package app.aemu.stub;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The adapter logic shared by the API 23-27 groups: state, discovery, bonding and RFCOMM on top of the host
 * bridge ({@link BtHost}). The per-group BtAdapter classes are thin IBluetooth.Stub shells over this class;
 * everything real comes from the host phone's adapter. What the host cannot do without root (discoverable
 * mode, the pairing UI, removing a bond) is emulated inside the guest and never touches the phone.
 */
final class BtCore implements BtHost.Listener {
    static final int STATE_OFF = 10, STATE_TURNING_ON = 11, STATE_ON = 12, STATE_TURNING_OFF = 13;
    static final int BOND_NONE = 10, BOND_BONDING = 11, BOND_BONDED = 12;
    static final int SCAN_NONE = 20, SCAN_CONNECTABLE = 21, SCAN_DISCOVERABLE = 23;
    static final int PROFILE_DISCONNECTED = 0, PROFILE_CONNECTED = 2;
    static final int ACCESS_UNKNOWN = 0;
    static final int PERM_PHONEBOOK = 0, PERM_MESSAGE = 1, PERM_SIM = 2;
    static final int CAP_CLASSIC_SCAN = 1, CAP_LE_SCAN = 2, CAP_SPP = 4, CAP_GATT = 8, CAP_LISTEN = 32;

    static final class Dev {
        String name = "";
        int kind;          // 1 classic, 2 LE, 3 both
        int cls;
    }

    private final Object lock = new Object();
    final BtHost host = new BtHost(this);
    private final BtSpp spp = new BtSpp(host);
    private final RemoteCallbackList<IBluetoothCallback> callbacks = new RemoteCallbackList<IBluetoothCallback>();
    private final RemoteCallbackList<IBluetoothManagerCallback> managerCallbacks = new RemoteCallbackList<IBluetoothManagerCallback>();
    private final RemoteCallbackList<IBluetoothStateChangeCallback> stateCallbacks = new RemoteCallbackList<IBluetoothStateChangeCallback>();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();

    private GattSide gatt;
    private IBinder adapterBinder;
    private boolean hostUp, hostEnabled, guestOn = true;
    private int hostCaps;
    private String hostName = "";
    private String nameOverride;
    private int state = STATE_OFF;
    private int scanMode = SCAN_CONNECTABLE;
    private int discoverableTimeout = 120;
    private java.util.concurrent.ScheduledFuture<?> discoverableRevert;
    private boolean discovering;
    private long discoveryEndMillis;
    private int leClients;
    private final Map<String, Dev> known = new HashMap<String, Dev>();
    private final Set<String> hostBonded = new HashSet<String>();
    private final Set<String> removed = new HashSet<String>();      // bonds the guest dropped; the host keeps its own
    private final Map<String, Integer> bonding = new HashMap<String, Integer>();
    private final Set<String> initiatedByUs = new HashSet<String>();
    private final Map<String, int[]> accessPerms = new HashMap<String, int[]>();
    private final Set<IBinder> bleApps = new HashSet<IBinder>();

    // ---------------------------------------------------------------------------------------------------------
    // state

    void attachGatt(GattSide g) { gatt = g; }
    void setAdapterBinder(IBinder b) { adapterBinder = b; }
    void start() { host.start(); }

    int state() { synchronized (lock) { return state; } }
    boolean isOn() { return state() == STATE_ON; }
    boolean has(int cap) { return (hostCaps & cap) != 0; }

    private int computeState() { return hostUp && hostEnabled && guestOn ? STATE_ON : STATE_OFF; }

    /** Moves the visible state to what the host and the guest switch say, with the intermediate steps apps expect. */
    private void refreshState() {
        int from, to;
        synchronized (lock) {
            from = state;
            to = computeState();
            if (from == to) return;
            state = to;
        }
        int mid = to == STATE_ON ? STATE_TURNING_ON : STATE_TURNING_OFF;
        stateBroadcast(from, mid);
        stateBroadcast(mid, to);
        if (to == STATE_OFF) {
            synchronized (lock) {
                discovering = false;
                scanMode = SCAN_CONNECTABLE;
                bonding.clear();
            }
            if (gatt != null) gatt.hostLost();
            spp.closeAll();
        }
        notifyUpDown(to == STATE_ON);
        if (to == STATE_ON) {
            boolean scan;
            synchronized (lock) { scan = discovering || leClients > 0; }
            if (scan) applyScan();       // LE scan clients registered while the host link was down
        }
    }

