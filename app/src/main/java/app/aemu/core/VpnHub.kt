package app.aemu.core

import android.net.LocalSocket
import android.system.Os
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The resident half of the guest's VPN data plane (M2). A per-process supervisor cannot hold a
 * VPN session: the tun channel, the detoured connections and the interface's link events all
 * belong to different processes, so they meet here, in the host app, on a unix socket the
 * supervisors connect to (see the hub section of the 64-bit runner for the wire protocol).
 *
 * What the hub holds:
 *  - the tun channel endpoint, handed over by the supervisor at /dev/tun open. Reading it is
 *    how packets the guest writes into the tunnel are seen at all. A session ends when the
 *    VPN app says so (a SESSION_END line written into the tunnel before it closes anything);
 *    the channel running dry is only the fallback, because the framework keeps its own
 *    duplicate of the tun descriptor and may hold the channel open past the app's stop.
 *  - the host ends of guest connections the supervisor detoured at their connect() call. A
 *    synthesizer turns their byte stream into TCP segments and writes them into the tunnel.
 *    Only the synthesizing half exists in M2: nothing is read back out of the tunnel and
 *    answered, so a detoured connection gets no reply — that half is M3, and the test page
 *    says so rather than pretending otherwise.
 *  - the netlink listeners (netd above all). A synthesised interface has no kernel to announce
 *    its birth and death, so the hub writes the link events itself — a kernel uevent for a
 *    listener on the uevent protocol (the only dialect netd turns into an interface removal),
 *    binary RTM_NEWLINK / RTM_DELLINK for the rest — and the supervisor delivers them with a
 *    kernel-shaped receive envelope; the rest of the framework's teardown chain is the
 *    guest's own stock code.
 *
 * Everything here is fail-open: no session, no listener, a dead supervisor — the guest simply
 * keeps the behaviour it had before M2.
 */
class VpnHub(private val paths: VmPaths, private val log: (String) -> Unit) {
    private val server = UnixServer(paths.socket("aemu_vpnhub"), "vpnhub") { c -> serve(c) }
    private val lock = Object()
    private val conns = ArrayList<Conn>()
    private val listeners = HashMap<Int, Listener>()
    private val flows = HashMap<Int, Flow>()
    private var nextFlow = 1
    private var nextListener = 1
    @Volatile private var session: Session? = null
    @Volatile private var exemptUid = -1

    fun ensure() { server.start(log) }

    fun stop() {
        server.stop()
        synchronized(lock) {
            session?.let { it.alive = false; closeFd(it.peerFd) }
            session = null
            for (f in flows.values) { f.open = false; closeFd(f.fd) }
            flows.clear()
            for (l in listeners.values) closeFd(l.fd)
            listeners.clear()
            conns.clear()
        }
    }

    private class Conn(val socket: LocalSocket) {
        val wlock = Object()
        @Volatile var out: OutputStream? = null
        fun send(type: Int, payload: ByteArray) {
            val o = out ?: return
            val frame = ByteArray(5 + payload.size)
            putLe32(frame, 0, payload.size + 1)
            frame[4] = type.toByte()
            System.arraycopy(payload, 0, frame, 5, payload.size)
            synchronized(wlock) { o.write(frame); o.flush() }
        }
    }

    private class Session(val name0: String) {
        @Volatile var name = name0
        @Volatile var index = 0
        @Volatile var mtu = 1500
        @Volatile var addr = 0
        @Volatile var up = false
        @Volatile var alive = true
        var peerFd: FileDescriptor? = null
        var tunIn: InputStream? = null
        var tunOut: OutputStream? = null
        val writeLock = Object()
        @Volatile var appPkts = 0L
        @Volatile var synthPkts = 0L
    }

    private class Listener(val id: Int, val fd: FileDescriptor, val proto: Int) {
        val out: OutputStream = FileOutputStream(fd)
        val wlock = Object()
    }

