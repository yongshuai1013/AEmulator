/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * Classic su root for one VM: a su binary and the Superuser manager app, both built from the
 * koush/Superuser sources (pinned in app/guest-superuser/build.sh) and carried in the host APK's
 * assets. TreeFixer plants them while the VM's superuser switch is on; what makes the planted
 * su's setuid bit mean anything inside the guest is the supervisor's exec elevation point
 * (native/guest64/23/run64.c, exec_ids). The pairing with the manager is upstream's own: the
 * package name below and its su.sqlite database are what the su binary looks for.
 */
internal object SuperuserSupport {
    const val SU_ASSET = "engines/kk/aemu-su"
    const val APP_ASSET = "engines/kk/aemu-superuser.apk"
    const val SU_PATH = "system/xbin/su"
    const val APP_PATH = "system/app/Superuser/Superuser.apk"
    const val PACKAGE = "com.koushikdutta.superuser"

    /** First release: the native engine, Android 6.0 (the only supervisor with the exec elevation point), arm64 firmware. */
    fun offered(img: GuestImage): Boolean =
        img.effective().engine == Engine.N64 && img.api == 23 && img.abi == "arm64-v8a"
}
