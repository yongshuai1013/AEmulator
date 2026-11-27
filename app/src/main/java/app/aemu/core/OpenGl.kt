package app.aemu.core

import android.content.Context
import java.io.File

/**
 * Ported from AEmulator Plus .17 (2026-11-25): the open GPU bridge (native/glbridge: libGLES_aemu.so in the guest, GlHost in the app) for ARM
 * firmware on the 32-bit engines, instead of the engine's closed pair (libGLES.so / libGLES_dhd.so in the guest, libglserverd_* on the host).
 *
 * Why the closed pair goes: its drawing was never correct for hwui (artifacts, so hwui had to stay off), and its fb0 page flipping hangs
 * Gingerbread. The open bridge is the whole EGL of the guest ("solo" mode, as for x86 firmware since 2026-11-20): every context is made on
 * the phone's GPU, GLES 1 by the fixed-function layer of gles1.h, and every frame comes back into the window's buffer, which the guest's own
 * gralloc and framebuffer HAL put on the screen exactly as with software rendering.
 *
 * Which releases: every released one, 1.0 to 7.x (a pre-1.0 build keeps its software renderer: its window and loader were never read).
 *  - 2.0 and later: the EGL window is android_native_window_t (include/ui/egl/android_natives.h of 2.0 - 2.2, system/window.h from 2.3 on),
 *    whose first slots are the same in every release up to 7.x and which guest.c reads by its common.version (win_new_abi). The driver is
 *    the GL driver libEGL finds through egl.cfg (libGLES_aemu.so in system/lib/egl).
 *  - 1.0 - 1.6: the window is an egl_native_window_t (opengl/include/EGL/eglnatives.h of 1.6): a mapped buffer plus swapBuffers(), which
 *    guest.c fills from the host's frame and then calls (bridge_swap_egl). libEGL of those releases has no egl.cfg: it dlopen()s libagl.so
 *    (software) and libhgl.so (hardware), so the driver takes the place of libagl.so, the original kept beside it. The display window of
 *    SurfaceFlinger is a pair of pages of fb0 there, which is what the 1.0 / 1.1 translator (yres_virtual =
 *    2 * yres) is for: the driver writes the back page and swapBuffers flips, exactly as libagl did.
 *
 * Adaptations to this tree: GuestImage has no GuestArch here (the ABI is a string, "armeabi-v7a" by default) and there is no
 * LegacyFirmware (its isPreRelease(release) is exactly release.startsWith("0."), inlined below); this tree ships no x86 firmware
 * for the KK engine (EngineAssets has no kkX86OpenBridge), so [any] is [arm].
 */
object OpenGl {
    /** the armv7 build of the guest driver (native/glbridge/build32-zig.sh) */
    const val ARM32 = "engines/kk/libGLES_aemu.so"

    /** the lowest API level (1.0) */
    const val MIN_API = 1

    /** below this the driver is libagl.so (see above), from it libGLES_aemu.so in egl/ */
    const val EGL_CFG_API = 5

    /** the original libagl.so is kept under this name while the driver stands in for it */
    const val AGL_KEPT = "libagl.so.aemu-sw"

    /** where the driver is in the guest's tree */
    fun driverPath(api: Int): String = if (api < EGL_CFG_API) "system/lib/libagl.so" else "system/lib/egl/libGLES_aemu.so"

    /** a file with this name in the image's directory keeps the VM on the engine's closed driver (switch back, for a comparison or a bug) */
    const val CLOSED = "glclosed"

    /** this ARM guest on a 32-bit engine draws through the open bridge (upstream also ORs the x86 guest of the KK engine, which this tree does not ship) */
    fun arm(ctx: Context, img: GuestImage, dir: File): Boolean =
        (img.engine == Engine.KK || img.engine == Engine.GB) && img.abi.startsWith("arm") && img.api >= MIN_API &&
            !img.release.startsWith("0.") && !File(dir, CLOSED).isFile && EngineAssets.exists(ctx, ARM32)

    /** any guest of the 32-bit engines that uses the open bridge */
    fun any(ctx: Context, img: GuestImage, dir: File): Boolean =
        arm(ctx, img, dir)

    /**
     * What egl.cfg says. The EGL of 2.0 - 4.0 (Loader.cpp) has two slots, "0 0 tag" the software one it insists on and "0 1 tag" the
     * hardware one; with the software slot left on libagl a program may be given a PixelFlinger config, so up to 4.0.4 the bridge is the
     * only line (as the engine's own driver was). Later loaders take the last line, or the one with impl=1: both name the bridge.
     */
    fun eglCfg(api: Int): String = if (api <= 15) "0 0 aemu\n" else "0 0 android\n0 1 aemu\n"
}