    private class Flow(val id: Int, val uid: Int, val dstIp: Int, val dstPort: Int, val srcPort: Int) {
        var fd: FileDescriptor? = null
        @Volatile var open = true
        var isn = 0
        var seq = 0L
    }

    private fun serve(c: LocalSocket) {
        val conn = Conn(c)
        synchronized(lock) { conns.add(conn) }
        try {
            val inp = c.inputStream
            conn.out = c.outputStream
            while (true) {
                val hdr = readN(inp, 5) ?: break
                // A descriptor sent with a frame rides its first recvmsg: snapshot before reading on.
                val fds = runCatching { c.ancillaryFileDescriptors }.getOrNull()
                val len = le32(hdr, 0)
                if (len < 1 || len > 4096) break
                val pl = if (len > 1) readN(inp, len - 1) ?: break else ByteArray(0)
                val fd = fds?.firstOrNull()
                if (!handle(conn, hdr[4].toInt() and 0xff, pl, fd) && fd != null) closeFd(fd)
            }
        } catch (_: Throwable) {
        } finally {
            synchronized(lock) { conns.remove(conn) }
            runCatching { c.close() }
        }
    }

    /** Returns true when the frame's descriptor (if any) was taken over. */
    private fun handle(conn: Conn, type: Int, pl: ByteArray, fd: FileDescriptor?): Boolean {
        when (type) {
            T_HELLO -> conn.send(T_SESSION_STATE, statePayload())
            T_TUN_PEER -> {
                if (fd == null) return false
                // A new channel while the old session is still registered means the old
                // session's end never reached us (its stop signal was lost, or the app was
                // killed mid-session). Never stack the new session on the corpse: run the
                // old one through the full teardown first — registry cleared, SESSION_DOWN
                // broadcast, link event — so the framework buries the old interface before
                // the new one is announced.
                val stale = synchronized(lock) { session }
                if (stale != null) {
                    log("aemu-vpn: a new tun channel arrived while session ${stale.name} was still registered; replacing stale session ${stale.name}")
                    sessionDown(stale)
                }
                val s = Session("")
                s.peerFd = fd
                s.tunIn = FileInputStream(fd)
                s.tunOut = FileOutputStream(fd)
                synchronized(lock) {
                    session?.let { it.alive = false; closeFd(it.peerFd) }
                    session = s
                }
                Thread({ tunReader(s) }, "vpnhub-tun").apply { isDaemon = true; start() }
                conn.send(T_PEER_OK, ByteArray(0))
                log("aemu-vpn: the hub holds the tun channel; waiting for the session's address")
                return true
            }
            T_SESSION_CFG -> if (pl.size >= 24) synchronized(lock) {
                session?.let { s ->
                    s.name = name16(pl, 0); s.index = le32(pl, 16); s.mtu = le32(pl, 20)
                }
            }
            T_SESSION_UP -> if (pl.size >= 28) {
                var s: Session? = null
                synchronized(lock) {
                    session?.let {
                        it.name = name16(pl, 0); it.index = le32(pl, 16); it.addr = le32(pl, 20); it.mtu = le32(pl, 24)
                        it.up = true; s = it
                    }
                }
                val up = s
                if (up != null) {
                    broadcastState()
                    linkEvent(true, up)
                    log("aemu-vpn: session ${up.name} up at ${ip(up.addr)} mtu ${up.mtu}; guest connections will be detoured into the tunnel")
                }
            }
            T_FLOW_NEW -> {
                if (pl.size < 12) return false
                val s = session
                if (s == null || !s.up) { conn.send(T_FLOW_NO, ByteArray(0)); return false }
                val f: Flow
                synchronized(lock) {
                    f = Flow(nextFlow++, le32(pl, 0), le32(pl, 4), le16(pl, 8), le16(pl, 10))
                    flows[f.id] = f
                }
                val id = ByteArray(4); putLe32(id, 0, f.id)
                conn.send(T_FLOW_OK, id)
            }
            T_FLOW_FD -> {
                if (fd == null || pl.size < 4) return false
                val f = synchronized(lock) { flows[le32(pl, 0)] }
                val s = session
                if (f == null || s == null) return false
                f.fd = fd
                Thread({ flowReader(s, f) }, "vpnhub-flow").apply { isDaemon = true; start() }
                return true
            }
            T_LINKREG -> {
                if (fd == null) return false
                val l: Listener
                synchronized(lock) {
                    l = Listener(nextListener++, fd, if (pl.size >= 4) le32(pl, 0) else 0)
                    listeners[l.id] = l
                }
                val id = ByteArray(4); putLe32(id, 0, l.id)
                conn.send(T_LINKREG_OK, id)
                session?.takeIf { it.up }?.let { linkEventTo(l, true, it) }
                return true
            }
            T_LINKUNREG -> if (pl.size >= 4) {
                val l = synchronized(lock) { listeners.remove(le32(pl, 0)) }
                if (l != null) closeFd(l.fd)
            }
        }
        return false
    }

