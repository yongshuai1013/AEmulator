package app.aemu.stub;

import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothProfileServiceConnection;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.os.IBinder;

/** "bluetooth_manager" for API 24-27: enable/disable carry the calling package name from API 24 on. */
final class BtManager extends IBluetoothManager.Stub {
    private final BtCore core;
    private final BtAdapter adapter;
    private final BtGatt gatt;

    BtManager(BtCore core, BtAdapter adapter, BtGatt gatt) {
        this.core = core;
        this.adapter = adapter;
        this.gatt = gatt;
    }

    @Override public IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        core.registerAdapter(callback);
        return adapter;
    }
    @Override public void unregisterAdapter(IBluetoothManagerCallback callback) { core.unregisterAdapter(callback); }
    @Override public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) { core.registerStateChange(callback); }
    @Override public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) { core.unregisterStateChange(callback); }
    @Override public boolean isEnabled() { return core.isEnabled(); }
    @Override public boolean enable(String packageName) { return core.enable(); }
    @Override public boolean enableNoAutoConnect(String packageName) { return core.enableNoAutoConnect(); }
    @Override public boolean disable(String packageName, boolean persist) { return core.disable(); }
    @Override public int getState() { return core.state(); }
    @Override public IBluetoothGatt getBluetoothGatt() { return gatt; }
    @Override public boolean bindBluetoothProfileService(int profile, IBluetoothProfileServiceConnection proxy) {
        return false;   // no profile services live in the guest; the bridge carries data channels, not stack profiles
    }
    @Override public void unbindBluetoothProfileService(int profile, IBluetoothProfileServiceConnection proxy) { }
    @Override public String getAddress() { return core.getAddress(); }
    @Override public String getName() { return core.getName(); }
    @Override public boolean isBleScanAlwaysAvailable() { return core.isBleScanAlwaysAvailable(); }
    @Override public int updateBleAppCount(IBinder b, boolean enable, String packageName) {
        return core.updateBleAppCount(b, enable);
    }
    @Override public boolean isBleAppPresent() { return core.isBleAppPresent(); }
}
