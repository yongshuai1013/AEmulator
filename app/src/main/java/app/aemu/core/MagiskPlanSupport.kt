/* AEmulator addition, 2026-11-28. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * Magisk, the genuine article, in plan guests (magisk-plan): the official Magisk
 * v30.7 manager app with its five arm64 payload slots replaced by this project's
 * own build of the unmodified v30.7 native sources (app/guest-magisk-plan/build.sh
 * repacks and re-signs it with the project's long-term key), embedded in the host
 * APK as an asset. Nothing in the daemon is edited for this environment — the
 * adaptation runs the other way: the host wakes the daemon at the plan boot's own
 * milestones (GuestVm, under the host-log prefix "magisk-plan:"), and the two
 * kernel surfaces it asks for are answered by machinery the supervisor already
 * has — its module mounts go into the supervisor's mount table (the table is
 * armed before the boot) and its sepolicy loads land in the planted selinuxfs
 * (see GuestVm.plantSelinuxfsPlan). TreeFixer deploys the /data/adb side out of
 * the embedded app before boot, exactly the substitute's delivery shape, so the
 * manager app and the planted payload are the same bytes by construction.
 *
 * Placement is split like the substitute's: TreeFixer deploys, GuestVm seats.
 * The host never execs the daemon: the first root client wakes it (the client's
 * create=true), exactly the official contract.
 *
 * Phase-1 scope, stated where the switch is (the settings page): Android 8.1
 * (api 27) arm64 guests on the native engine — the generation whose supervisor
 * carries the secon ledger and the mount table without a Magisk exception.
 * Zygisk does not take effect in this mode (the daemon's own switch writes its
 * setting; there is no injection into the supervised zygote) and the denylist
 * has no per-app view (the mount table is one table for the whole guest).
 * Mutually exclusive with the Magisk substitute and with the partition seat:
 * one root mode per VM.
 */
internal object MagiskPlanSupport {
    const val APK_ASSET = "engines/kk/aemu-magisk-plan.apk"
    const val APK_PATH = "data/adb/aemu-magisk-plan.apk"
    const val PACKAGE = "com.topjohnwu.magisk"

    /** The payload tmp inside the guest (the daemon's magisk_tmp()). */
    const val TMP = "/debug_ramdisk"

    /** The deployed payload directory inside the guest (the daemon's DATABIN). */
    const val BIN = "/data/adb/magisk"

    /** Guest /system names the boot seat links into the payload tmp. */
    val SYSTEM_LINKS = listOf("su", "magisk", "resetprop")

    /** APK entry -> deployed file name under [BIN]. Same slot table as the substitute's:
     *  the layout of the official app does not change, only who built the binaries. */
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
        img.effective().engine == Engine.N64 && img.api == 27 && img.abi == "arm64-v8a"

    /** The switch is on for this VM and this guest can run the plan daemon. */
    fun on(img: GuestImage): Boolean = supported(img) && img.settings.magiskPlan
}
