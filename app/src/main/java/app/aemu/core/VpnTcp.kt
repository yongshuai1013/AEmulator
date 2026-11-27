package app.aemu.core

import java.util.ArrayDeque
import java.util.TreeMap

/**
 * M3 terminator contract (design vpn-m3-design.md, B0): one instance per detoured guest
 * connection. The hub owns the tunnel channel and the guest stream descriptor; this class
 * owns only the TCP endpoint state between them, behind a narrow contract — open the
 * connection, feed guest bytes, consume tunnel packets, tick the retransmit clock, drain
 * deliverables — so the endpoint stack can be swapped (the design's lwIP fallback) without
 * the hub or the supervisors changing shape.
 *
 * The handshake is hub <-> real server, through the VPN app's transparent forwarding:
 * this class emits the SYN, completes on the SYN-ACK, and the guest's connect() keeps the
 * optimistic success it got at detour time (M2 semantics, unchanged). Failure presentation
 * is the design's section 5 vocabulary, carried in [closeReason]: complete, rst,
 * connect-failed-rst, connect-failed-timeout, timeout, guest-gone, session-down.
 *
 * Wire tuple (design sections 0/4): segments into the tunnel are sourced at the session
 * address and the flow's source port, destined to the real server; inbound segments are
 * the mirror image. Nothing here knows about files, sockets or the hub's registries.
 */
sealed class TcpDeliverable {
    class Data(val bytes: ByteArray) : TcpDeliverable()
    object Eof : TcpDeliverable()
    object Dead : TcpDeliverable()
}