    private fun statePayload(): ByteArray {
        val s = session
        val pl = ByteArray(33)
        pl[0] = if (s != null && s.up) 1 else 0
        if (s != null) {
            val n = s.name.toByteArray()
            System.arraycopy(n, 0, pl, 1, minOf(n.size, 15))
            putLe32(pl, 17, s.addr); putLe32(pl, 21, s.mtu)
            putLe32(pl, 29, s.index)
        }
        putLe32(pl, 25, exemptUid)
        return pl
    }

    private fun broadcastState() {
        val pl = statePayload()
        val cs = synchronized(lock) { conns.toList() }
        for (c in cs) runCatching { c.send(T_SESSION_STATE, pl) }
    }

    private fun broadcastDown(name: String) {
        val pl = ByteArray(16)
        val n = name.toByteArray()
        System.arraycopy(n, 0, pl, 0, minOf(n.size, 15))
        val cs = synchronized(lock) { conns.toList() }
        for (c in cs) runCatching { c.send(T_SESSION_DOWN, pl) }
    }

    // The channel is a datagram pair: one read is one packet the guest wrote into the tunnel.
    private fun tunReader(s: Session) {
        val buf = ByteArray(65536)
        try {
            while (s.alive) {
                val n = s.tunIn!!.read(buf)
                if (n < 0) break
                if (n == 0) continue
                // The VPN app speaks to the hub through the tunnel itself: a line "EXEMPT <uid>"
                // written into its tun descriptor registers the uid its protect() covers.
                if (n >= 7 && buf[0] == 'E'.code.toByte() && buf[1] == 'X'.code.toByte() &&
                    buf[2] == 'E'.code.toByte() && buf[3] == 'M'.code.toByte() &&
                    buf[4] == 'P'.code.toByte() && buf[5] == 'T'.code.toByte() && buf[6] == ' '.code.toByte()
                ) {
                    val line = String(buf, 7, minOf(n, 40) - 7).trim()
                    line.toIntOrNull()?.let { uid ->
                        exemptUid = uid
                        log("aemu-vpn: the VPN app registered its uid $uid as exempt from the detour")
                        broadcastState()
                    }
                    continue
                }
                // The app's explicit word that the session is over, sent before it closes
                // anything. This — not the channel running dry — is the reliable end: the
                // framework keeps its own duplicate of the tun descriptor, so on a stop the
                // channel may never run dry at all, and a hub that waits for dryness keeps a
                // dead session registered (and lets the next one stack on it).
                if (n >= 11 && buf[0] == 'S'.code.toByte() && buf[1] == 'E'.code.toByte() &&
                    buf[2] == 'S'.code.toByte() && buf[3] == 'S'.code.toByte() &&
                    buf[4] == 'I'.code.toByte() && buf[5] == 'O'.code.toByte() &&
                    buf[6] == 'N'.code.toByte() && buf[7] == '_'.code.toByte() &&
                    buf[8] == 'E'.code.toByte() && buf[9] == 'N'.code.toByte() &&
                    buf[10] == 'D'.code.toByte()
                ) {
                    log("aemu-vpn: SESSION_END from the VPN app on session ${s.name}: ending the session on its word")
                    break
                }
                s.appPkts++
                if (s.appPkts == 1L) log("aemu-vpn: first packet into the tunnel: " + describe(buf, n))
            }
        } catch (_: Throwable) {
        }
        sessionDown(s)
    }

