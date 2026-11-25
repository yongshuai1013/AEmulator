/* AEmulator addition, 2026-11-25. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * A terminal inside the guest: jackpal/Android-Terminal-Emulator, built from pinned
 * upstream sources (app/guest-terminal/build.sh) and carried in the host APK's assets.
 * TreeFixer plants it as a system app in every guest that can run it — the KK and N64
 * engines at api 14+ — with no switch: a shell is basic firmware equipment, and the
 * guest's own root story (the planted su asking the Superuser manager) needs a terminal
 * in the drawer to be usable at all. The GB engine and api < 14 are out of range: the
 * app is built for api 14+, and the 2.3 firmware generation is not a target.
 *
 * The plant path follows the generation's system-app layout: 5.0+ keeps one directory
 * per app under /system/app, while a 4.x PackageManager only scans flat .apk files
 * there and would never see a directory-form plant.
 */
internal object TerminalSupport {
    const val ASSET = "engines/kk/aemu-terminal.apk"
    const val PACKAGE = "jackpal.androidterm"

    /** 5.0+ system-app layout: /system/app/<App>/<App>.apk. */
    const val APP_PATH_DIR = "system/app/Terminal/Terminal.apk"

    /** Pre-5.0 system-app layout: a flat .apk directly in /system/app. */
    const val APP_PATH_FLAT = "system/app/Terminal.apk"

    fun pathFor(api: Int): String = if (api >= 21) APP_PATH_DIR else APP_PATH_FLAT
}