class VpnTcpConn(
    val id: Int,
    val gen: Int,
    val serverIp: Int,
    val serverPort: Int,
    val sessionAddr: Int,
    val srcPort: Int,
    private val mtu: Int,
    private val emit: (ByteArray) -> Boolean,
    private val onEstablished: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val lock = Object()

    private var phase = PHASE_NEW
    private var isn = 0L
    private var sndUna = 0L
    private var sndNxt = 0L
    private var rcvNxt = 0L
    private var peerWnd = 65535L
    private var peerScale = 0
    private var peerMss = 0
    private var connectStart = 0L
    private var synSentAt = 0L
    private var synRtx = 0
    private val unacked = ArrayDeque<Seg>()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingHeadOff = 0
    private var pendingBytes = 0
    private var guestEof = false
    private var finSent = false
    private var finSeq = 0L
    private var serverFin = false
    private var pendingFinAt = -1L
    private val ooo = TreeMap<Long, ByteArray>()
    private var oooBytes = 0
    private val deliverQ = ArrayDeque<ByteArray>()
    private var deliverQBytes = 0
    private var downSeen = false   // gate2 egress series: the first downstream byte has arrived
    private var eofDelivered = false
    private var abortive = false
    private var pktId = 0

    @Volatile var dead = false
        private set
    @Volatile var closeReason = ""
        private set
    @Volatile var upBytes = 0L
        private set
    @Volatile var downBytes = 0L
        private set
    @Volatile var badCksum = 0
        private set

    private class Seg(val seq: Long, val data: ByteArray, val fin: Boolean) {
        var lastSent = 0L
        var rto = RTO_INIT
        var rtx = 0
        fun end(): Long = seq + data.size + if (fin) 1 else 0
    }

    /** Begins the handshake toward the real server: emits the SYN (with MSS and the
     * window-scale option the design requires; without it our receive window would be
     * capped at 64 KB and throughput with it at 64 KB per tunnel round trip). */
    fun open(now: Long) {
        synchronized(lock) {
            if (phase != PHASE_NEW) return
            isn = ((System.nanoTime() ushr 8) xor (id.toLong() * 0x9e3779b9L)) and 0xffffffffL
            sndUna = isn
            sndNxt = isn + 1
            connectStart = now
            synSentAt = now
            phase = PHASE_SYN
            emitLocked(buildLocked(FLAG_SYN, isn, 0, EMPTY, true))
            log("aemu-vpn: flow $id syn-sent dst=${serverIp ushr 24}.${(serverIp ushr 16) and 0xff}.${(serverIp ushr 8) and 0xff}.${serverIp and 0xff}:$serverPort srcport=$srcPort gen=$gen")
            log("aemu-egress: flow $id connect dst=${serverIp ushr 24}.${(serverIp ushr 16) and 0xff}.${(serverIp ushr 8) and 0xff}.${serverIp and 0xff}:$serverPort t=$now")
        }
    }

    /** Feeds bytes read from the guest stream. Blocks while the not-yet-segmented backlog
     * is at its cap — that is the backpressure point toward the guest — and returns false
     * once the connection is dead or the guest already half-closed. */
    fun guestBytes(b: ByteArray, off: Int, len: Int): Boolean {
        synchronized(lock) {
            if (dead || guestEof) return false
            var copied = 0
            while (copied < len) {
                while (pendingBytes >= PEND_CAP && !dead) lock.wait(50)
                if (dead) { upBytes += copied; return false }
                val room = PEND_CAP - pendingBytes
                val m = minOf(room, len - copied)
                pending.add(b.copyOfRange(off + copied, off + copied + m))
                pendingBytes += m
                copied += m
                pumpLocked()
            }
            upBytes += copied
            return true
        }
    }

    /** The guest stream hit EOF: queue our FIN behind everything still to send. The flow
     * lives on — the server may still be sending, and its data is still delivered. */
    fun guestEof() {
        synchronized(lock) {
            if (dead || guestEof) return
            guestEof = true
            pumpLocked()
            lock.notifyAll()
        }
    }

    /** The guest descriptor died outright (a delivery write failed). If the connection had
     * not closed cleanly, leave a RST behind so the server can reap its side at once. */
    fun guestGone() {
        synchronized(lock) {
            if (dead) return
            if (phase == PHASE_SYN || phase == PHASE_ESTABLISHED) {
                emitLocked(buildLocked(FLAG_RST or FLAG_ACK, sndNxt, rcvNxt, EMPTY, false))
            }
            dieLocked("guest-gone", true)
        }
    }

    /** The hub is tearing the connection down from outside (session end, hub stop). */
    fun abort(reason: String) {
        synchronized(lock) { dieLocked(reason, true) }
    }

    /** Marks a fully-closed connection complete; the hub reaps it right after. */
    fun markComplete() {
        synchronized(lock) {
            if (!dead) {
                dead = true
                closeReason = "complete"
                abortive = false
                lock.notifyAll()
            }
        }
    }

    /** True once both FINs are exchanged, ours is acked, and the guest has been given its
     * EOF — the hub then reaps the flow and leaves an ember behind (see emberRcvNxt). */
    fun fullyClosed(): Boolean = synchronized(lock) {
        !dead && phase == PHASE_ESTABLISHED && serverFin && eofDelivered && finSent && sndUna > finSeq
    }

    /** The rcvNxt an ember should answer for, or -1 when this close leaves no ember
     * (anything but a clean active close: our FIN sent and acked, the server's received). */
    fun emberRcvNxt(): Long = synchronized(lock) {
        if (!dead && phase == PHASE_ESTABLISHED && serverFin && finSent && sndUna > finSeq) rcvNxt else -1L
    }

    /** The sequence number just past our FIN, for the ember's ACKs. */
    fun emberSeq(): Long = synchronized(lock) { sndNxt }

    /** Consumes one raw IPv4 packet read back out of the tunnel (the caller dispatched it
     * to this connection by its tuple). Runs on the tunnel reader thread and never blocks:
     * inbound data is only queued for the delivery writer. */
    fun onPacket(p: ByteArray, n: Int) {
        synchronized(lock) {
            if (dead || phase == PHASE_NEW) return
            if (n < 40) return
            val ihl = (p[0].toInt() and 0x0f) * 4
            if (ihl < 20 || n < ihl + 20) return
            val total = be16(p, 2)
            val end = if (total >= ihl + 20 && total <= n) total else n
            if (!tcpChecksumOk(p, ihl, end)) { badCksum++; return }
            val t = ihl
            val seqRaw = be32s(p, t + 4)
            val ackRaw = be32s(p, t + 8)
            val doff = (p[t + 12].toInt() ushr 4) * 4
            if (doff < 20 || ihl + doff > end) return
            val flags = p[t + 13].toInt() and 0xff
            val wndRaw = be16(p, t + 14)
            val payloadOff = ihl + doff
            val payloadLen = end - payloadOff

            if (phase == PHASE_SYN) {
                if (flags and FLAG_RST != 0) {
                    log("aemu-vpn: flow $id connect failed (rst)")
                    dieLocked("connect-failed-rst", true)
                    return
                }
                if (flags and FLAG_SYN != 0 && flags and FLAG_ACK != 0) {
                    if (absFromWire(ackRaw, sndUna) != isn + 1) return   // not our SYN's answer
                    rcvNxt = (seqRaw.toLong() and 0xffffffffL) + 1
                    parseOptions(p, t + 20, payloadOff)
                    peerWnd = (wndRaw shl peerScale).toLong()
                    sndUna = isn + 1
                    phase = PHASE_ESTABLISHED
                    emitLocked(ackLocked())
                    onEstablished()
                    log("aemu-egress: flow $id established afterMs=${System.currentTimeMillis() - connectStart}")
                    if (payloadLen > 0) {
                        receiveDataLocked(rcvNxt, p, payloadOff, payloadLen)
                        emitLocked(ackLocked())
                    }
                    pumpLocked()
                }
                return
            }

            // Established. A RST inside the receive window aborts at once: queues are
            // cleared and the hub closes the guest descriptor (its reads see what was
            // already delivered, then EOF; its writes get EPIPE).
            if (flags and FLAG_RST != 0) {
                val seqAbs = absFromWire(seqRaw, rcvNxt)
                if (seqAbs >= rcvNxt - RECV_CAP && seqAbs < rcvNxt + RECV_CAP) dieLocked("rst", true)
                return
            }
            if (flags and FLAG_SYN != 0) {
                // A retransmitted SYN-ACK (our ACK was lost): answer it again.
                emitLocked(ackLocked())
                return
            }
            if (flags and FLAG_ACK != 0) {
                peerWnd = (wndRaw shl peerScale).toLong()
                val ackAbs = absFromWire(ackRaw, sndUna)
                if (ackAbs > sndUna && ackAbs <= sndNxt) advanceAckLocked(ackAbs)
            }
            var ackDirty = false
            if (payloadLen > 0 && !serverFin) {
                receiveDataLocked(absFromWire(seqRaw, rcvNxt), p, payloadOff, payloadLen)
                ackDirty = true
            }
            if (flags and FLAG_FIN != 0) {
                val finAt = absFromWire(seqRaw, rcvNxt) + payloadLen
                if (finAt == rcvNxt) {
                    rcvNxt += 1
                    serverFin = true
                    lock.notifyAll()
                } else if (finAt > rcvNxt && pendingFinAt < 0) {
                    pendingFinAt = finAt
                }
                ackDirty = true
            }
            if (ackDirty) emitLocked(ackLocked())
            pumpLocked()
            lock.notifyAll()
        }
    }

    /** The retransmit/timeout driver, called by the hub's timer. Every callback first
     * re-checks the connection is still the live one — the hub only ever ticks flows in
     * its live registry, and a dead connection's tick is a no-op, so no timer can act
     * inside a later session (design section 2). */
    fun tick(now: Long) {
        synchronized(lock) {
            if (dead || phase == PHASE_NEW) return
            if (phase == PHASE_SYN) {
                if (now - connectStart >= CONNECT_TIMEOUT_MS) {
                    log("aemu-vpn: flow $id connect failed (timeout)")
                    dieLocked("connect-failed-timeout", true)
                    return
                }
                if (now - synSentAt >= synRto()) {
                    synRtx++
                    synSentAt = now
                    emitLocked(buildLocked(FLAG_SYN, isn, 0, EMPTY, true))
                }
                return
            }
            pumpLocked()
            val head = unacked.peekFirst() ?: return
            if (now - head.lastSent >= head.rto) {
                if (head.rtx >= MAX_RTX) {
                    dieLocked("timeout", true)
                    return
                }
                head.rtx++
                head.lastSent = now
                head.rto = minOf(RTO_MAX, maxOf(RTO_MIN, head.rto * 2))
                val fl = (if (head.fin) FLAG_FIN else FLAG_PSH) or FLAG_ACK
                emitLocked(buildLocked(fl, head.seq, rcvNxt, head.data, false))
            }
        }
    }

    /** Blocks the calling (per-flow writer) thread until there is something to hand the
     * guest: data, the EOF that follows the server's FIN once everything before it has
     * been delivered, or the connection's death. */
    fun nextDeliverable(): TcpDeliverable {
        synchronized(lock) {
            while (true) {
                val head = deliverQ.peekFirst()
                if (head != null) {
                    deliverQ.removeFirst()
                    deliverQBytes -= head.size
                    if (!dead && phase == PHASE_ESTABLISHED) emitLocked(ackLocked())
                    return TcpDeliverable.Data(head)
                }
                if (dead) return TcpDeliverable.Dead
                if (serverFin && !eofDelivered) {
                    eofDelivered = true
                    return TcpDeliverable.Eof
                }
                if (serverFin) return TcpDeliverable.Dead
                lock.wait(100)
            }
        }
    }

    // ---- state machine internals (all called with lock held)

    private fun dieLocked(reason: String, abortive: Boolean) {
        if (dead) return
        dead = true
        closeReason = reason
        this.abortive = abortive
        if (abortive) log("aemu-egress: flow $id dead reason=$reason up=$upBytes down=$downBytes")
        unacked.clear()
        pending.clear()
        pendingBytes = 0
        ooo.clear()
        oooBytes = 0
        if (abortive) {
            deliverQ.clear()
            deliverQBytes = 0
        }
        lock.notifyAll()
    }

    private fun advanceAckLocked(ackAbs: Long) {
        sndUna = ackAbs
        while (true) {
            val head = unacked.peekFirst() ?: break
            if (head.end() <= ackAbs) {
                unacked.removeFirst()
                continue
            }
            if (head.seq < ackAbs && !head.fin && head.data.isNotEmpty()) {
                // Partial ack: the acknowledged prefix drops off, the rest keeps its place.
                val cut = (ackAbs - head.seq).toInt()
                val trimmed = Seg(ackAbs, head.data.copyOfRange(cut, head.data.size), false)
                trimmed.lastSent = head.lastSent
                trimmed.rto = head.rto
                trimmed.rtx = head.rtx
                unacked.removeFirst()
                unacked.addFirst(trimmed)
            }
            break
        }
    }

    private fun receiveDataLocked(segSeq: Long, p: ByteArray, off: Int, len: Int) {
        var s = segSeq
        var o = off
        var l = len
        if (s < rcvNxt) {
            val trim = rcvNxt - s
            if (trim >= l) return   // entirely old: the caller's ACK is the whole answer
            s += trim
            o += trim.toInt()
            l -= trim.toInt()
        }
        if (!downSeen && l > 0) {
            downSeen = true
            log("aemu-egress: flow $id first-down bytes=$l afterMs=${System.currentTimeMillis() - connectStart}")
        }
        if (s == rcvNxt) {
            enqueueDeliverLocked(p, o, l)
            rcvNxt += l
            drainOooLocked()
        } else if (s - rcvNxt <= RECV_CAP && oooBytes + deliverQBytes + l <= RECV_CAP && !ooo.containsKey(s)) {
            // Out of order but inside the window: hold it; the gap's retransmit joins it up.
            ooo[s] = p.copyOfRange(o, o + l)
            oooBytes += l
        }
        // else: outside the window or no room — dropped; the ACK tells the server where we stand.
        checkFinLocked()
    }

    private fun enqueueDeliverLocked(p: ByteArray, off: Int, len: Int) {
        val copy = p.copyOfRange(off, off + len)
        deliverQ.add(copy)
        deliverQBytes += copy.size
        downBytes += copy.size
        lock.notifyAll()
    }

    private fun drainOooLocked() {
        while (true) {
            val seg = ooo.remove(rcvNxt) ?: break
            oooBytes -= seg.size
            deliverQ.add(seg)
            deliverQBytes += seg.size
            downBytes += seg.size
            rcvNxt += seg.size
        }
        if (deliverQ.isNotEmpty()) lock.notifyAll()
    }

    private fun checkFinLocked() {
        if (pendingFinAt >= 0 && pendingFinAt == rcvNxt) {
            pendingFinAt = -1
            rcvNxt += 1
            serverFin = true
            lock.notifyAll()
        }
    }

    private fun pumpLocked() {
        if (phase != PHASE_ESTABLISHED || dead) return
        while (pendingBytes > 0) {
            val room = minOf(peerWnd, INFLIGHT_CAP) - (sndNxt - sndUna)
            if (room <= 0) break
            val m = minOf(room, segMaxLocked().toLong(), pendingBytes.toLong()).toInt()
            if (m <= 0) break
            val data = takePendingLocked(m)
            val seg = Seg(sndNxt, data, false)
            seg.lastSent = System.currentTimeMillis()
            unacked.add(seg)
            sndNxt += data.size
            emitLocked(buildLocked(FLAG_PSH or FLAG_ACK, seg.seq, rcvNxt, data, false))
        }
        if (guestEof && pendingBytes == 0 && !finSent) {
            finSent = true
            finSeq = sndNxt
            val seg = Seg(sndNxt, EMPTY, true)
            seg.lastSent = System.currentTimeMillis()
            unacked.add(seg)
            sndNxt += 1
            emitLocked(buildLocked(FLAG_FIN or FLAG_ACK, finSeq, rcvNxt, EMPTY, false))
        }
    }

    private fun takePendingLocked(m: Int): ByteArray {
        val out = ByteArray(m)
        var done = 0
        while (done < m) {
            val head = pending.peekFirst() ?: break
            val avail = head.size - pendingHeadOff
            val take = minOf(avail, m - done)
            System.arraycopy(head, pendingHeadOff, out, done, take)
            done += take
            pendingHeadOff += take
            if (pendingHeadOff >= head.size) {
                pending.removeFirst()
                pendingHeadOff = 0
            }
        }
        pendingBytes -= done
        lock.notifyAll()
        return out
    }

    private fun segMaxLocked(): Int {
        var base = mtu - 40
        if (peerMss in 1 until base) base = peerMss
        return maxOf(64, base)
    }

    private fun advertisedWndLocked(): Int {
        val free = RECV_CAP - deliverQBytes - oooBytes
        return (free shr WSCALE).coerceIn(0, 65535)
    }

    private fun synRto(): Long = minOf(RTO_MAX, RTO_INIT shl minOf(synRtx, 3))

    private fun ackLocked(): ByteArray = buildLocked(FLAG_ACK, sndNxt, rcvNxt, EMPTY, false)

    private fun emitLocked(pkt: ByteArray) {
        emit(pkt)
    }

    private fun buildLocked(flags: Int, seq: Long, ack: Long, payload: ByteArray, synOptions: Boolean): ByteArray =
        buildTcpPacket(sessionAddr, srcPort, serverIp, serverPort, seq, ack, flags,
            advertisedWndLocked(), payload, synOptions, mtu, ++pktId)

    private fun parseOptions(p: ByteArray, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val kind = p[i].toInt() and 0xff
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= to) break
            val len = p[i + 1].toInt() and 0xff
            if (len < 2 || i + len > to) break
            if (kind == 2 && len == 4) peerMss = be16(p, i + 2)
            if (kind == 3 && len == 3) peerScale = p[i + 2].toInt() and 0xff
            i += len
        }
    }

    /** Maps a wire (32-bit) sequence/ack value into the absolute space anchored at [ref]. */
    private fun absFromWire(raw: Int, ref: Long): Long {
        val rawU = raw.toLong() and 0xffffffffL
        val refU = ref and 0xffffffffL
        var diff = rawU - refU
        if (diff > 0x7fffffffL) diff -= 0x100000000L
        if (diff < -0x80000000L) diff += 0x100000000L
        return ref + diff
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val PHASE_NEW = 0
        const val PHASE_SYN = 1
        const val PHASE_ESTABLISHED = 2
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
        const val RTO_INIT = 500L
        const val RTO_MIN = 200L
        const val RTO_MAX = 5000L
        const val MAX_RTX = 5
        const val CONNECT_TIMEOUT_MS = 20000L
        const val RECV_CAP = 256 * 1024
        const val PEND_CAP = 256 * 1024
        const val INFLIGHT_CAP = 65536L
        const val WSCALE = 7
    }
}

