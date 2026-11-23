/* AEmulator Plus addition, 2026-11-21. GPL-3.0; see LICENSE. */
package app.aemu.core

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Android side of [MagiskCore]: installing a picked Magisk APK into a stopped VM and taking it out again. */
internal object MagiskSupport {
    /**
     * Offered for the native engine and an arm64 firmware with a 64-bit side. A firmware that is 32-bit only would need
     * the armeabi-v7a build under qemu-arm, which has no setns/mount stand-ins: not done.
     * (This tree names the firmware's ABI [GuestImage.abi] where upstream has a GuestArch enum.)
     */
    fun offered(img: GuestImage, root: File): Boolean =
        img.effective().engine == Engine.N64 && img.abi == "arm64-v8a" && MagiskCore.hasArm64Userland(root)

    /**
     * The files of a VM must not change under a running one (the VMs live in the :vm process; the same check the
     * library's archive import/export guards with). Upstream leases the storage instead; this tree has no lease.
     */
    private fun requireNoVmRunning(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        check(am.runningAppProcesses.orEmpty().none { it.processName == ctx.packageName + ":vm" }) { "Stop all VMs first" }
    }

    /** [uri] is the Magisk app APK (the full app, not the stub). Stop all VMs first, as for the other imports. */
    fun install(ctx: Context, id: String, uri: Uri): MagiskCore.Info {
        requireNoVmRunning(ctx)
        val paths = VmPaths(ctx, id)
        val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
        val apk = File(ctx.cacheDir, "magisk-${System.nanoTime()}.apk")
        try {
            (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the APK")).use { i ->
                apk.outputStream().use { i.copyTo(it) }
            }
            val info = MagiskCore.install(paths.root, apk)
            ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
            return info
        } finally { apk.delete() }
    }

    fun remove(ctx: Context, id: String) {
        requireNoVmRunning(ctx)
        val paths = VmPaths(ctx, id)
        val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
        MagiskCore.uninstall(paths.root)
        ImageStore.save(ctx, old.copy(settings = old.settings.copy(magisk = false), sizeBytes = ImageStore.du(paths.dir)))
    }
}
