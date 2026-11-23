package app.aemu.core

import android.net.LocalSocket
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The host end of the guest agent's socket (/dev/socket/aemu-agent). The protocol is line-based except for
 * the file payload of a PUT:
 *
 *   agent -> host   HELLO <api>            the agent is up
 *   agent -> host   UIDS <n>               the uid set was rewritten with n entries
 *   host  -> agent  SYNC                   re-read the settings file and re-resolve the hide list
 *   host  -> agent  PUT <bytes> <name>     followed by exactly <bytes> raw bytes
 *   agent -> host   OK <guest path>        the file is in the guest's import inbox
 *   agent -> host   ERR <message>
 *
 * One connection at a time; a new agent replaces the old one, like the other bridges. Everything the agent
 * reports lands in the VM log: a failed import says so here and to the caller, never a silent success.
 */
class AgentBridge(paths: VmPaths, private val enabled: () -> Boolean, private val log: (String) -> Unit) {
    private val lock = Any()
    private var session: Session? = null
    private var client: LocalSocket? = null
    @Volatile private var serving = false
    private val server = UnixServer(paths.socket(AgentSupport.SOCKET), "guest agent") { socket -> serveClient(socket) }

    val online: Boolean get() = synchronized(lock) { session } != null

    fun serve(): Boolean {
        if (!enabled()) return false
        synchronized(lock) { if (serving) return true }
        stop()   // a previous boot's listener, if one is somehow still around, goes first
        val ok = server.start(log)
        synchronized(lock) { serving = ok }
        log("agent: bridge ${if (ok) "ready" else "unavailable"}")
        return ok
    }

    fun stop() {
        server.stop()
        val (s, c) = synchronized(lock) { Pair(session, client).also { session = null; client = null; serving = false } }
        s?.fail("the bridge stopped")
        runCatching { c?.close() }
    }

    /** Asks the connected agent to re-read its settings and re-resolve the hide list; false when it is not up. */
    fun syncNow(): Boolean {
        val s = synchronized(lock) { session } ?: return false
        s.send("SYNC")
        return true
    }

    /**
     * Streams one APK to the agent, which writes it into the guest's own import inbox; returns the guest
     * path of the written file, or null (the reason is logged). Runs on the caller's thread and blocks for
     * the transfer; the agent answers only after the file is complete and synced.
     */
    fun putApk(name: String, size: Long, open: () -> InputStream?): String? {
        val s = synchronized(lock) { session }
        if (s == null) { log("agent: import of $name impossible: the guest agent is not connected"); return null }
        val latch = CountDownLatch(1)
        var answer: String? = null
        synchronized(s.putLock) {
            s.pendingPut = { line ->
                answer = line
                latch.countDown()
            }
            try {
                val input = open()
                if (input == null) { s.pendingPut = null; log("agent: import of $name: the source could not be opened"); return null }
                s.sendBytes("PUT $size $name\n".toByteArray(Charsets.UTF_8))
                input.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var left = size
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        s.sendBytes(buf.copyOf(n))
                        left -= n
                    }
                    if (left > 0) { s.pendingPut = null; log("agent: import of $name: the source ended ${left} byte(s) early"); return null }
                }
            } catch (e: Exception) {
                s.pendingPut = null
                log("agent: import of $name failed while sending: ${e.message}")
                return null
            }
            val done = latch.await(600, TimeUnit.SECONDS)
            s.pendingPut = null
            if (!done) { log("agent: import of $name: the agent did not answer"); return null }
            val line = answer ?: return null
            return when {
                line.startsWith("OK ") -> line.substring(3).trim()
                line.startsWith("ERR ") -> { log("agent: import of $name refused: ${line.substring(4)}"); null }
                else -> { log("agent: import of $name: unexpected answer \"$line\""); null }
            }
        }
    }

    private inner class Session(val out: OutputStream) {
        val putLock = Any()
        @Volatile var pendingPut: ((String) -> Unit)? = null
        fun send(line: String) = sendBytes((line + "\n").toByteArray(Charsets.UTF_8))
        fun sendBytes(bytes: ByteArray) { synchronized(out) { out.write(bytes); out.flush() } }
        fun fail(reason: String) { pendingPut?.invoke("ERR $reason") }
    }

    private fun serveClient(socket: LocalSocket) {
        val mine = Session(socket.outputStream)
        val (oldSession, oldClient) = synchronized(lock) { Pair(session, client).also { session = mine; client = socket } }
        oldSession?.fail("replaced by a new agent connection")
        runCatching { oldClient?.close() }
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("HELLO") -> log("agent: connected ($line)")
                    line.startsWith("UIDS") -> log("agent: $line")
                    line.startsWith("OK ") || line.startsWith("ERR ") -> {
                        val p = mine.pendingPut
                        if (p != null) p(line) else log("agent: $line")
                    }
                    line.startsWith("LOG ") -> log("agent: ${line.substring(4)}")
                    else -> log("agent: $line")
                }
            }
        } catch (e: Exception) {
            log("agent: connection ended: ${e.message}")
        } finally {
            mine.fail("the agent disconnected")
            synchronized(lock) { if (session === mine) { session = null; client = null } }
        }
    }
}
