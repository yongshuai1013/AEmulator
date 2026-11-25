package app.aemu.stub;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/** Hidden-API plumbing the service needs: the activity manager (broadcasts), the service manager, device parcels. */
final class BtUtil {
    static final String PERM_BLUETOOTH = "android.permission.BLUETOOTH";
    private static Object am;
    private static Method broadcast;
    private static int broadcastArgs;
    private static boolean amFailed;

    private BtUtil() { }

    static void addService(String name, IBinder binder) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        sm.getMethod("addService", String.class, IBinder.class).invoke(null, name, binder);
    }

    /**
     * BluetoothDevice's constructor calls BluetoothDevice.getService(), which would bind back to this very service.
     * Hand it our IBluetooth first so building devices (for replies and for incoming arguments) never loops through binder.
     */
    static void primeDeviceService(IBinder core) {
        try {
            java.lang.reflect.Field f = BluetoothDevice.class.getDeclaredField("sService");
            f.setAccessible(true);
            if (f.get(null) == null) f.set(null, android.bluetooth.IBluetooth.Stub.asInterface(core));
        } catch (Throwable e) {
            BtLog.w("cannot prime BluetoothDevice.sService", e);
        }
    }

    static BluetoothDevice device(String address) {
        Parcel p = Parcel.obtain();
        try {
            p.writeString(address);
            p.setDataPosition(0);
            return BluetoothDevice.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    static BluetoothClass deviceClass(int cls) {
        Parcel p = Parcel.obtain();
        try {
            p.writeInt(cls);
            p.setDataPosition(0);
            return BluetoothClass.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    static String address(BluetoothDevice d) { return d.getAddress().toUpperCase(); }

    /**
     * The activity manager moved between API levels: ActivityManagerNative.getDefault() up to 7.x,
     * ActivityManager.getService() from 8.0 (the Native class is gone there). broadcastIntent also grew a
     * 13th parameter (requiredPermissions as String[], plus a Bundle options slot) in 6.0. Resolve both at
     * run time so one code path serves 23-27.
     */
    private static synchronized boolean resolveAm() {
        if (am != null) return true;
        if (amFailed) return false;
        try {
            Object mgr = null;
            try {
                mgr = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            } catch (Throwable ignored) { }
            if (mgr == null) {
                mgr = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            }
            if (mgr == null) return false;    // activity manager not up yet; the caller may retry
            Method m13 = null, m12 = null;
            for (Method m : mgr.getClass().getMethods()) {
                if (!m.getName().equals("broadcastIntent")) continue;
                if (m.getParameterTypes().length == 13) m13 = m;
                else if (m.getParameterTypes().length == 12) m12 = m;
            }
            if (m13 != null) { broadcast = m13; broadcastArgs = 13; }
            else if (m12 != null) { broadcast = m12; broadcastArgs = 12; }
            else { amFailed = true; BtLog.i("no IActivityManager.broadcastIntent(12/13 args); broadcasts disabled"); return false; }
            am = mgr;
            return true;
        } catch (Throwable e) {
            BtLog.w("cannot reach the activity manager", e);
            return false;
        }
    }

    /** Protected broadcasts need a system/root caller; the service runs as the guest's root, like `am broadcast`. */
    static void broadcast(Intent intent, String permission) {
        if (!resolveAm()) return;
        try {
            if (broadcastArgs == 13) {
                // (caller, intent, resolvedType, resultTo, resultCode, resultData, resultExtras,
                //  requiredPermissions, appOp, options, serialized, sticky, userId)
                broadcast.invoke(am, null, intent, null, null, 0, null, null,
                        new String[]{permission}, -1, null, false, false, -1);
            } else {
                // (caller, intent, resolvedType, resultTo, resultCode, resultData, map, requiredPermission,
                //  appOp, serialized, sticky, userId)
                broadcast.invoke(am, null, intent, null, null, 0, null, null, permission, -1, false, false, -1);
            }
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            BtLog.w("broadcast " + intent.getAction() + " failed", c);
        }
    }
}
