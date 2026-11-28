package app.aemu.core

/**
 * The guest TCP timing probe (app/guest-probe): a one-shot user app that timestamps each step of a
 * detoured connect on the guest's own clock, in the three shapes the host VPN hub's log is lined
 * up against. It is delivered the way the agent's inbox app is — TreeFixer plants the delivered
 * copy in the tree, GuestVm installs it at boot complete behind the content-hash gate — so a guest
 * carries it by default and nobody has to install it by hand (a guest has no file manager to do
 * that with). It stays a plain user app: nothing starts it but the person measuring, and the
 * guest's package manager can uninstall it like any other app. Below the probe's own API floor
 * the install is skipped silently.
 */
object ProbeSupport {
    const val PACKAGE = "app.aemu.probe"
    /** the delivered copy in the tree (TreeFixer), installed at boot complete */
    const val APK_COPY = "data/adb/aemu-tcp-probe.apk"
    /** the probe manifest's minSdkVersion: guests below it never see an install attempt */
    const val MIN_API = 19
}
