/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * The Magisk substitute: our own daemon (app/guest-substitute/substitute.c) carrying the
 * official Magisk v30.7 manager app's interface — the same socket protocol, the same
 * magisk.db schema, the same stage and module-flag semantics — built and packaged by
 * app/guest-substitute/build.sh, which takes the official app APK, swaps its payload
 * slots for the substitute (the manager's own code is untouched) and re-signs it with
 * this project's long-term key. The result rides inside the host APK as an asset.
 *
 * Placement is split the way the payload itself is shaped: TreeFixer deploys the
 * /data/adb side out of the embedded app before boot (binaries, scripts, the app copy),
 * and GuestVm gives the daemon its own boot seats at runtime under the host-log prefix
 * "magisk-sub:" — the payload tmp under [TMP], the /system name links, the stage calls.
 * The host never execs the daemon: the first root client wakes it (the client's
 * create=true), exactly the official contract.
 *
 * What the substitute deliberately is not: there is no mount or namespace work behind
 * it (module system/ trees are recorded, not overlaid), no sepolicy is compiled or
 * loaded (magiskpolicy answers with an honest refusal), resetprop does not exist, and
 * the manager's Zygisk switch writes its database row with no effect — the settings
 * page says so next to this switch. Supported guests: the native engine, api 23
 * (Android 6.0), arm64 — the generation this batch targets; elsewhere the switch is
 * shown with a not-supported label instead of pretending.
 */
internal object SubstituteSupport {
    const val APK_ASSET = "engines/kk/aemu-magisk-sub.apk"
    const val APK_PATH = "data/adb/aemu-magisk-sub.apk"
    const val PACKAGE = "com.topjohnwu.magisk"

    /** The payload tmp inside the guest (the daemon's magisk_tmp()). */
    const val TMP = "/debug_ramdisk"

    /** The deployed payload directory inside the guest (the daemon's DATABIN). */
    const val BIN = "/data/adb/magisk"

    /** Guest /system names the boot seat links into the payload tmp. */
    val SYSTEM_LINKS = listOf("su", "magisk", "resetprop")

    /** APK entry -> deployed file name under [BIN]. Binaries first, then the scripts. */
    val PAYLOAD: List<Pair<String, String>> = listOf(
        "lib/arm64-v8a/libmagisk.so" to "magisk",
        "lib/arm64-v8a/libmagisk.so" to "magisk64",
        "lib/armeabi-v7a/libmagisk.so" to "magisk32",
        "lib/arm64-v8a/libmagiskpolicy.so" to "magiskpolicy",
        "lib/arm64-v8a/libmagiskinit.so" to "magiskinit",
        "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
        "lib/arm64-v8a/libbusybox.so" to "busybox",
        "lib/arm64-v8a/libinit-ld.so" to "init-ld",
        "assets/util_functions.sh" to "util_functions.sh",
        "assets/app_functions.sh" to "app_functions.sh",
        "assets/module_installer.sh" to "module_installer.sh",
        "assets/boot_patch.sh" to "boot_patch.sh",
        "assets/addon.d.sh" to "addon.d.sh",
        "assets/uninstaller.sh" to "uninstaller.sh",
    )

    /** Guest directories the deployment owns besides [BIN] itself. */
    val DATA_DIRS = listOf(
        "data/adb/modules",
        "data/adb/modules_update",
        "data/adb/post-fs-data.d",
        "data/adb/service.d",
    )

    fun supported(img: GuestImage): Boolean =
        img.effective().engine == Engine.N64 && img.api == 23 && img.abi == "arm64-v8a"

    /** The switch is on for this VM and this guest can run the substitute. */
    fun on(img: GuestImage): Boolean = supported(img) && img.settings.magiskSub
}
