package app.aemu.stub;

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.PeriodicAdvertisingParameters;
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
 * IBluetoothGatt for API 26-27 (8.0-8.1), compiled against the 8.1 AIDL set. Scanning is its own
 * registry here (IScannerCallback), separate from GATT clients; attributes are handle-addressed and
 * discovery delivers the whole service tree. The jar also carries the 26-shaped clientConnect overload:
 * a 26 framework's Stub dispatches to it, a 27 framework's to the 7-argument form. Advertising sets,
 * periodic sync and the GATT server are not bridged and answer with an error callback at once.
 */
final class BtGatt extends IBluetoothGatt.Stub implements GattSide, GattEngine.Delivery {
    private final BtCore core;
    private final GattEngine engine;
    private static final int ADVERTISE_FAILED_INTERNAL_ERROR = 4;

    private final Map<Integer, IBluetoothGattCallback> cbs = new HashMap<Integer, IBluetoothGattCallback>();
    private final Map<Integer, IScannerCallback> scannerCbs = new HashMap<Integer, IScannerCallback>();
    private final Map<Integer, LeScanState> scans = new HashMap<Integer, LeScanState>();
    private int nextScanner = 1;
    private int nextServer = 1;

    BtGatt(BtCore core) { this.core = core; this.engine = new GattEngine(core, this); }

    @Override public void hostLost() { engine.hostLost(); }
    @Override public int connectionState(String addr) { return engine.connectionState(addr); }
    @Override public void onHostEvent(int op, BtHost.Rd r) { engine.onHostEvent(op, r); }

    private IBluetoothGattCallback cb(int clientIf) {
        synchronized (cbs) { return cbs.get(clientIf); }
    }

    // ---------------------------------------------------------------------------------------------------------
    // scanner registry (26+: scanning no longer hangs off a GATT client)

    @Override public void registerScanner(final IScannerCallback callback, WorkSource workSource) {
        if (callback == null) return;
        final int id;
        synchronized (scannerCbs) {
            id = nextScanner++;
            scannerCbs.put(id, callback);
            scans.put(id, new LeScanState());
        }
        engine.deliver(new Runnable() { public void run() {
            try { callback.onScannerRegistered(0, id); } catch (RemoteException ignored) { }
        } });
    }

    @Override public void unregisterScanner(int scannerId) {
        LeScanState st;
        synchronized (scannerCbs) {
            scannerCbs.remove(scannerId);
            st = scans.remove(scannerId);
        }
        if (st != null && st.active) core.leClientDelta(-1);
    }

    @Override public void startScan(int scannerId, ScanSettings settings, List<ScanFilter> filters,
                                    List scanStorages, String callingPackage) {
        LeScanState st;
        synchronized (scannerCbs) { st = scans.get(scannerId); }
        if (st == null || !core.isOn() || !core.has(BtCore.CAP_LE_SCAN)) return;
        boolean first = !st.active;
        st.configure(settings, filters);
        st.active = true;
        if (first) core.leClientDelta(+1);
    }

    @Override public void startScanForIntent(PendingIntent intent, ScanSettings settings,
                                             List<ScanFilter> filters, String callingPackage) {
        // PendingIntent-based scanning has no result channel back to this service; not offered
        BtLog.i("startScanForIntent: not supported by the bridge");
    }

    @Override public void stopScanForIntent(PendingIntent intent, String callingPackage) { }

    @Override public void stopScan(int scannerId) {
        LeScanState st;
        synchronized (scannerCbs) { st = scans.get(scannerId); }
        if (st == null || !st.active) return;
        st.active = false;
        core.leClientDelta(-1);
    }

    @Override public void flushPendingBatchResults(int scannerId) {
        LeScanState st;
        final IScannerCallback callback;
        synchronized (scannerCbs) {
            st = scans.get(scannerId);
            callback = scannerCbs.get(scannerId);
        }
        if (st == null || callback == null) return;
        final List<ScanResult> out = st.drainBatch();
        if (out.isEmpty()) return;
        engine.deliver(new Runnable() { public void run() {
            try { callback.onBatchScanResults(out); } catch (RemoteException ignored) { }
        } });
    }