    private fun describe(b: ByteArray, n: Int): String {
        if (n < 20 || (b[0].toInt() ushr 4) != 4) return "$n bytes, not IPv4"
        val ihl = (b[0].toInt() and 0xf) * 4
        val proto = b[9].toInt() and 0xff
        val src = ipAt(b, 12); val dst = ipAt(b, 16)
        val pname = when (proto) { 6 -> "TCP"; 17 -> "UDP"; 1 -> "ICMP"; else -> "proto $proto" }
        var ports = ""
        if ((proto == 6 || proto == 17) && n >= ihl + 4) {
            ports = ":${be16(b, ihl)} -> ${dst}:${be16(b, ihl + 2)}"
            return "IPv4/$pname $src$ports ($n bytes)"
        }
        return "IPv4/$pname $src -> $dst ($n bytes)"
    }

    // The session is over — the app said so, or the channel ran dry behind it. Order matters
    // and is fixed: the supervisors hear about it first (they clear their registries, so the
    // framework's own interface check reads "gone"), the link event goes to the listeners
    // only after that. Every step is logged: a teardown that cannot be read line by line in
    // the field is indistinguishable from the silent non-teardown this replaces.
    private fun sessionDown(s: Session) {
        val flowCount: Int
        val listenerCount: Int
        val hadExempt: Int
        synchronized(lock) {
            if (session !== s) return
            session = null
            s.alive = false
            flowCount = flows.size
            for (f in flows.values) { f.open = false; closeFd(f.fd) }
            flows.clear()
            listenerCount = listeners.size
            hadExempt = exemptUid
            exemptUid = -1
        }
        closeFd(s.peerFd)
        broadcastState()
        broadcastDown(s.name)
        log("aemu-vpn: session ${s.name} registry cleared (tun registration, $flowCount flow(s), exempt uid $hadExempt released); SESSION_DOWN broadcast")
        linkEvent(false, s)
        log("aemu-vpn: RTM_DELLINK for ${s.name} injected to $listenerCount link listener(s)")
        log("aemu-vpn: session ${s.name} over: ${s.appPkts} packets from the app, ${s.synthPkts} synthesized packets back")
    }

