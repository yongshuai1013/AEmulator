package app.aemu.stub;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.OobData;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.ResultReceiver;

/**
 * IBluetooth for API 26-27 (8.0-8.1), compiled against the 8.1 AIDL set (the 27 superset; the one extra
 * method, getBatteryLevel, is simply never called by a 26 framework). A thin shell over BtCore; calls
 * the bridge cannot answer say so (false / null / 0 / -1), never fake success.
 */
final class BtAdapter extends IBluetooth.Stub {
    private final BtCore core;

    BtAdapter(BtCore core) { this.core = core; }

    @Override public boolean isEnabled() { return core.isEnabled(); }
    @Override public int getState() { return core.state(); }
    @Override public boolean enable() { return core.enable(); }
    @Override public boolean enableNoAutoConnect() { return core.enableNoAutoConnect(); }
    @Override public boolean disable() { return core.disable(); }
    @Override public String getAddress() { return core.getAddress(); }
    @Override public ParcelUuid[] getUuids() { return core.getUuids(); }
    @Override public boolean setName(String name) { return core.setName(name); }
    @Override public String getName() { return core.getName(); }
    @Override public int getScanMode() { return core.getScanMode(); }
    @Override public boolean setScanMode(int mode, int duration) { return core.setScanMode(mode, duration); }
    @Override public int getDiscoverableTimeout() { return core.getDiscoverableTimeout(); }
    @Override public boolean setDiscoverableTimeout(int timeout) { return core.setDiscoverableTimeout(timeout); }
    @Override public boolean startDiscovery() { return core.startDiscovery(); }
    @Override public boolean cancelDiscovery() { return core.cancelDiscovery(); }
    @Override public boolean isDiscovering() { return core.isDiscovering(); }
    @Override public long getDiscoveryEndMillis() { return core.discoveryEndMillis(); }
    @Override public int getAdapterConnectionState() { return core.getAdapterConnectionState(); }
    @Override public int getProfileConnectionState(int profile) { return core.getProfileConnectionState(profile); }
    @Override public BluetoothDevice[] getBondedDevices() { return core.getBondedDevices(); }
    @Override public boolean createBond(BluetoothDevice device, int transport) {
        return device != null && core.createBond(BtUtil.address(device));
    }
    @Override public boolean createBondOutOfBand(BluetoothDevice device, int transport, OobData oobData) {
        return false;   // the host pairing dialog has no out-of-band channel to feed
    }
    @Override public boolean cancelBondProcess(BluetoothDevice device) {
        return device != null && core.cancelBondProcess(BtUtil.address(device));
    }
    @Override public boolean removeBond(BluetoothDevice device) {
        return device != null && core.removeBond(BtUtil.address(device));
    }
    @Override public int getBondState(BluetoothDevice device) {
        return device == null ? BtCore.BOND_NONE : core.getBondState(BtUtil.address(device));
    }
    @Override public boolean isBondingInitiatedLocally(BluetoothDevice device) {
        return device != null && core.bondingInitiatedLocally(BtUtil.address(device));
    }
    @Override public long getSupportedProfiles() { return 0; }   // the bridge hosts no stack profiles
    @Override public int getConnectionState(BluetoothDevice device) {
        return device == null ? BtCore.PROFILE_DISCONNECTED : core.getConnectionState(BtUtil.address(device));
    }
    @Override public String getRemoteName(BluetoothDevice device) {
        return device == null ? null : core.getRemoteName(BtUtil.address(device));
    }
    @Override public int getRemoteType(BluetoothDevice device) {
        return device == null ? 0 : core.getRemoteType(BtUtil.address(device));
    }
    @Override public String getRemoteAlias(BluetoothDevice device) {
        return device == null ? null : core.getRemoteAlias(BtUtil.address(device));
    }
    @Override public boolean setRemoteAlias(BluetoothDevice device, String name) {
        return device != null && core.setRemoteAlias(BtUtil.address(device), name);
    }
    @Override public int getRemoteClass(BluetoothDevice device) {
        return device == null ? 0xFF000000 : core.getRemoteClass(BtUtil.address(device));
    }
    @Override public ParcelUuid[] getRemoteUuids(BluetoothDevice device) { return core.getRemoteUuids(null); }
    @Override public boolean fetchRemoteUuids(BluetoothDevice device) { return false; }
    @Override public boolean sdpSearch(BluetoothDevice device, ParcelUuid uuid) { return false; }
    @Override public int getBatteryLevel(BluetoothDevice device) {
        return -1;   // the wire protocol has no battery field; -1 is the framework's "unknown"
    }
    @Override public boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
        return device != null && core.setPin(BtUtil.address(device), accept, len, pinCode);
    }
    @Override public boolean setPasskey(BluetoothDevice device, boolean accept, int len, byte[] passkey) {
        return device != null && core.setPasskey(BtUtil.address(device), accept, len, passkey);
    }
    @Override public boolean setPairingConfirmation(BluetoothDevice device, boolean accept) {
        return device != null && core.setPairingConfirmation(BtUtil.address(device), accept);
    }
    @Override public int getPhonebookAccessPermission(BluetoothDevice device) {
        return device == null ? BtCore.ACCESS_UNKNOWN : core.getAccessPermission(BtUtil.address(device), BtCore.PERM_PHONEBOOK);
    }
    @Override public boolean setPhonebookAccessPermission(BluetoothDevice device, int value) {
        return device != null && core.setAccessPermission(BtUtil.address(device), BtCore.PERM_PHONEBOOK, value);
    }
    @Override public int getMessageAccessPermission(BluetoothDevice device) {
        return device == null ? BtCore.ACCESS_UNKNOWN : core.getAccessPermission(BtUtil.address(device), BtCore.PERM_MESSAGE);
    }
    @Override public boolean setMessageAccessPermission(BluetoothDevice device, int value) {
        return device != null && core.setAccessPermission(BtUtil.address(device), BtCore.PERM_MESSAGE, value);
    }
    @Override public int getSimAccessPermission(BluetoothDevice device) {
        return device == null ? BtCore.ACCESS_UNKNOWN : core.getAccessPermission(BtUtil.address(device), BtCore.PERM_SIM);
    }
    @Override public boolean setSimAccessPermission(BluetoothDevice device, int value) {
        return device != null && core.setAccessPermission(BtUtil.address(device), BtCore.PERM_SIM, value);
    }
    @Override public void sendConnectionStateChange(BluetoothDevice device, int profile, int state, int prevState) {
        if (device != null) core.sendConnectionStateChange(BtUtil.address(device), profile, state, prevState);
    }
    @Override public void registerCallback(IBluetoothCallback callback) { core.registerCallback(callback); }
    @Override public void unregisterCallback(IBluetoothCallback callback) { core.unregisterCallback(callback); }
    @Override public ParcelFileDescriptor connectSocket(BluetoothDevice device, int type, ParcelUuid uuid, int port, int flag) {
        return device == null ? null : core.connectSocket(BtUtil.address(device), type, uuid, port, flag);
    }
    @Override public ParcelFileDescriptor createSocketChannel(int type, String serviceName, ParcelUuid uuid, int port, int flag) {
        return core.createSocketChannel(type, serviceName, uuid, port, flag);
    }
    @Override public boolean factoryReset() { return core.factoryReset(); }
    @Override public boolean isMultiAdvertisementSupported() { return false; }
    @Override public boolean isOffloadedFilteringSupported() { return false; }
    @Override public boolean isOffloadedScanBatchingSupported() { return false; }
    @Override public boolean isActivityAndEnergyReportingSupported() { return false; }
    @Override public boolean isLe2MPhySupported() { return false; }
    @Override public boolean isLeCodedPhySupported() { return false; }
    @Override public boolean isLeExtendedAdvertisingSupported() { return false; }
    @Override public boolean isLePeriodicAdvertisingSupported() { return false; }
    @Override public int getLeMaximumAdvertisingDataLength() { return 0; }
    @Override public BluetoothActivityEnergyInfo reportActivityInfo() { return null; }
    @Override public void requestActivityInfo(ResultReceiver result) {
        // no energy counters exist on the bridge; leave the request unanswered rather than fabricate numbers
    }
    @Override public void onLeServiceUp() { }
    @Override public void onBrEdrDown() { }
}