    private void stateBroadcast(int prev, int now) {
        Intent i = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        i.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prev);
        i.putExtra(BluetoothAdapter.EXTRA_STATE, now);
        i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        BtUtil.broadcast(i, BtUtil.PERM_BLUETOOTH);
        int n = callbacks.beginBroadcast();
        try {
            for (int k = 0; k < n; k++) {
                try { callbacks.getBroadcastItem(k).onBluetoothStateChange(prev, now); } catch (RemoteException ignored) { }
            }
        } finally { callbacks.finishBroadcast(); }
    }

    private void notifyUpDown(boolean up) {
        int n = stateCallbacks.beginBroadcast();
        try {
            for (int k = 0; k < n; k++) {
                try { stateCallbacks.getBroadcastItem(k).onBluetoothStateChange(up); } catch (RemoteException ignored) { }
            }
        } finally { stateCallbacks.finishBroadcast(); }
    }

    // IBluetoothManagerCallback: the adapter object stays "up" for the whole life of the service; BluetoothAdapter
    // asks isEnabled()/getState() on it, which answer STATE_OFF while the host adapter is off or the link is down.
    IBinder registerAdapter(IBluetoothManagerCallback cb) {
        if (cb != null) managerCallbacks.register(cb);
        return adapterBinder;
    }
    void unregisterAdapter(IBluetoothManagerCallback cb) { if (cb != null) managerCallbacks.unregister(cb); }
    void registerStateChange(IBluetoothStateChangeCallback cb) { if (cb != null) stateCallbacks.register(cb); }
    void unregisterStateChange(IBluetoothStateChangeCallback cb) { if (cb != null) stateCallbacks.unregister(cb); }

    // ---------------------------------------------------------------------------------------------------------
    // BtHost.Listener

    @Override public void onConnected(int caps, boolean enabled, String name) {
        synchronized (lock) { hostUp = true; hostCaps = caps; hostEnabled = enabled; hostName = name == null ? "" : name; }
        BtLog.i("host link up (caps=" + caps + ", adapter " + (enabled ? "on" : "off") + ")");
        refreshHostBonded();
        refreshState();
    }

    @Override public void onDisconnected() {
        synchronized (lock) { hostUp = false; }
        BtLog.i("host link down");
        refreshState();
    }

    @Override public void onEvent(int op, byte[] payload) {
        BtHost.Rd r = new BtHost.Rd(payload);
        switch (op) {
            case BtHost.EV_ADAPTER_STATE: {
                boolean on = r.u8() != 0;
                synchronized (lock) { hostEnabled = on; }
                refreshState();
                break;
            }
            case BtHost.EV_SCAN_RESULT: scanResult(r); break;
            case BtHost.EV_SCAN_DONE: scanDone(); break;
            case BtHost.EV_BOND_STATE: hostBond(r.string().toUpperCase(), r.u8(), r.u8()); break;
            case BtHost.EV_SPP_DATA: case BtHost.EV_SPP_CLOSED: case BtHost.EV_SPP_ACCEPT: spp.onEvent(op, r); break;
            default:
                if (op >= BtHost.EV_GATT_STATE && op <= BtHost.EV_GATT_MTU && gatt != null) gatt.onHostEvent(op, r);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // discovery (classic) and the shared host scan

    private void scanResult(BtHost.Rd r) {
        String addr = r.string().toUpperCase();
        String name = r.string();
        int rssi = r.i16();
        int kind = r.u8();
        int cls = (int) r.u32();
        byte[] record = r.bytes(r.u16());
        remember(addr, name, kind, cls);
        if (kind == 2) {
            if (gatt != null) gatt.leResult(addr, rssi, record);
        } else {
            boolean want;
            synchronized (lock) { want = discovering; }
            if (!want) return;
            Intent i = new Intent(BluetoothDevice.ACTION_FOUND);
            i.putExtra(BluetoothDevice.EXTRA_DEVICE, BtUtil.device(addr));
            i.putExtra(BluetoothDevice.EXTRA_RSSI, (short) rssi);
            if (name.length() > 0) i.putExtra(BluetoothDevice.EXTRA_NAME, name);
            if (cls != 0) i.putExtra(BluetoothDevice.EXTRA_CLASS, BtUtil.deviceClass(cls));
            BtUtil.broadcast(i, BtUtil.PERM_BLUETOOTH);
        }
    }

    private void scanDone() {
        boolean was;
        boolean le;
        synchronized (lock) {
            was = discovering;
            discovering = false;
            discoveryEndMillis = System.currentTimeMillis();
            le = leClients > 0;
        }
        if (was) BtUtil.broadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED), BtUtil.PERM_BLUETOOTH);
        // the host stops forwarding LE results once its classic phase reports done: start the LE-only scan again
        if (le) applyScan();
    }

    /** One host scan serves both classic discovery and LE scan clients. */
    int applyScan() {
        int mode;
        synchronized (lock) { mode = (discovering ? 1 : 0) | (leClients > 0 ? 2 : 0); }
        BtHost.Result res = mode == 0
                ? host.request(BtHost.OP_SCAN_STOP, new byte[0])
                : host.request(BtHost.OP_SCAN_START, new BtHost.Wr().u8(mode).build());
        return res.status;
    }

    int leClientDelta(int d) {
        synchronized (lock) { leClients = Math.max(0, leClients + d); }
        return applyScan();
    }

    void remember(String addr, String name, int kind, int cls) {
        synchronized (lock) {
            Dev d = known.get(addr);
            if (d == null) { d = new Dev(); known.put(addr, d); }
            if (name != null && name.length() > 0) d.name = name;
            if (kind != 0) d.kind |= kind;
            if (cls != 0) d.cls = cls;
        }
    }

    private Dev dev(String addr) { synchronized (lock) { return known.get(addr); } }

    long discoveryEndMillis() { synchronized (lock) { return discoveryEndMillis; } }

    // ---------------------------------------------------------------------------------------------------------
    // bonding

    private void refreshHostBonded() {
        BtHost.Result res = host.request(BtHost.OP_BONDED, new byte[0]);
        if (!res.ok()) return;
        try {
            BtHost.Rd r = new BtHost.Rd(res.data);
            int n = r.u16();
            Set<String> now = new HashSet<String>();
            for (int k = 0; k < n; k++) {
                String addr = r.string().toUpperCase();
                String name = r.string();
                int kind = r.u8();
                int cls = (int) r.u32();
                now.add(addr);
                remember(addr, name, kind, cls);
            }
            synchronized (lock) { hostBonded.clear(); hostBonded.addAll(now); }
        } catch (RuntimeException e) {
            BtLog.w("bad BONDED reply", e);
        }
    }

    private int bondOf(String addr) {
        synchronized (lock) {
            Integer b = bonding.get(addr);
            if (b != null) return b;
            return hostBonded.contains(addr) && !removed.contains(addr) ? BOND_BONDED : BOND_NONE;
        }
    }

    private void bondBroadcast(String addr, int now, int prev) {
        Intent i = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        i.putExtra(BluetoothDevice.EXTRA_DEVICE, BtUtil.device(addr));
        i.putExtra(BluetoothDevice.EXTRA_BOND_STATE, now);
        i.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, prev);
        BtUtil.broadcast(i, BtUtil.PERM_BLUETOOTH);
    }

    /** Bond progress reported by the phone (it runs the real pairing dialog itself). */
    private void hostBond(String addr, int now, int prev) {
        int before;
        synchronized (lock) {
            before = bondOf(addr);
            if (now == BOND_BONDING) bonding.put(addr, BOND_BONDING);
            else {
                bonding.remove(addr);
                initiatedByUs.remove(addr);
                if (now == BOND_BONDED) { hostBonded.add(addr); removed.remove(addr); }
                else hostBonded.remove(addr);
            }
        }
        if (before != now) bondBroadcast(addr, now, before);
    }

    boolean bondingInitiatedLocally(String addr) { synchronized (lock) { return initiatedByUs.contains(addr); } }

    // ---------------------------------------------------------------------------------------------------------
    // adapter surface (the per-group BtAdapter shells forward here)

    boolean isEnabled() { return state() == STATE_ON; }

    boolean enable() {
        boolean ok;
        synchronized (lock) {
            ok = hostUp && hostEnabled;     // an app cannot switch the phone's adapter on
            if (ok) guestOn = true;
        }
        if (!ok) return false;
        int before = state();
        refreshState();
        if (before == STATE_ON) {
            // already on: replay the transition so a freshly registered receiver (the setup wizard) still sees it
            sched.schedule(new Runnable() {
                public void run() { stateBroadcast(STATE_TURNING_ON, STATE_ON); }
            }, 300, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    boolean enableNoAutoConnect() { return enable(); }

    boolean disable() {
        synchronized (lock) { guestOn = false; }
        refreshState();
        return true;
    }

    String getAddress() {
        BtHost.Result res = host.request(BtHost.OP_ADAPTER_INFO, new byte[0]);
        if (res.ok()) {
            try {
                BtHost.Rd r = new BtHost.Rd(res.data);
                r.u8(); r.string();
                return r.string();
            } catch (RuntimeException ignored) { }
        }
        return "02:00:00:00:00:00";
    }

    ParcelUuid[] getUuids() { return null; }

    boolean setName(String name) {
        synchronized (lock) { nameOverride = name; }
        return true;
    }

    String getName() {
        synchronized (lock) {
            if (nameOverride != null) return nameOverride;
            return hostName.length() > 0 ? hostName : "Android";
        }
    }

    int getScanMode() {
        synchronized (lock) { return state == STATE_ON ? scanMode : SCAN_NONE; }
    }

    /** The phone's own discoverable mode needs a user prompt on the host, so the mode is tracked in the guest only. */
    boolean setScanMode(int mode, int duration) {
        if (mode != SCAN_NONE && mode != SCAN_CONNECTABLE && mode != SCAN_DISCOVERABLE) return false;
        int prev;
        synchronized (lock) {
            if (state != STATE_ON) return false;
            prev = scanMode;
            scanMode = mode;
            if (discoverableRevert != null) { discoverableRevert.cancel(false); discoverableRevert = null; }
            if (mode == SCAN_DISCOVERABLE && duration > 0) {
                discoverableRevert = sched.schedule(new Runnable() {
                    public void run() { setScanMode(SCAN_CONNECTABLE, 0); }
                }, duration, TimeUnit.SECONDS);
            }
        }
        if (prev != mode) scanModeBroadcast(mode, prev);
        return true;
    }

    private void scanModeBroadcast(int now, int prev) {
        Intent i = new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        i.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, now);
        i.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_SCAN_MODE, prev);
        i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        BtUtil.broadcast(i, BtUtil.PERM_BLUETOOTH);
    }

    int getDiscoverableTimeout() { synchronized (lock) { return discoverableTimeout; } }
    boolean setDiscoverableTimeout(int timeout) { synchronized (lock) { discoverableTimeout = timeout; } return true; }

    boolean startDiscovery() {
        if (!isOn() || !has(CAP_CLASSIC_SCAN)) return false;
        synchronized (lock) { discovering = true; }
        BtUtil.broadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED), BtUtil.PERM_BLUETOOTH);
        if (applyScan() != BtHost.OK) {
            synchronized (lock) { discovering = false; }
            BtUtil.broadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED), BtUtil.PERM_BLUETOOTH);
            return false;
        }
        return true;
    }

    boolean cancelDiscovery() {
        boolean was;
        synchronized (lock) { was = discovering; discovering = false; }
        if (!was) return true;
        applyScan();
        BtUtil.broadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED), BtUtil.PERM_BLUETOOTH);
        return true;
    }

    boolean isDiscovering() { synchronized (lock) { return discovering; } }

    int getAdapterConnectionState() { return PROFILE_DISCONNECTED; }
    int getProfileConnectionState(int profile) { return PROFILE_DISCONNECTED; }
    int getConnectionState(String addr) { return gatt != null ? gatt.connectionState(addr) : PROFILE_DISCONNECTED; }

    BluetoothDevice[] getBondedDevices() {
        if (!isOn()) return new BluetoothDevice[0];
        refreshHostBonded();
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        synchronized (lock) {
            for (String a : hostBonded) if (!removed.contains(a)) out.add(BtUtil.device(a));
        }
        return out.toArray(new BluetoothDevice[out.size()]);
    }

    boolean createBond(String addr) {
        if (addr == null || !isOn()) return false;
        int cur = bondOf(addr);
        if (cur != BOND_NONE) return false;
        boolean hostHas;
        synchronized (lock) { hostHas = hostBonded.contains(addr); }
        if (hostHas) {
            // the phone already trusts this device; the guest just takes its own bond back, no host dialog
            synchronized (lock) { removed.remove(addr); }
            bondBroadcast(addr, BOND_BONDING, BOND_NONE);
            bondBroadcast(addr, BOND_BONDED, BOND_BONDING);
            return true;
        }
        if (!has(BtHost.CAP_BOND)) return false;
        synchronized (lock) { bonding.put(addr, BOND_BONDING); initiatedByUs.add(addr); }
        BtHost.Result res = host.request(BtHost.OP_BOND_CREATE, new BtHost.Wr().string(addr).build(), 20000);
        if (!res.ok()) {
            synchronized (lock) { bonding.remove(addr); initiatedByUs.remove(addr); }
            return false;
        }
        bondBroadcast(addr, BOND_BONDING, BOND_NONE);
        return true;
    }

    boolean cancelBondProcess(String addr) {
        if (addr == null) return false;
        boolean was;
        synchronized (lock) { was = bonding.remove(addr) != null; initiatedByUs.remove(addr); }
        if (was) bondBroadcast(addr, BOND_NONE, BOND_BONDING);
        return was;
    }

    /** Guest-only: dropping a bond here must never unpair the user's real headphones, car or watch on the phone. */
    boolean removeBond(String addr) {
        if (addr == null) return false;
        int before = bondOf(addr);
        if (before == BOND_NONE) return false;
        synchronized (lock) { removed.add(addr); bonding.remove(addr); initiatedByUs.remove(addr); }
        bondBroadcast(addr, BOND_NONE, before);
        return true;
    }

    int getBondState(String addr) { return addr == null ? BOND_NONE : bondOf(addr); }

    String getRemoteName(String addr) {
        Dev d = dev(addr);
        return d != null && d.name.length() > 0 ? d.name : null;
    }

    int getRemoteType(String addr) {
        Dev d = dev(addr);
        return d == null ? 0 : d.kind;          // DEVICE_TYPE_CLASSIC 1, LE 2, DUAL 3, UNKNOWN 0 - same numbers
    }

    String getRemoteAlias(String addr) { return getRemoteName(addr); }
    boolean setRemoteAlias(String addr, String name) { return true; }

    int getRemoteClass(String addr) {
        Dev d = dev(addr);
        return d != null && d.cls != 0 ? d.cls : 0xFF000000;      // BluetoothClass.ERROR
    }

    ParcelUuid[] getRemoteUuids(String addr) { return null; }
    boolean fetchRemoteUuids(String addr) { return false; }
    boolean sdpSearch(String addr) { return false; }

    // pairing input is handled by the phone's own dialog; accept so the guest UI does not report a failure
    boolean setPin(String addr, boolean accept, int len, byte[] pinCode) { return true; }
    boolean setPasskey(String addr, boolean accept, int len, byte[] passkey) { return true; }
    boolean setPairingConfirmation(String addr, boolean accept) { return true; }

    void sendConnectionStateChange(String addr, int profile, int state, int prevState) { }
    void registerCallback(IBluetoothCallback callback) { if (callback != null) callbacks.register(callback); }
    void unregisterCallback(IBluetoothCallback callback) { if (callback != null) callbacks.unregister(callback); }

    ParcelFileDescriptor connectSocket(String addr, int type, ParcelUuid uuid, int port, int flag) {
        if (addr == null || type != 1 || uuid == null || !isOn() || !has(CAP_SPP)) return null;   // RFCOMM by UUID only
        try {
            return spp.connect(addr, uuid.getUuid(), (flag & 3) != 0);
        } catch (Throwable e) {
            BtLog.w("connectSocket failed", e);
            return null;
        }
    }

    /** BluetoothServerSocket (RFCOMM by UUID): the phone listens and every incoming connection is handed over as an fd. */
    ParcelFileDescriptor createSocketChannel(int type, String serviceName, ParcelUuid uuid, int port, int flag) {
        if (type != 1 || uuid == null || !isOn() || !has(CAP_SPP) || !has(CAP_LISTEN)) return null;
        try {
            return spp.listen(serviceName, uuid.getUuid(), (flag & 3) != 0);
        } catch (Throwable e) {
            BtLog.w("createSocketChannel failed", e);
            return null;
        }
    }

    boolean configHciSnoopLog(boolean enable) { return false; }
    boolean factoryReset() { return false; }   // never let a guest wipe the phone's real pairings

    // ---------------------------------------------------------------------------------------------------------
    // per-device access permissions (phonebook / message / SIM): guest-local bookkeeping, defaults UNKNOWN

    int getAccessPermission(String addr, int which) {
        synchronized (lock) {
            int[] p = accessPerms.get(addr);
            return p == null ? ACCESS_UNKNOWN : p[which];
        }
    }

    boolean setAccessPermission(String addr, int which, int value) {
        synchronized (lock) {
            int[] p = accessPerms.get(addr);
            if (p == null) { p = new int[3]; accessPerms.put(addr, p); }
            p[which] = value;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // BLE app bookkeeping for IBluetoothManager (the count is real; always-available scan is not offered)

    int updateBleAppCount(IBinder b, boolean enable) {
        if (b == null) return 0;
        synchronized (lock) {
            if (enable) bleApps.add(b); else bleApps.remove(b);
            return bleApps.size();
        }
    }

    boolean isBleAppPresent() { synchronized (lock) { return !bleApps.isEmpty(); } }
    boolean isBleScanAlwaysAvailable() { return false; }

    String dumpText() {
        synchronized (lock) {
            return "aemu-bt (N64 bridge client)\nstate=" + state + " hostUp=" + hostUp + " hostEnabled=" + hostEnabled
                    + " caps=" + hostCaps + " known=" + known.size() + " bonded=" + hostBonded.size()
                    + " leClients=" + leClients + "\n";
        }
    }
}
