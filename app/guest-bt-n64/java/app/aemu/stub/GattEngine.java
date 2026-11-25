package app.aemu.stub;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The GATT client machinery shared by the API 23-27 groups, on top of the host bridge's GATT ops.
 * Attribute ids the host assigns at discovery double as the "instance ids" (24+: handles) the framework
 * wants, so an attribute's host id is what the framework hands back to us on read/write. This class owns
 * clients, connections, the attribute tables and host-event parsing; the per-group BtGatt classes own the
 * binder surface and format the callbacks, which differ per generation.
 */
final class GattEngine {
    static final int GATT_ERROR = 133;
    static final int GATT_LOCAL_TERMINATED = 22;
    static final int KIND_SERVICE = 1, KIND_CHAR = 2, KIND_DESC = 3;
    private static final int STATE_CONNECTED = 2;
    static final int TYPE_PRIMARY = 0, TYPE_SECONDARY = 1;

    static final class Attr {
        int id, kind, parent;
        long props;
        UUID uuid;
    }

    static final class Conn {
        final int host;
        final String address;
        final int clientIf;
        final Map<Integer, Attr> attrs = new LinkedHashMap<Integer, Attr>();
        Conn(int host, String address, int clientIf) { this.host = host; this.address = address; this.clientIf = clientIf; }
    }

    private final class ClientRec implements IBinder.DeathRecipient {
        final int id;
        final IBinder binder;
        final Map<String, Conn> conns = new HashMap<String, Conn>();
        ClientRec(int id, IBinder binder) { this.id = id; this.binder = binder; }
        @Override public void binderDied() { unregisterClientInternal(id); }
    }

    protected final BtCore core;
    private final Object lock = new Object();
    private final Map<Integer, ClientRec> clients = new HashMap<Integer, ClientRec>();
    private final Map<Integer, Conn> byHost = new HashMap<Integer, Conn>();
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private int nextClient = 1;

    GattEngine(BtCore core, Delivery delivery) { this.core = core; this.delivery = delivery; }

    /** Delivery surface, implemented per group; always invoked on the callback thread. */
    interface Delivery {
        void deliverConnState(int clientIf, String addr, int status, boolean connected);
        void deliverServices(int clientIf, String addr, int status, List<Attr> attrs);
        void deliverAttr(int clientIf, String addr, Attr attr, Attr chr, Attr svc, int status, byte[] value, boolean isRead);
        void deliverNotify(int clientIf, String addr, Attr chr, Attr svc, byte[] value);
        void deliverMtu(int clientIf, String addr, int mtu, int status);
        void deliverExecuteWrite(int clientIf, String addr, int status);
        /** A client binder died or was unregistered: the group drops its scan state for this client. */
        void onClientGone(int clientIf);
    }

    private final Delivery delivery;

