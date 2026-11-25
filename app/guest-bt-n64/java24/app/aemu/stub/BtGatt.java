package app.aemu.stub;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.WorkSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IBluetoothGatt for API 24-25 (7.0-7.1): attributes are addressed by handle (the host's attribute id,
 * handed back to the framework as the characteristic/descriptor instance id at discovery), and discovery
 * is delivered as one whole BluetoothGattService tree in onSearchComplete. GATT server and
 * multi-advertising are not bridged: registerServer and startMultiAdvertising answer with an error
 * callback at once.
 */
final class BtGatt extends IBluetoothGatt.Stub implements GattSide, GattEngine.Delivery {
    private final BtCore core;
    private final GattEngine engine;
    private final Map<Integer, IBluetoothGattCallback> cbs = new HashMap<Integer, IBluetoothGattCallback>();
    private final Map<Integer, LeScanState> scans = new HashMap<Integer, LeScanState>();
    private final Map<Integer, AdvertiseSettings> advSettings = new HashMap<Integer, AdvertiseSettings>();
    private int nextServer = 1;

    BtGatt(BtCore core) { this.core = core; this.engine = new GattEngine(core, this); }

    @Override public void hostLost() { engine.hostLost(); }
    @Override public int connectionState(String addr) { return engine.connectionState(addr); }
    @Override public void onHostEvent(int op, BtHost.Rd r) { engine.onHostEvent(op, r); }

    private IBluetoothGattCallback cb(int clientIf) {
        synchronized (cbs) { return cbs.get(clientIf); }
    }

    // ---------------------------------------------------------------------------------------------------------
    // client registration and scan

    @Override public void registerClient(ParcelUuid appId, final IBluetoothGattCallback callback) {
        if (callback == null) return;
        final int id = engine.registerClientInternal(callback.asBinder());
        if (id < 0) return;
        synchronized (cbs) { cbs.put(id, callback); }
        engine.deliver(new Runnable() { public void run() {
            try { callback.onClientRegistered(0, id); } catch (RemoteException e) { engine.unregisterClientInternal(id); }
        } });
    }

    @Override public void unregisterClient(int clientIf) { engine.unregisterClientInternal(clientIf); }

    @Override public void onClientGone(int clientIf) {
        synchronized (cbs) { cbs.remove(clientIf); }
        LeScanState st;
        synchronized (scans) { st = scans.remove(clientIf); }
        synchronized (advSettings) { advSettings.remove(clientIf); }
        if (st != null && st.active) core.leClientDelta(-1);
    }

    @Override public void startScan(int appIf, boolean isServer, ScanSettings settings,
                                    List<ScanFilter> filters, WorkSource workSource, List scanStorages,
                                    String callingPackage) {
        if (isServer) return;
        if (!engine.hasClient(appIf) || !core.isOn() || !core.has(BtCore.CAP_LE_SCAN)) return;
        LeScanState st;
        synchronized (scans) {
            st = scans.get(appIf);
            if (st == null) { st = new LeScanState(); scans.put(appIf, st); }
        }
        boolean first = !st.active;
        st.configure(settings, filters);
        st.active = true;
        if (first) core.leClientDelta(+1);
    }

    @Override public void stopScan(int appIf, boolean isServer) {
        if (isServer) return;
        LeScanState st;
        synchronized (scans) { st = scans.get(appIf); }
        if (st == null || !st.active) return;
        st.active = false;
        core.leClientDelta(-1);
    }

    @Override public void flushPendingBatchResults(int appIf, boolean isServer) {
        if (isServer) return;
        LeScanState st;
        synchronized (scans) { st = scans.get(appIf); }
        final IBluetoothGattCallback callback = cb(appIf);
        if (st == null || callback == null) return;
        final List<ScanResult> out = st.drainBatch();
        if (out.isEmpty()) return;
        engine.deliver(new Runnable() { public void run() {
            try { callback.onBatchScanResults(out); } catch (RemoteException e) { engine.unregisterClientInternal(appIf); }
        } });
    }

