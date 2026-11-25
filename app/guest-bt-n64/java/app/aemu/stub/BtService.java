package app.aemu.stub;

import android.os.Looper;

/**
 * Entry point: app_process ... app.aemu.stub.BtService bluetooth_manager
 * Registers the guest Bluetooth manager and keeps a connection to the host bridge (/dev/aemu_bluetooth).
 * This build serves the N64 generations (API 23-27); the per-API-group BtManager/BtGatt classes in this jar
 * were compiled against that group's AIDL. API 19 keeps guest-bt's aemu-bt.jar, other levels keep BtStub.
 */
public final class BtService {
    private BtService() { }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "bluetooth_manager";
        Looper.prepare();
        BtCore core = new BtCore();
        BtGatt gatt = new BtGatt(core);
        core.attachGatt(gatt);
        BtAdapter adapter = new BtAdapter(core);
        core.setAdapterBinder(adapter);
        BtUtil.primeDeviceService(adapter);
        BtUtil.addService(name, new BtManager(core, adapter, gatt));
        BtLog.i("service " + name + " registered (host bridge client, API 23-27)");
        core.start();
        Looper.loop();
    }
}
