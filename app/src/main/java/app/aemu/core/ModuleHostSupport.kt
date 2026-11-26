/* Offline Magisk-module host, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.core

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File

/** Android side of [ModuleHost]: installing a picked module zip into a stopped VM and managing it. */
internal object ModuleHostSupport {
    /** v1 is offered for Android 6.0 (API 23) guests on the native engine only. */
    fun offered(img: GuestImage): Boolean = img.api == 23 && img.effective().engine == Engine.N64

    fun store(ctx: Context, id: String): File = File(VmPaths(ctx, id).dir, "modhost")

    fun list(ctx: Context, id: String): List<ModuleHost.Info> = ModuleHost.list(store(ctx, id))

    /** Exact unix modes through Os (lstat, so a symlink's own mode is never mistaken for its target's). */
    private val modeIo = object : ModuleHost.ModeIo {
        override fun read(f: File): Int = runCatching { Os.lstat(f.path).st_mode and 0xFFF }.getOrDefault(-1)
        override fun write(f: File, mode: Int) { runCatching { Os.chmod(f.path, mode) } }
    }

    /** The same guard the Magisk install uses: a guest's files must not change under a running VM. */
    private fun requireNoVmRunning(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        check(am.runningAppProcesses.orEmpty().none { it.processName == ctx.packageName + ":vm" }) { "Stop the VM first" }
    }

    private fun touchSize(ctx: Context, id: String) {
        val paths = VmPaths(ctx, id)
        ImageStore.get(ctx, id)?.let { ImageStore.save(ctx, it.copy(sizeBytes = ImageStore.du(paths.dir))) }
    }

    /** [uri] is the module zip. The VM must be stopped; the report says what was placed and what was not. */
    fun install(ctx: Context, id: String, uri: Uri): ModuleHost.Report {
        requireNoVmRunning(ctx)
        val paths = VmPaths(ctx, id)
        val tmp = File(ctx.cacheDir, "module-${System.nanoTime()}.zip")
        try {
            (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the module zip")).use { i ->
                tmp.outputStream().use { i.copyTo(it) }
            }
            val report = ModuleHost.install(paths.root, store(ctx, id), tmp, modeIo) {}
            touchSize(ctx, id)
            return report
        } finally { tmp.delete() }
    }

    fun setEnabled(ctx: Context, id: String, moduleId: String, enabled: Boolean) {
        requireNoVmRunning(ctx)
        ModuleHost.setEnabled(VmPaths(ctx, id).root, store(ctx, id), moduleId, enabled, modeIo) {}
        touchSize(ctx, id)
    }

    fun uninstall(ctx: Context, id: String, moduleId: String) {
        requireNoVmRunning(ctx)
        ModuleHost.uninstall(VmPaths(ctx, id).root, store(ctx, id), moduleId, modeIo) {}
        touchSize(ctx, id)
    }
}
