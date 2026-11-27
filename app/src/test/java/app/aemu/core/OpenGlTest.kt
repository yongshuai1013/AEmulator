package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The open GPU bridge for ARM firmware on the 32-bit engines (added for AEmulator Plus, 2026-11-25) */
class OpenGlTest {
    @Test fun upTo404TheBridgeIsTheOnlyEglImpl() {
        // (EGL of 2.0 - 4.0 insists on impl 0 and merges the configs of impl 1: libagl's must not stay next to ours)
        for (api in listOf(5, 7, 8, 10, 14, 15)) assertEquals("api $api", "0 0 aemu\n", OpenGl.eglCfg(api))
    }

    @Test fun laterLoadersKeepAndroidAsTheFirstLineAndTheBridgeAsTheLast() {
        for (api in listOf(16, 17, 19, 21, 24, 25)) assertEquals("api $api", "0 0 android\n0 1 aemu\n", OpenGl.eglCfg(api))
    }

    @Test fun theDriverIsShipped() {
        assertTrue(File("src/main/assets/${OpenGl.ARM32}").length() > 100_000)
    }

    @Test fun everyReleasedAndroidIsCarried() {
        // 1.0 - 1.6 give EGL an egl_native_window_t (guest.c: bridge_swap_egl), the driver stands in for libagl.so there
        assertEquals(1, OpenGl.MIN_API); assertEquals("system/lib/libagl.so", OpenGl.driverPath(4)); assertEquals("system/lib/egl/libGLES_aemu.so", OpenGl.driverPath(5))
    }

    @Test fun theGuestDriverKnowsBothWindowTables() {
        val src = File("../native/glbridge/guest.c").readText()
        for (s in listOf("win_new_abi", "w_dequeue", "w_queue", "w_cancel", "lockBuffer_DEPRECATED", "bridge_swap_egl", "EGL_NATIVE_WINDOW_MAGIC")) assertTrue(s, src.contains(s))
        // no call through the 4.2+ slots left outside the wrappers
        val outside = src.lines().filter { it.contains("->dequeueBuffer(") || it.contains("->queueBuffer(") || it.contains("->cancelBuffer(") }
        assertEquals(outside.joinToString("\n"), 3, outside.size)   // (one each, inside w_dequeue / w_queue / w_cancel)
    }
}