    @Override public void leResult(String addr, int rssi, byte[] record) {
        List<Map.Entry<Integer, LeScanState>> targets = new ArrayList<Map.Entry<Integer, LeScanState>>();
        synchronized (scannerCbs) {
            for (Map.Entry<Integer, LeScanState> e : scans.entrySet()) {
                if (e.getValue().active && ScanHelp.matchesAny(e.getValue().filters, addr, record)) targets.add(e);
            }
        }
        if (targets.isEmpty()) return;
        final ScanResult result = ScanHelp.makeResult(addr, rssi, record);
        if (result == null) return;
        for (final Map.Entry<Integer, LeScanState> e : targets) {
            final int scannerId = e.getKey();
            final LeScanState st = e.getValue();
            final IScannerCallback callback;
            synchronized (scannerCbs) { callback = scannerCbs.get(scannerId); }
            if (callback == null) continue;
            if (st.reportDelay > 0) { st.buffer(result); continue; }
            final boolean first = (st.callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0 && st.firstSight(addr);
            engine.deliver(new Runnable() { public void run() {
                try {
                    if (first) callback.onFoundOrLost(true, result);
                    callback.onScanResult(result);
                } catch (RemoteException ignored) { }
            } });
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // GATT clients

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
    }

    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        return engine.devicesMatching(states);
    }

    /** API 27 form. */
    @Override public void clientConnect(int clientIf, String address, boolean isDirect, int transport,
                                        boolean opportunistic, int phy) {
        engine.clientConnectInternal(clientIf, address);
    }

    /** API 26 form: a 26 framework's Stub dispatches here; not part of the 27 interface, hence no @Override. */
    public void clientConnect(int clientIf, String address, boolean isDirect, int transport, int phy) {
        engine.clientConnectInternal(clientIf, address);
    }

    @Override public void clientDisconnect(int clientIf, String address) {
        engine.clientDisconnectInternal(clientIf, address);
    }

    @Override public void clientSetPreferredPhy(final int clientIf, final String address, int txPhy, int rxPhy, int phyOptions) {
        final IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        // the wire protocol has no PHY control: report the failure, do not fake a 1M answer
        engine.deliver(new Runnable() { public void run() {
            try { callback.onPhyUpdate(address.toUpperCase(), 0, 0, GattEngine.GATT_ERROR); }
            catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
        } });
    }

    @Override public void clientReadPhy(final int clientIf, final String address) {
        final IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        engine.deliver(new Runnable() { public void run() {
            try { callback.onPhyRead(address.toUpperCase(), 0, 0, GattEngine.GATT_ERROR); }
            catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
        } });
    }

    @Override public void refreshDevice(int clientIf, String address) { }

    @Override public void discoverServices(int clientIf, String address) { engine.discoverInternal(clientIf, address); }

    @Override public void discoverServiceByUuid(int clientIf, String address, ParcelUuid uuid) {
        // the host discovery returns the whole tree anyway; the framework picks out the service it wanted
        engine.discoverInternal(clientIf, address);
    }

    @Override public void readCharacteristic(int clientIf, String address, int handle, int authReq) {
        engine.readInternal(clientIf, address, handle);
    }

    @Override public void readUsingCharacteristicUuid(final int clientIf, final String address, ParcelUuid uuid,
                                                      int startHandle, int endHandle, int authReq) {
        GattEngine.Attr a = uuid == null ? null : engine.findCharByUuid(clientIf, address, uuid.getUuid(), startHandle, endHandle);
        if (a == null) {
            final IBluetoothGattCallback callback = cb(clientIf);
            if (callback == null) return;
            engine.deliver(new Runnable() { public void run() {
                try { callback.onCharacteristicRead(address.toUpperCase(), GattEngine.GATT_ERROR, startHandle, new byte[0]); }
                catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
            } });
            return;
        }
        engine.readInternal(clientIf, address, a.id);
    }

    @Override public void writeCharacteristic(int clientIf, String address, int handle, int writeType, int authReq, byte[] value) {
        engine.writeInternal(clientIf, address, handle, writeType, value);
    }

    @Override public void readDescriptor(int clientIf, String address, int handle, int authReq) {
        engine.readInternal(clientIf, address, handle);
    }

    @Override public void writeDescriptor(int clientIf, String address, int handle, int authReq, byte[] value) {
        // the 26+ form has no writeType: a descriptor write is always with response
        engine.writeInternal(clientIf, address, handle, 2, value);
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

    @Override public void connectionParameterUpdate(final int clientIf, final String address, int connectionPriority) {
        final IBluetoothGattCallback callback = cb(clientIf);
        if (callback == null) return;
        // no wire op for this; this generation has a result callback, so say it failed there
        engine.deliver(new Runnable() { public void run() {
            try { callback.onConnectionUpdated(address.toUpperCase(), 0, 0, 0, GattEngine.GATT_ERROR); }
            catch (RemoteException e) { engine.unregisterClientInternal(clientIf); }
        } });
    }

    @Override public void disconnectAll() { engine.disconnectAllInternal(); }

    @Override public void unregAll() {
        List<Integer> ids;
        synchronized (cbs) { ids = new ArrayList<Integer>(cbs.keySet()); }
        for (int id : ids) engine.unregisterClientInternal(id);
    }

    @Override public int numHwTrackFiltersAvailable() { return 0; }

    // ---------------------------------------------------------------------------------------------------------
    // GattEngine delivery, formatted for the 26+ callback surface (whole tree, handle addressing)

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
    // advertising sets, periodic sync and the GATT server: not bridged, answered with an error at once

    @Override public void startAdvertisingSet(AdvertisingSetParameters parameters, AdvertiseData advertiseData,
                                              AdvertiseData scanResponse, PeriodicAdvertisingParameters periodicParameters,
                                              AdvertiseData periodicData, int duration, int maxExtAdvEvents,
                                              final IAdvertisingSetCallback callback) {
        if (callback == null) return;
        engine.deliver(new Runnable() { public void run() {
            try { callback.onAdvertisingSetStarted(0, 0, ADVERTISE_FAILED_INTERNAL_ERROR); }
            catch (RemoteException ignored) { }
        } });
    }

    @Override public void stopAdvertisingSet(IAdvertisingSetCallback callback) { }
    @Override public void getOwnAddress(int advertiserId) { }
    @Override public void enableAdvertisingSet(int advertiserId, boolean enable, int duration, int maxExtAdvEvents) { }
    @Override public void setAdvertisingData(int advertiserId, AdvertiseData data) { }
    @Override public void setScanResponseData(int advertiserId, AdvertiseData data) { }
    @Override public void setAdvertisingParameters(int advertiserId, AdvertisingSetParameters parameters) { }
    @Override public void setPeriodicAdvertisingParameters(int advertiserId, PeriodicAdvertisingParameters parameters) { }
    @Override public void setPeriodicAdvertisingData(int advertiserId, AdvertiseData data) { }
    @Override public void setPeriodicAdvertisingEnable(int advertiserId, boolean enable) { }

    @Override public void registerSync(ScanResult scanResult, int skip, int timeout,
                                       final IPeriodicAdvertisingCallback callback) {
        if (callback == null || scanResult == null) return;
        final BluetoothDevice device = scanResult.getDevice();
        engine.deliver(new Runnable() { public void run() {
            try { callback.onSyncEstablished(0, device, 0, 0, 0, GattEngine.GATT_ERROR); }
            catch (RemoteException ignored) { }
        } });
    }

    @Override public void unregisterSync(IPeriodicAdvertisingCallback callback) { }

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
    @Override public void serverSetPreferredPhy(int clientIf, String address, int txPhy, int rxPhy, int phyOptions) { }
    @Override public void serverReadPhy(int clientIf, String address) { }
    @Override public void addService(int serverIf, BluetoothGattService service) { }
    @Override public void removeService(int serverIf, int handle) { }
    @Override public void clearServices(int serverIf) { }
    @Override public void sendResponse(int serverIf, String address, int requestId, int status, int offset, byte[] value) { }
    @Override public void sendNotification(int serverIf, String address, int handle, boolean confirm, byte[] value) { }
}