    /** Callbacks go out in order on their own thread, so neither a binder thread nor the host reader waits on an app. */
    protected final void deliver(Runnable r) {
        callbacks.execute(new Runnable() {
            public void run() {
                try { r.run(); } catch (Throwable e) { BtLog.w("gatt callback failed", e); }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // client registry

    /** Returns the new clientIf, or -1 when the callback binder is already dead. */
    final int registerClientInternal(IBinder cb) {
        if (cb == null) return -1;
        ClientRec rec;
        synchronized (lock) { rec = new ClientRec(nextClient++, cb); clients.put(rec.id, rec); }
        try { cb.linkToDeath(rec, 0); } catch (RemoteException e) { unregisterClientInternal(rec.id); return -1; }
        return rec.id;
    }

    final void unregisterClientInternal(int clientIf) {
        ClientRec rec;
        synchronized (lock) { rec = clients.remove(clientIf); }
        if (rec == null) return;
        try { rec.binder.unlinkToDeath(rec, 0); } catch (Throwable ignored) { }
        List<Conn> conns;
        synchronized (lock) {
            conns = new ArrayList<Conn>(rec.conns.values());
            rec.conns.clear();
            for (Conn k : conns) byHost.remove(k.host);
        }
        for (Conn k : conns) core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(k.host).build());
        delivery.onClientGone(clientIf);
    }

    final boolean hasClient(int clientIf) { synchronized (lock) { return clients.containsKey(clientIf); } }

    private Conn conn(int clientIf, String address) {
        synchronized (lock) {
            ClientRec rec = clients.get(clientIf);
            return rec == null ? null : rec.conns.get(address.toUpperCase());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // connections

    final void clientConnectInternal(final int clientIf, final String address) {
        final String addr = address.toUpperCase();
        if (!hasClient(clientIf)) return;
        if (!core.isOn() || !core.has(BtCore.CAP_GATT)) {
            deliver(new Runnable() { public void run() { delivery.deliverConnState(clientIf, addr, GATT_ERROR, false); } });
            return;
        }
        synchronized (lock) {
            ClientRec rec = clients.get(clientIf);
            if (rec != null && rec.conns.containsKey(addr)) {
                deliver(new Runnable() { public void run() { delivery.deliverConnState(clientIf, addr, 0, true); } });
                return;
            }
        }
        BtHost.Result res = core.host.request(BtHost.OP_GATT_OPEN, new BtHost.Wr().string(addr).build());
        if (!res.ok() || res.data.length < 4) {
            deliver(new Runnable() { public void run() { delivery.deliverConnState(clientIf, addr, GATT_ERROR, false); } });
            return;
        }
        int hostConn = BtHost.get32(res.data, 0);
        Conn k = new Conn(hostConn, addr, clientIf);
        synchronized (lock) {
            ClientRec rec = clients.get(clientIf);
            if (rec == null) { core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(hostConn).build()); return; }
            rec.conns.put(addr, k);
            byHost.put(hostConn, k);
        }
    }

    final void clientDisconnectInternal(final int clientIf, final String address) {
        if (!hasClient(clientIf)) return;
        final String addr = address.toUpperCase();
        Conn k;
        synchronized (lock) {
            ClientRec rec = clients.get(clientIf);
            k = rec == null ? null : rec.conns.remove(addr);
            if (k != null) byHost.remove(k.host);
        }
        if (k != null) core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(k.host).build());
        // the host reports nothing for a local close, but the framework waits for this callback
        deliver(new Runnable() { public void run() { delivery.deliverConnState(clientIf, addr, 0, false); } });
    }

    public void hostLost() {
        List<Conn> all;
        synchronized (lock) {
            all = new ArrayList<Conn>(byHost.values());
            byHost.clear();
            for (ClientRec rec : clients.values()) rec.conns.clear();
        }
        for (final Conn k : all) {
            deliver(new Runnable() { public void run() { delivery.deliverConnState(k.clientIf, k.address, GATT_LOCAL_TERMINATED, false); } });
        }
    }

    public int connectionState(String addr) {
        synchronized (lock) {
            for (Conn k : byHost.values()) if (k.address.equals(addr)) return STATE_CONNECTED;
        }
        return 0;
    }

    /** Drops every live connection of every client (IBluetoothGatt.disconnectAll). */
    final void disconnectAllInternal() {
        List<Conn> all;
        synchronized (lock) { all = new ArrayList<Conn>(byHost.values()); }
        for (Conn k : all) clientDisconnectInternal(k.clientIf, k.address);
    }

    final List<BluetoothDevice> devicesMatching(int[] states) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        boolean wantConnected = false;
        if (states != null) for (int s : states) if (s == STATE_CONNECTED) wantConnected = true;
        if (!wantConnected) return out;
        List<String> seen = new ArrayList<String>();
        synchronized (lock) {
            for (Conn k : byHost.values()) if (!seen.contains(k.address)) seen.add(k.address);
        }
        for (String a : seen) out.add(BtUtil.device(a));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // discovery and attribute access

    final void discoverInternal(final int clientIf, final String address) {
        final Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_DISCOVER, new BtHost.Wr().u32(k.host).build());
        if (!res.ok()) {
            deliver(new Runnable() { public void run() { delivery.deliverServices(clientIf, k.address, GATT_ERROR, new ArrayList<Attr>()); } });
        }
    }

    final void readInternal(int clientIf, String address, int attrId) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_READ, new BtHost.Wr().u32(k.host).u16(attrId).build());
        if (!res.ok()) attrResult(k, attrId, GATT_ERROR, new byte[0], true);
    }

