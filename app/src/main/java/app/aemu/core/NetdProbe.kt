package app.aemu.core

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.File

/**
 * Added for AEmulator Plus (2026-11-17), race fix: "BOOT FAILURE starting Connectivity Service" / zygote exit 137.
 *
 * dhdrun binds AND listens on the netd command socket before it execs netd, so the socket file (the only thing the boot
 * sequence used to wait for) exists, and accepts connections into its backlog, long before netd runs its own listener. system_server's
 * NetdConnector therefore connects at once, sends "interface list" and waits 60 s for a reply that a stalled netd never
 * writes; ConnectivityService then fails to start, the Google backup transport dereferences the missing
 * IConnectivityManager on the ServerThread and system_server dies (zygote exit 137). Whether netd stalls depends on timing
 * (a slow device under qemu), so it showed up for one user all the time and for another never.
 *
 * [serving] asks netd the same question the framework will ask and reports whether it answers.
 */
object NetdProbe {
    /** true when netd answered a command on [socket] within [timeoutMs]; false on silence, refusal or any error */
    fun serving(socket: File, timeoutMs: Int): Boolean {
        val s = LocalSocket()
        return try {
            s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = timeoutMs
            // the framework's own wire format: "<sequence> <command>\0"; sequence 0 is not used by NativeDaemonConnector
            // (its numbers start at 1), so the answer cannot be mistaken for one of its own
            s.outputStream.apply { write("0 interface list\u0000".toByteArray(Charsets.US_ASCII)); flush() }
            s.inputStream.read() >= 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * Polls [serving] until netd answers or [totalMs] have passed. Each try is short and the pause between tries is
     * [pauseMs]: a refused connect (no listener yet) fails at once and is simply tried again.
     */
    fun waitServing(socket: File, totalMs: Long, tryMs: Int = 2_000, pauseMs: Long = 250, stopped: () -> Boolean = { false }): Boolean {
        val until = SystemClock.uptimeMillis() + totalMs
        while (!stopped() && SystemClock.uptimeMillis() < until) {
            if (socket.exists() && serving(socket, tryMs)) return true
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) { return false }
        }
        return false
    }
}