    // The synthesizer (M2's half of the terminator): the detoured connection's byte stream is
    // framed into TCP segments written into the tunnel, so the VPN app sees a real flow. The
    // acknowledgement number is nominal — nothing is read back and answered in M2 (that is M3),
    // so a detoured connection's reads wait for the session to end, exactly as the test page says.
    private fun flowReader(s: Session, f: Flow) {
        val inp = FileInputStream(f.fd)
        f.isn = (System.nanoTime() xor (f.id * 0x9e3779b9L)).toInt()
        f.seq = (f.isn + 1).toLong()
        writePacket(s, tcpPacket(s, f, FLAG_SYN, ByteArray(0)))
        val buf = ByteArray(65536)
        try {
            while (f.open && s.alive) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                var off = 0
                val seg = maxOf(64, s.mtu - 40)
                while (off < n) {
                    val m = minOf(n - off, seg)
                    writePacket(s, tcpPacket(s, f, FLAG_PSH or FLAG_ACK, buf.copyOfRange(off, off + m)))
                    off += m
                }
            }
        } catch (_: Throwable) {
        }
        if (f.open && s.alive) writePacket(s, tcpPacket(s, f, FLAG_FIN or FLAG_ACK, ByteArray(0)))
        f.open = false
        synchronized(lock) { flows.remove(f.id) }
        closeFd(f.fd)
    }

    private fun writePacket(s: Session, pkt: ByteArray) {
        synchronized(s.writeLock) {
            if (!s.alive) return
            try {
                s.tunOut!!.write(pkt); s.tunOut!!.flush()
                s.synthPkts++
            } catch (_: Throwable) {
            }
        }
    }

    private fun tcpPacket(s: Session, f: Flow, flags: Int, payload: ByteArray): ByteArray {
        val syn = flags and FLAG_SYN != 0
        val tcpLen = if (syn) 24 else 20
        val total = 20 + tcpLen + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        putBe16(p, 2, total)
        putBe16(p, 4, f.id and 0xffff)
        putBe16(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        putBe32(p, 12, f.dstIp)            // from the server the guest thinks it is talking to
        putBe32(p, 16, s.addr)             // to the interface address the framework gave the app
        putBe16(p, 20, f.dstPort)
        putBe16(p, 22, if (f.srcPort != 0) f.srcPort else 40000 + f.id)
        if (syn) {
            putBe32(p, 24, f.isn)
            f.seq = (f.isn + 1).toLong()
        } else {
            putBe32(p, 24, (f.seq and 0xffffffffL).toInt())
            f.seq += payload.size + if (flags and FLAG_FIN != 0) 1 else 0
        }
        putBe32(p, 28, 1)                  // nominal ack (see flowReader's note)
        p[32] = ((tcpLen / 4) shl 4).toByte()
        p[33] = flags.toByte()
        putBe16(p, 34, 65535)
        if (syn) {                         // MSS option: 1460, capped by the tunnel MTU
            p[40] = 2; p[41] = 4
            putBe16(p, 42, minOf(1460, maxOf(64, s.mtu - 40)))
        }
        System.arraycopy(payload, 0, p, 20 + tcpLen, payload.size)
        putBe16(p, 10, cksumDone(cksumAdd(p, 0, 20, 0)))
        // pseudo-header: src, dst, zero, protocol, TCP length
        var sum = cksumAdd(p, 12, 8, 0)
        sum += 6 + (total - 20)
        putBe16(p, 36, cksumDone(cksumAdd(p, 20, total - 20, sum)))
        return p
    }

    private fun cksumAdd(b: ByteArray, off: Int, len: Int, seed: Long): Long {
        var sum = seed
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
        if (i < end) sum += (b[i].toInt() and 0xff) shl 8
        return sum
    }

    private fun cksumDone(sum0: Long): Int {
        var sum = sum0
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    // ---- the link events a synthesised interface has no kernel to produce

    private fun linkEvent(up: Boolean, s: Session) {
        val ls = synchronized(lock) { listeners.values.toList() }
        for (l in ls) linkEventTo(l, up, s)
    }

    private fun linkEventTo(l: Listener, up: Boolean, s: Session) {
        // Each listener is spoken to in the dialect its consumer parses. A listener on the
        // kernel-uevent protocol gets a kernel uevent: that is the only dialect the guest's
        // netd turns into an interface removal (its binary rtnetlink parser has no
        // RTM_DELLINK branch at all — the remove action exists only in the ASCII uevent
        // parser), and the removal is what the framework's whole VPN teardown waits for.
        // Route-protocol listeners keep the binary rtnetlink message they had.
        val msg = if (l.proto == PROTO_UEVENT) ueventMessage(up, s) else linkMessage(up, s)
        try {
            synchronized(l.wlock) { l.out.write(msg); l.out.flush() }
        } catch (_: Throwable) {
            // A listener that will not take the event is gone; the supervisor's own
            // LINKUNREG may never arrive, so drop it here too.
            synchronized(lock) { if (listeners[l.id] === l) listeners.remove(l.id) }
            closeFd(l.fd)
        }
    }

    private fun linkMessage(up: Boolean, s: Session): ByteArray {
        val name = s.name.toByteArray()
        val attrLen = 4 + name.size + 1
        val total = 16 + 16 + ((attrLen + 3) and 3.inv())
        val m = ByteArray(total)
        putLe32(m, 0, total)
        putLe16(m, 4, if (up) RTM_NEWLINK else RTM_DELLINK)
        // ifinfomsg at 16: family, pad, type, index, flags, change
        m[16] = 0
        putLe16(m, 18, 1)                  // ARPHRD_ETHER
        putLe32(m, 20, s.index)
        putLe32(m, 24, if (up) 0x180d1 else 0)   // the SIOCGIFFLAGS set plus IFF_LOWER_UP: a live channel has carrier, as a kernel NEWLINK reports
        putLe32(m, 28, -1)
        putLe16(m, 32, attrLen)
        putLe16(m, 34, IFLA_IFNAME)
        System.arraycopy(name, 0, m, 36, name.size)
        return m
    }

    private var ueventSeq = 1000

    // A kernel uevent for a virtual net device, byte for byte the shape the kernel's
    // kobject_uevent + netdev_uevent produce: "<action>@<devpath>", then NUL-separated
    // ACTION / DEVPATH / SUBSYSTEM / INTERFACE / IFINDEX / SEQNUM pairs.
    private fun ueventMessage(up: Boolean, s: Session): ByteArray {
        val action = if (up) "add" else "remove"
        val path = "/devices/virtual/net/${s.name}"
        val seq = ++ueventSeq
        val text = "$action@$path\u0000ACTION=$action\u0000DEVPATH=$path\u0000SUBSYSTEM=net\u0000" +
            "INTERFACE=${s.name}\u0000IFINDEX=${s.index}\u0000SEQNUM=$seq\u0000"
        return text.toByteArray(Charsets.US_ASCII)
    }

    // ---- small helpers

    private fun readN(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) return null
            got += r
        }
        return b
    }

    private fun closeFd(fd: FileDescriptor?) {
        if (fd == null) return
        runCatching { Os.close(fd) }
    }

    private companion object {
    private fun name16(pl: ByteArray, off: Int): String {
        var end = off
        while (end < off + 16 && pl[end] != 0.toByte()) end++
        return String(pl, off, end - off)
    }

    private fun ip(v: Int) = "${v ushr 24}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"
    private fun ipAt(b: ByteArray, off: Int) = "${b[off].toInt() and 0xff}.${b[off + 1].toInt() and 0xff}.${b[off + 2].toInt() and 0xff}.${b[off + 3].toInt() and 0xff}"

    private fun le32(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    private fun be16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte(); b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    private fun putLe16(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte() }
    private fun putBe16(b: ByteArray, off: Int, v: Int) { b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte() }
    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

        const val T_HELLO = 1
        const val T_SESSION_STATE = 2
        const val T_TUN_PEER = 3
        const val T_PEER_OK = 4
        const val T_SESSION_CFG = 5
        const val T_SESSION_UP = 6
        const val T_SESSION_DOWN = 7
        const val T_FLOW_NEW = 8
        const val T_FLOW_OK = 9
        const val T_FLOW_NO = 10
        const val T_FLOW_FD = 11
        const val T_LINKREG = 12
        const val T_LINKREG_OK = 13
        const val T_LINKUNREG = 14
        const val RTM_NEWLINK = 16
        const val RTM_DELLINK = 17
        const val PROTO_UEVENT = 15   // NETLINK_KOBJECT_UEVENT
        const val IFLA_IFNAME = 3
        const val FLAG_SYN = 0x02
        const val FLAG_FIN = 0x01
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
    }
}
