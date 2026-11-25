/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * Classic su root for one VM: a su binary and the Superuser manager app, both built from the
 * koush/Superuser sources (pinned in app/guest-superuser/build.sh) and carried in the host APK's
 * assets. TreeFixer plants them while the VM's superuser switch is on; what makes the planted
 * su's setuid bit mean anything inside the guest is the supervisor's exec elevation point
 * (native/guest64/<level>/run64.c, exec_ids). The pairing with the manager is upstream's own: the
 * package name below and its su.sqlite database are what the su binary looks for.
 */
internal object SuperuserSupport {
    const val SU_ASSET = "engines/kk/aemu-su"
    const val APP_ASSET = "engines/kk/aemu-superuser.apk"
    const val SU_PATH = "system/xbin/su"
    const val APP_PATH = "system/app/Superuser/Superuser.apk"
    const val PACKAGE = "com.koushikdutta.superuser"

    /** Supervisors (Kernel64 tags) carrying the exec elevation point: every native-engine level. */
    private val ELEVATION_TAGS = setOf(
        "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "33", "34", "35", "36", "37",
    )

    /**
     * The native engine, an arm64 firmware, and the supervisor that will actually run it carries
     * the exec elevation point. The tag follows [Kernel64.forApi] rather than the raw api number:
     * an api-32 firmware runs the 31 supervisor, and it is that binary's elevation point that
     * counts.
     */
    fun supported(img: GuestImage): Boolean =
        img.effective().engine == Engine.N64 && img.abi == "arm64-v8a" &&
            Kernel64.forApi(img.api).tag in ELEVATION_TAGS
}
