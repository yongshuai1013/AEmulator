/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * The Zygisk spike (route B of the feasibility desk in aemu-verify-probe):
 * our own bridge library (app/guest-zygisk/bridge.c) that ART loads inside
 * zygote through the native-bridge property, hooks
 * nativeForkAndSpecialize there, and drives modules through the official
 * Zygisk API v4 table. This batch only proves the landing: the bridge, one
 * test module that logs its pre/post calls, and the placement below. It is
 * not wired to the substitute's module list or switches - module
 * management stays the substitute's surface, and the manager app's Zygisk
 * toggle still has no effect.
 *
 * Placement is split by what each piece needs: TreeFixer plants the
 * bridge as /system/lib64/libazygisk.so, the module manifest as
 * /system/etc/aemu-zygisk-manifest and the test module under
 * /data/adb/modules before boot, and GuestVm points
 * ro.dalvik.vm.native.bridge at the bridge in the per-boot property
 * overrides, so the property is set before zygote is born.
 *
 * Honest edges, also stated next to the switch: there is no companion
 * (connectCompanion answers -1), the modules' PLT-hook and JNI-hook API
 * entry points report unsupported, 32-bit processes get no bridge, and
 * system_server's fork path is not hooked. Supported guests: the native
 * engine, api 23 (Android 6.0), arm64 - the generation this spike
 * targets; elsewhere the switch is shown with a not-supported label.
 */
internal object ZygiskSpikeSupport {
    const val BRIDGE_ASSET = "engines/kk/aemu-azygisk.so"
    const val BRIDGE_PATH = "system/lib64/libazygisk.so"
    const val BRIDGE_PROP = "ro.dalvik.vm.native.bridge"
    const val BRIDGE_SONAME = "libazygisk.so"

    const val MANIFEST_PATH = "system/etc/aemu-zygisk-manifest"

    const val MODULE_ASSET = "engines/kk/aemu-zygisk-testmod.so"
    const val MODULE_ID = "aemu-spike"
    const val MODULE_DIR = "data/adb/modules/$MODULE_ID"
    const val MODULE_PATH = "$MODULE_DIR/zygisk/arm64-v8a.so"

    /** The manifest the bridge reads in the forked child: one module dir per line. */
    const val MANIFEST_TEXT = "/data/adb/modules/$MODULE_ID\n"

    fun supported(img: GuestImage): Boolean =
        img.effective().engine == Engine.N64 && img.api == 23 && img.abi == "arm64-v8a"

    /** The switch is on for this VM and this guest can run the spike. */
    fun on(img: GuestImage): Boolean = supported(img) && img.settings.zygiskSpike
}