// ---- packet plumbing shared by the terminator and the hub (the hub's ember ACKs)

internal fun buildTcpPacket(
    srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
    seq: Long, ack: Long, flags: Int, window: Int,
    payload: ByteArray, synOptions: Boolean, mtu: Int, ipId: Int,
): ByteArray {
    val tcpLen = if (synOptions) 28 else 20
    val total = 20 + tcpLen + payload.size
    val p = ByteArray(total)
    p[0] = 0x45
    putBe16(p, 2, total)
    putBe16(p, 4, ipId and 0xffff)
    putBe16(p, 6, 0x4000)
    p[8] = 64
    p[9] = 6
    putBe32(p, 12, srcIp)
    putBe32(p, 16, dstIp)
    putBe16(p, 20, srcPort)
    putBe16(p, 22, dstPort)
    putBe32(p, 24, (seq and 0xffffffffL).toInt())
    putBe32(p, 28, (ack and 0xffffffffL).toInt())
    p[32] = ((tcpLen / 4) shl 4).toByte()
    p[33] = flags.toByte()
    putBe16(p, 34, window)
    if (synOptions) {
        // MSS, then window scale 7 (the design's SYN upgrade), then end-of-options padding.
        p[40] = 2; p[41] = 4
        putBe16(p, 42, maxOf(64, minOf(1460, mtu - 40)))
        p[44] = 3; p[45] = 3; p[46] = 7
        p[47] = 0
    }
    System.arraycopy(payload, 0, p, 20 + tcpLen, payload.size)
    putBe16(p, 10, tcpCksumDone(tcpCksumAdd(p, 0, 20, 0)))
    var sum = tcpCksumAdd(p, 12, 8, 0)
    sum += 6 + (total - 20)
    putBe16(p, 36, tcpCksumDone(tcpCksumAdd(p, 20, total - 20, sum)))
    return p
}

internal fun tcpChecksumOk(p: ByteArray, ihl: Int, end: Int): Boolean {
    var sum = tcpCksumAdd(p, 12, 8, 0)
    sum += 6 + (end - ihl)
    sum += tcpCksumAdd(p, ihl, end - ihl, 0)
    return tcpCksumDone(sum) == 0
}

private fun tcpCksumAdd(b: ByteArray, off: Int, len: Int, seed: Long): Long {
    var sum = seed
    var i = off
    val end = off + len
    while (i + 1 < end) { sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
    if (i < end) sum += (b[i].toInt() and 0xff) shl 8
    return sum
}

private fun tcpCksumDone(sum0: Long): Int {
    var sum = sum0
    while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
    return sum.toInt().inv() and 0xffff
}

private fun be16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

private fun be32s(b: ByteArray, off: Int) =
    ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
        ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

private fun putBe16(b: ByteArray, off: Int, v: Int) { b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte() }

private fun putBe32(b: ByteArray, off: Int, v: Int) {
    b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
}