    @Override public void leResult(String addr, int rssi, byte[] record) {
        List<Map.Entry<Integer, LeScanState>> targets = new ArrayList<Map.Entry<Integer, LeScanState>>();
        synchronized (scans) {
            for (Map.Entry<Integer, LeScanState> e : scans.entrySet()) {
                if (e.getValue().active && ScanHelp.matchesAny(e.getValue().filters, addr, record)) targets.add(e);
            }
        }
        if (targets.isEmpty()) return;
        final ScanResult result = ScanHelp.makeResult(addr, rssi, record);
        if (result == null) return;
        for (final Map.Entry<Integer, LeScanState> e : targets) {
            final int clientIf = e.getKey();
            final LeScanState st = e.getValue();
            final IBluetoothGattCallback callback = cb(clientIf);
            if (callback == null) continue;
            if (st.reportDelay > 0) { st.buffer(result); continue; }
            final boolean first = (st.callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0 && st.firstSight(addr);
            engine.deliver(new Runnable() { public void run() {
                try {
                    if (first) callback.onFoundOrLost(true, result);
                    callback.onScanResult(result);
                } catch (RemoteException ex) { engine.unregisterClientInternal(clientIf); }
            } });
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // connections and attribute access (handle addressing)

    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        return engine.devicesMatching(states);
    }

    @Override public void clientConnect(int clientIf, String address, boolean isDirect, int transport) {
        engine.clientConnectInternal(clientIf, address);
    }

    @Override public void clientDisconnect(int clientIf, String address) {
        engine.clientDisconnectInternal(clientIf, address);
    }

    @Override public void refreshDevice(int clientIf, String address) { }

    @Override public void discoverServices(int clientIf, String address) { engine.discoverInternal(clientIf, address); }

    @Override public void readCharacteristic(int clientIf, String address, int handle, int authReq) {
        engine.readInternal(clientIf, address, handle);
    }

    @Override public void writeCharacteristic(int clientIf, String address, int handle, int writeType, int authReq, byte[] value) {
        engine.writeInternal(clientIf, address, handle, writeType, value);
    }

    @Override public void readDescriptor(int clientIf, String address, int handle, int authReq) {
        engine.readInternal(clientIf, address, handle);
    }

    @Override public void writeDescriptor(int clientIf, String address, int handle, int writeType, int authReq, byte[] value) {
        engine.writeInternal(clientIf, address, handle, writeType, value);
    }

    @Override public void registerForNotification(int clientIf, String address, int handle, boolean enable) {
        engine.notifyInternal(clientIf, address, handle, enable);
    }

    @Override public void beginReliableWrite(int clientIf, String address) { }

    @Override public void endReliableWrite(int clientIf, String address, boolean execute) {
        engine.endReliableWriteInternal(clientIf, address, execute);
    }

    @Override public void readRemoteRssi(final int clientIf, final String address) {
        final IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        // the host protocol has no RSSI read; answer with an error instead of inventing a number
        engine.deliver(new Runnable() { public void run() {
            try { callback.onReadRemoteRssi(address.toUpperCase(), 0, GattEngine.GATT_ERROR); }
            catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
        } });
    }

    @Override public void configureMTU(int clientIf, String address, int mtu) { engine.mtuInternal(clientIf, address, mtu); }

    @Override public void connectionParameterUpdate(int clientIf, String address, int connectionPriority) {
        // no wire op and no result callback in this generation: the request is best-effort by design
    }

    @Override public void disconnectAll() { engine.disconnectAllInternal(); }

    @Override public void unregAll() {
        List<Integer> ids;
        synchronized (cbs) { ids = new ArrayList<Integer>(cbs.keySet()); }
        for (int id : ids) engine.unregisterClientInternal(id);
    }

    @Override public int numHwTrackFiltersAvailable() { return 0; }

    // ---------------------------------------------------------------------------------------------------------
    // GattEngine delivery, formatted for the 24+ callback surface (whole tree, handle addressing)

    @Override public void deliverConnState(int clientIf, String addr, int status, boolean connected) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        try { callback.onClientConnectionState(status, clientIf, connected, addr); }
        catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    @Override public void deliverServices(int clientIf, String addr, int status, List<GattEngine.Attr> attrs) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        List<BluetoothGattService> tree = status == 0 ? GattEngine.buildTree(attrs) : new ArrayList<BluetoothGattService>();
        try { callback.onSearchComplete(addr, tree, status); }
        catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    @Override public void deliverAttr(int clientIf, String addr, GattEngine.Attr attr, GattEngine.Attr chr, GattEngine.Attr svc, int status, byte[] value, boolean isRead) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        try {
            if (attr.kind == GattEngine.KIND_DESC) {
                if (isRead) callback.onDescriptorRead(addr, status, attr.id, value);
                else callback.onDescriptorWrite(addr, status, attr.id);
            } else if (isRead) {
                callback.onCharacteristicRead(addr, status, attr.id, value);
            } else {
                callback.onCharacteristicWrite(addr, status, attr.id);
            }
        } catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    @Override public void deliverNotify(int clientIf, String addr, GattEngine.Attr chr, GattEngine.Attr svc, byte[] value) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        try { callback.onNotify(addr, chr.id, value); }
        catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    @Override public void deliverMtu(int clientIf, String addr, int mtu, int status) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        try { callback.onConfigureMTU(addr, mtu, status); }
        catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    @Override public void deliverExecuteWrite(int clientIf, String addr, int status) {
        IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        try { callback.onExecuteWrite(addr, status); }
        catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
    }

    // ---------------------------------------------------------------------------------------------------------
    // multi-advertising and the GATT server: not bridged, answered with an error at once

    @Override public void startMultiAdvertising(final int appIf, AdvertiseData advertiseData,
                                                AdvertiseData scanResponse, AdvertiseSettings settings) {
        final IBluetoothGattCallback callback = cb(appIf);
        if (callback == null) return;
        synchronized (advSettings) { advSettings.put(appIf, settings); }
        engine.deliver(new Runnable() { public void run() {
            try { callback.onMultiAdvertiseCallback(GattEngine.GATT_ERROR, true, settings); }
            catch (RemoteException e) { engine.unregisterClientInternal(appIf); }
        } });
    }

    @Override public void stopMultiAdvertising(final int appIf) {
        final IBluetoothGattCallback callback = cb(appIf);
        if (callback == null) return;
        final AdvertiseSettings settings;
        synchronized (advSettings) { settings = advSettings.remove(appIf); }
        engine.deliver(new Runnable() { public void run() {
            try { callback.onMultiAdvertiseCallback(GattEngine.GATT_ERROR, false, settings); }
            catch (RemoteException e) { engine.unregisterClientInternal(appIf); }
        } });
    }

    @Override public void registerServer(ParcelUuid appId, final IBluetoothGattServerCallback callback) {
        if (callback == null) return;
        final int serverIf;
        synchronized (this) { serverIf = nextServer++; }
        // the bridge has no GATT server: refuse through the registration callback instead of hanging the framework
        engine.deliver(new Runnable() { public void run() {
            try { callback.onServerRegistered(GattEngine.GATT_ERROR, serverIf); } catch (RemoteException ignored) { }
        } });
    }

    @Override public void unregisterServer(int serverIf) { }
    @Override public void serverConnect(int servertIf, String address, boolean isDirect, int transport) { }
    @Override public void serverDisconnect(int serverIf, String address) { }
    @Override public void beginServiceDeclaration(int serverIf, int srvcType, int srvcInstanceId, int minHandles,
                                                  ParcelUuid srvcId, boolean advertisePreferred) { }
    @Override public void addIncludedService(int serverIf, int srvcType, int srvcInstanceId, ParcelUuid srvcId) { }
    @Override public void addCharacteristic(int serverIf, ParcelUuid charId, int properties, int permissions) { }
    @Override public void addDescriptor(int serverIf, ParcelUuid descId, int permissions) { }
    @Override public void endServiceDeclaration(int serverIf) { }
    @Override public void removeService(int serverIf, int srvcType, int srvcInstanceId, ParcelUuid srvcId) { }
    @Override public void clearServices(int serverIf) { }
    @Override public void sendResponse(int serverIf, String address, int requestId, int status, int offset, byte[] value) { }
    @Override public void sendNotification(int serverIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                           int charInstanceId, ParcelUuid charId, boolean confirm, byte[] value) { }
}
