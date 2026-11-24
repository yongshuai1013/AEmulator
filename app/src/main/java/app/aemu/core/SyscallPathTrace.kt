package app.aemu.core

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Filtered QEMU_STRACE capture for one spawned translator process (measurement builds).
 *
 * Some processes are worth tracing whole — the zygote's translator carries system_server and
 * every app process, installd is a daemon — but their raw strace is far too large to mirror into
 * the host log, and the raw file (the service's own run/<name>.log) is outside the UI log export.
 * This capture therefore keeps only the lines that name one of [needles] (a path fragment is
 * enough: the interesting calls are the ones that touch that path) and surfaces those, with the
 * pump's arrival stamps, in the host log.
 *
 * Two modes. Digest mode (the zygote/acore capture) stores the matching lines and mirrors them
 * into the host log exactly once, when the trace settles — same triggers as [NetdTrace]. Mirror
 * mode (installd, a quiet daemon) stores nothing: the pump asks [isMatch] per line and logs the
 * hits itself as they arrive; [summary] then closes the incarnation with the counts.
 */
class SyscallPathTrace(
    private val tag: String,
    private val needles: List<String>,
    private val digestMode: Boolean,
    private val t0: Long,
) {
    private val lock = Object()
    private var total = 0
    private var syscallLines = 0
    @Volatile private var lastAt = -1L
    private val matches = ArrayList<Pair<Long, String>>()
    private var matchCount = 0
    private var failedMatches = 0
    private var matchesDropped = 0
    private val ring = ArrayDeque<String>()
    private var firstFailureContext: List<String>? = null

    @Volatile private var exitedAt = -1L
    private val digested = AtomicBoolean(false)

    fun isMatch(line: String): Boolean = needles.any { line.contains(it) }

    /** Records one output line; returns its elapsed ms since the spawn. */
    fun record(line: String): Long {
        val el = SystemClock.uptimeMillis() - t0
        synchronized(lock) {
            total++
            lastAt = el
            if (line.contains('(') && line.contains(") =")) syscallLines++
            if (isMatch(line)) matchCount++
            if (digestMode) {
                if (isMatch(line)) {
                    val failed = line.contains("= -1")
                    if (failed) {
                        failedMatches++
                        if (firstFailureContext == null) firstFailureContext = ArrayList(ring)
                    }
                    if (matches.size < MAX_MATCHES) matches.add(el to line) else matchesDropped++
                }
                ring.addLast(line)
                while (ring.size > CONTEXT_LINES) ring.removeFirst()
            }
        }
        return el
    }

    fun exited() {
        if (exitedAt < 0) exitedAt = SystemClock.uptimeMillis() - t0
    }

    /**
     * Digest mode only: watches the trace and logs the digest exactly once, at the earliest of:
     * the trace quiet for 25 s after at least 60 s of life; the process out and quiet for 5 s;
     * a 420 s hard cap; or the VM stopping (best effort with whatever has been captured).
     */
    fun digestThread(stopping: () -> Boolean, log: (String) -> Unit): Thread {
        val th = Thread({
            while (!digested.get()) {
                runCatching { Thread.sleep(2_000) }
                val now = SystemClock.uptimeMillis() - t0
                val quiet = if (lastAt < 0) now else now - lastAt
                val fire = when {
                    stopping() -> true
                    exitedAt >= 0 && quiet >= 5_000 -> true
                    quiet >= 25_000 && now >= 60_000 -> true
                    now >= 420_000 -> true
                    else -> false
                }
                if (fire && digested.compareAndSet(false, true)) digest(log)
            }
        }, "$tag-digest")
        th.isDaemon = true
        return th
    }

    private fun digest(log: (String) -> Unit) {
        val ms: List<Pair<Long, String>>
        val tot: Int
        val sys: Int
        val named: Int
        val failed: Int
        val dropped: Int
        val ctx: List<String>?
        synchronized(lock) {
            ms = ArrayList(matches)
            tot = total
            sys = syscallLines
            named = matchCount
            failed = failedMatches
            dropped = matchesDropped
            ctx = firstFailureContext
        }
        if (ms.isEmpty()) {
            log("$tag: no line named ${needles.joinToString(" or ")} in $tot captured lines " +
                "($sys syscall lines) — the trace was not active for this process, or the path is reached another way")
            return
        }
        log("$tag: $tot lines captured ($sys syscall lines); $named named " +
            "${needles.joinToString("/")}, $failed of them failed (= -1)" +
            if (dropped > 0) "; only the first ${ms.size} are mirrored here" else "")
        for ((el, line) in ms.take(MAX_MIRRORED)) {
            log("$tag: [+${fmt(el / 1000.0)} s] ${line.take(300)}")
        }
        if (ms.size > MAX_MIRRORED) log("$tag: … and ${ms.size - MAX_MIRRORED} more matching lines (raw trace in the service log)")
        if (ctx != null && failed > 0) {
            log("$tag: the ${CONTEXT_LINES} lines just before the first failed match:")
            for (line in ctx) log("$tag:   ${line.take(300)}")
        }
    }

    /** Mirror mode only: one closing line with the counts of this incarnation. */
    fun summary(log: (String) -> Unit) {
        val tot: Int
        val hit: Int
        synchronized(lock) {
            tot = total
            hit = matchCount
        }
        if (tot > 0) log("$tag: $tot lines captured this incarnation, $hit named ${needles.joinToString("/")}")
    }

    private fun fmt(s: Double): String = String.format(Locale.US, "%.1f", s)

    companion object {
        private const val MAX_MATCHES = 600
        private const val MAX_MIRRORED = 200
        private const val CONTEXT_LINES = 8

        /** Cap on stamped raw lines the pump writes to the traced service's own log file: a traced
         * zygote produces orders of magnitude more than a traced daemon, and the file must not be
         * able to fill the phone. Matching lines keep being recorded after the cap. */
        const val RAW_FILE_CAP = 300_000
    }
}