    final void writeInternal(int clientIf, String address, int attrId, int writeType, byte[] value) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        // Android: 1 no response, 2 default, 4 signed. The host knows the first two; signed goes as a normal write.
        int type = writeType == 1 ? 1 : 2;
        byte[] v = value == null ? new byte[0] : value;
        if (v.length > 512) { attrResult(k, attrId, GATT_ERROR, null, false); return; }
        BtHost.Result res = core.host.request(BtHost.OP_GATT_WRITE,
                new BtHost.Wr().u32(k.host).u16(attrId).u8(type).bytes(v).build());
        if (!res.ok()) attrResult(k, attrId, GATT_ERROR, null, false);
    }

    final void notifyInternal(int clientIf, String address, int attrId, boolean enable) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        core.host.request(BtHost.OP_GATT_NOTIFY, new BtHost.Wr().u32(k.host).u16(attrId).u8(enable ? 1 : 0).build());
    }

    final void mtuInternal(int clientIf, String address, int mtu) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_MTU, new BtHost.Wr().u32(k.host).u16(mtu).build());
        if (!res.ok()) {
            final int cif = clientIf;
            final String addr = k.address;
            deliver(new Runnable() { public void run() { delivery.deliverMtu(cif, addr, 23, GATT_ERROR); } });
        }
    }

    final void endReliableWriteInternal(final int clientIf, final String address, boolean execute) {
        final Conn k = conn(clientIf, address);
        if (k == null) return;
        // reliable writes are not staged on the host link; say so instead of pretending the queue was written
        deliver(new Runnable() { public void run() { delivery.deliverExecuteWrite(clientIf, k.address, GATT_ERROR); } });
    }

    final void readRssiInternal(final int clientIf, final String address) {
        // the host protocol has no RSSI read; the group classes answer through their own callback shape
    }

    final Attr findAttr(int clientIf, String address, int attrId) {
        Conn k = conn(clientIf, address);
        if (k == null) return null;
        synchronized (lock) { return k.attrs.get(attrId); }
    }

    final List<Attr> attrsSnapshot(int clientIf, String address) {
        Conn k = conn(clientIf, address);
        if (k == null) return new ArrayList<Attr>();
        synchronized (lock) { return new ArrayList<Attr>(k.attrs.values()); }
    }

    /** First characteristic with this UUID whose host id lies in [startId, endId], or null. */
    final Attr findCharByUuid(int clientIf, String address, UUID uuid, int startId, int endId) {
        for (Attr a : attrsSnapshot(clientIf, address)) {
            if (a.kind == KIND_CHAR && a.uuid.equals(uuid) && a.id >= startId && a.id <= endId) return a;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // host events

    public void onHostEvent(int op, BtHost.Rd r) {
        final int hostConn = (int) r.u32();
        final Conn k;
        synchronized (lock) { k = byHost.get(hostConn); }
        if (k == null) return;
        switch (op) {
            case BtHost.EV_GATT_STATE: {
                final boolean connected = r.u8() != 0;
                final int status = fix(r.i32());
                if (!connected) {
                    // the remote dropped us: release the host handle; the app reconnects with a new clientConnect
                    synchronized (lock) {
                        ClientRec rec = clients.get(k.clientIf);
                        if (rec != null) rec.conns.remove(k.address);
                        byHost.remove(hostConn);
                    }
                    core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(hostConn).build());
                }
                deliver(new Runnable() { public void run() { delivery.deliverConnState(k.clientIf, k.address, status, connected); } });
                break;
            }
            case BtHost.EV_GATT_SERVICES: servicesEvent(k, r.i32(), r); break;
            case BtHost.EV_GATT_READ: {
                int attr = r.u16();
                int status = fix(r.i32());
                attrResult(k, attr, status, r.rest(), true);
                break;
            }
            case BtHost.EV_GATT_WRITE: {
                int attr = r.u16();
                attrResult(k, attr, fix(r.i32()), null, false);
                break;
            }
            case BtHost.EV_GATT_CHANGED: {
                int attr = r.u16();
                notifyEvent(k, attr, r.rest());
                break;
            }
            case BtHost.EV_GATT_MTU: {
                final int mtu = r.u16();
                final int status = fix(r.i32());
                deliver(new Runnable() { public void run() { delivery.deliverMtu(k.clientIf, k.address, mtu, status); } });
                break;
            }
            default: break;
        }
    }

    private static int fix(int status) { return status < 0 ? GATT_ERROR : status; }

    private void servicesEvent(final Conn k, int status, BtHost.Rd r) {
        if (status != 0) {
            final int st = fix(status);
            deliver(new Runnable() { public void run() { delivery.deliverServices(k.clientIf, k.address, st, new ArrayList<Attr>()); } });
            return;
        }
        int n = r.u16();
        final List<Attr> list = new ArrayList<Attr>();
        synchronized (lock) {
            k.attrs.clear();
            for (int i = 0; i < n; i++) {
                Attr a = new Attr();
                a.id = r.u16(); a.kind = r.u8(); a.uuid = r.uuid(); a.parent = r.u16(); a.props = r.u32();
                k.attrs.put(a.id, a);
                list.add(a);
            }
        }
        deliver(new Runnable() { public void run() { delivery.deliverServices(k.clientIf, k.address, 0, list); } });
    }

    /** Read/write result for a characteristic or descriptor, resolved against the discovered attribute table. */
    private void attrResult(final Conn k, int attrId, final int status, final byte[] value, final boolean isRead) {
        final Attr a, c, s;
        synchronized (lock) {
            a = k.attrs.get(attrId);
            Attr cc = a == null ? null : a.kind == KIND_DESC ? k.attrs.get(a.parent) : a;
            c = cc;
            s = cc == null ? null : k.attrs.get(cc.parent);
        }
        if (a == null || c == null || s == null) {
            if (a == null) BtLog.w("gatt result for unknown attribute " + attrId + " dropped", null);
            return;
        }
        final byte[] v = value == null ? new byte[0] : value;
        deliver(new Runnable() { public void run() { delivery.deliverAttr(k.clientIf, k.address, a, c, s, status, v, isRead); } });
    }

    private void notifyEvent(final Conn k, int attrId, final byte[] value) {
        final Attr c, s;
        synchronized (lock) {
            c = k.attrs.get(attrId);
            s = c == null ? null : k.attrs.get(c.parent);
        }
        if (c == null || s == null || c.kind != KIND_CHAR) return;
        deliver(new Runnable() { public void run() { delivery.deliverNotify(k.clientIf, k.address, c, s, value); } });
    }

    // ---------------------------------------------------------------------------------------------------------
    // BluetoothGattService tree for the 24+ whole-tree delivery

    private static Method setInstanceIdService, setInstanceIdChar, setInstanceIdDesc;
    private static boolean instanceIdFailed;

    private static void setInstanceId(Object target, int id, int which) {
        try {
            Method m;
            synchronized (GattEngine.class) {
                if (which == 0 && setInstanceIdService == null) setInstanceIdService = BluetoothGattService.class.getMethod("setInstanceId", int.class);
                if (which == 1 && setInstanceIdChar == null) setInstanceIdChar = BluetoothGattCharacteristic.class.getMethod("setInstanceId", int.class);
                if (which == 2 && setInstanceIdDesc == null) setInstanceIdDesc = BluetoothGattDescriptor.class.getMethod("setInstanceId", int.class);
                m = which == 0 ? setInstanceIdService : which == 1 ? setInstanceIdChar : setInstanceIdDesc;
            }
            m.invoke(target, id);
        } catch (Throwable e) {
            if (!instanceIdFailed) { instanceIdFailed = true; BtLog.w("setInstanceId unavailable; handles will not round-trip", e); }
        }
    }

    /**
     * Builds the framework service tree from the host attribute table. The host id becomes the instance id,
     * which the 24+ framework hands back to us as the attribute handle. The host protocol carries no ATT
     * permissions, so characteristics and descriptors are marked readable/writable; the real device still
     * enforces its own permissions on every operation the host forwards.
     */
    static List<BluetoothGattService> buildTree(List<Attr> attrs) {
        Map<Integer, BluetoothGattService> services = new LinkedHashMap<Integer, BluetoothGattService>();
        Map<Integer, BluetoothGattCharacteristic> chars = new HashMap<Integer, BluetoothGattCharacteristic>();
        List<BluetoothGattService> top = new ArrayList<BluetoothGattService>();
        for (Attr a : attrs) {
            if (a.kind != KIND_SERVICE) continue;
            BluetoothGattService s = new BluetoothGattService(a.uuid, a.parent == 0 ? TYPE_PRIMARY : TYPE_SECONDARY);
            setInstanceId(s, a.id, 0);
            services.put(a.id, s);
            if (a.parent == 0) top.add(s);
        }
        for (Attr a : attrs) {
            if (a.kind != KIND_SERVICE) continue;
            if (a.parent != 0) {
                BluetoothGattService parent = services.get(a.parent);
                if (parent != null) parent.addService(services.get(a.id));
            }
        }
        for (Attr a : attrs) {
            if (a.kind != KIND_CHAR) continue;
            BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(a.uuid, (int) a.props,
                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
            setInstanceId(c, a.id, 1);
            chars.put(a.id, c);
            BluetoothGattService s = services.get(a.parent);
            if (s != null) s.addCharacteristic(c);
        }
        for (Attr a : attrs) {
            if (a.kind != KIND_DESC) continue;
            BluetoothGattDescriptor d = new BluetoothGattDescriptor(a.uuid,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            setInstanceId(d, a.id, 2);
            BluetoothGattCharacteristic c = chars.get(a.parent);
            if (c != null) c.addDescriptor(d);
        }
        return top;
    }
}
