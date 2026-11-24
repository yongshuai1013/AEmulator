package app.aemu.core

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One netd incarnation's QEMU_STRACE capture (measurement build, see GuestVm's NETD_STRACE).
 *
 * The translator's strace lines carry no timestamps, so the service pump stamps each line as it
 * arrives: a line's arrival time is (to pump accuracy) the moment its syscall returned. The gap
 * between consecutive lines is therefore a park, and the line that ends the longest gap is the
 * call netd was parked in. The full stamped trace stays in run/netd.log, which the UI log export
 * cannot reach, so when the trace settles this class mirrors a digest into the host log.
 */
class NetdTrace(private val t0: Long) {
    private val lock = Object()
    private val times = ArrayList<Long>()
    private val texts = ArrayList<String>()

    @Volatile var truncated = false
        private set

    @Volatile private var exitedAt = -1L
    private val digested = AtomicBoolean(false)

    /** Records one output line; returns its elapsed ms since the spawn. */
    fun record(line: String): Long {
        val el = SystemClock.uptimeMillis() - t0
        synchronized(lock) {
            if (times.size < MAX_LINES) {
                times.add(el)
                texts.add(line)
            } else truncated = true
        }
        return el
    }

    fun exited() {
        if (exitedAt < 0) exitedAt = SystemClock.uptimeMillis() - t0
    }

    /**
     * Watches the trace and logs the digest exactly once, at the earliest of: the trace quiet for
     * 25 s after at least 60 s of life; the process out and quiet for 5 s; a 420 s hard cap; or the
     * VM stopping (best effort with whatever has been captured).
     */
    fun digestThread(stopping: () -> Boolean, log: (String) -> Unit): Thread {
        val th = Thread({
            while (!digested.get()) {
                runCatching { Thread.sleep(2_000) }
                val now = SystemClock.uptimeMillis() - t0
                val lastEl = synchronized(lock) { times.lastOrNull() ?: -1L }
                val quiet = if (lastEl < 0) now else now - lastEl
                val fire = when {
                    stopping() -> true
                    exitedAt >= 0 && quiet >= 5_000 -> true
                    quiet >= 25_000 && now >= 60_000 -> true
                    now >= 420_000 -> true
                    else -> false
                }
                if (fire && digested.compareAndSet(false, true)) digest(now, log)
            }
        }, "netd-trace-digest")
        th.isDaemon = true
        return th
    }

    private fun digest(now: Long, log: (String) -> Unit) {
        val ts: List<Long>
        val xs: List<String>
        synchronized(lock) {
            ts = ArrayList(times)
            xs = ArrayList(texts)
        }
        if (ts.isEmpty()) {
            log("netd-trace: no output captured from netd in ${now / 1000} s (QEMU_STRACE ignored, or netd printed nothing)")
            return
        }
        val syscallLines = xs.count { it.contains('(') && it.contains(") =") }
        log(
            "netd-trace: ${ts.size} lines over ${fmt(ts.last() / 1000.0)} s " +
                "($syscallLines syscall lines)${if (truncated) ", truncated at $MAX_LINES lines" else ""}"
        )
        // gaps between consecutive lines, longest first; the line ending a gap is the parked call
        val gaps = (1 until ts.size)
            .map { (ts[it] - ts[it - 1]) to it }
            .sortedByDescending { it.first }
            .take(5)
        for ((gapMs, endIdx) in gaps) {
            log("netd-trace: gap ${fmt(gapMs / 1000.0)} s ending at +${fmt(ts[endIdx] / 1000.0)} s: ${xs[endIdx].take(200)}")
        }
        val big = gaps.firstOrNull()
        if (big != null) {
            val endIdx = big.second
            for (i in maxOf(0, endIdx - 8) until endIdx) {
                log("netd-trace: before: [+${fmt(ts[i] / 1000.0)} s] ${xs[i].take(200)}")
            }
        }
        val quiet = now - ts.last()
        if (exitedAt < 0 && quiet > 20_000) {
            log(
                "netd-trace: still silent ${fmt(quiet / 1000.0)} s after the last line and netd is alive: " +
                    "it is parked inside a call that has not returned. Last completed: ${xs.last().take(200)}"
            )
        }
    }

    private fun fmt(s: Double): String = String.format(Locale.US, "%.1f", s)

    companion object {
        private const val MAX_LINES = 300_000
    }
}
