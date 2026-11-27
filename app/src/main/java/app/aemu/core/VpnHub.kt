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
 *    user-space TCP endpoint (VpnTcp.kt, M3) terminates each one: it frames the guest's byte
 *    stream into segments for the tunnel, completes the handshake with the real server
 *    through the VPN app's forwarding, and answers the inbound segments back into the guest
 *    stream — a detoured connection now gets its replies. Termination state lives only
 *    here, keyed by session generation; the supervisors hold none of it. UDP is not
 *    detoured and DNS stays on the host-side proxy (the test page says so).
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
    private val flowsByTuple = HashMap<Long, Flow>()
    private val embers = ArrayList<Ember>()
    private var nextFlow = 1
    @Volatile private var timerAlive = false
    private var nextListener = 1
    /** VPN 137 measurement: sessions since boot, numbered in registration order */
    private var sessionSeq = 0
    @Volatile private var session: Session? = null
    @Volatile private var exemptUid = -1

    fun ensure() {
        server.start(log)
        synchronized(lock) {
            if (!timerAlive) {
                timerAlive = true
                Thread({ timerLoop() }, "vpnhub-timer").apply { isDaemon = true; start() }
            }
        }
    }

    fun stop() {
        timerAlive = false
        server.stop()
        session?.let { s ->
            val fc: Int; val lc: Int
            synchronized(lock) { fc = flows.size; lc = listeners.size }
            step(s, "hub-stop flows=$fc listeners=$lc")
        }
        synchronized(lock) {
            session?.let { it.alive = false; closeFd(it.peerFd) }
            session = null
            for (f in flows.values) { f.open = false; f.conn?.abort("session-down"); closeFd(f.fd) }
            flows.clear()
            flowsByTuple.clear()
            embers.clear()
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
        /** VPN 137 measurement: this session's sequence number since boot (S1, S2, ...) */
        @Volatile var seq = 0
        @Volatile var index = 0
        @Volatile var mtu = 1500
        @Volatile var addr = 0
        @Volatile var up = false
        @Volatile var alive = true
        /** The supervisor connection that delivered this session's tun channel (lch1,
         * lifecycle-hardening-spec section 4): the session's life is bound to it. */
        @Volatile var owner: Conn? = null
        var peerFd: FileDescriptor? = null
        var tunIn: InputStream? = null
        var tunOut: OutputStream? = null
        val writeLock = Object()
        @Volatile var appPkts = 0L
        @Volatile var synthPkts = 0L
        /** M3 session totals: payload bytes each way, folded in as flows are reaped. */
        @Volatile var upBytes = 0L
        @Volatile var downBytes = 0L
        /** Inbound segments that matched no live flow of this session (dead generations). */
        @Volatile var staleDrops = 0L
        /** Inbound segments dropped for a bad checksum, folded in as flows are reaped. */
        @Volatile var badDrops = 0L
        @Volatile var writeFails = 0
    }

    private class Listener(val id: Int, val fd: FileDescriptor, val proto: Int) {
        val out: OutputStream = FileOutputStream(fd)
        val wlock = Object()
    }

    private class Flow(val id: Int, val uid: Int, val dstIp: Int, val dstPort: Int, val srcPort: Int,
                       val session: Session, val tupleKey: Long) {
        var fd: FileDescriptor? = null
        @Volatile var open = true
        var conn: VpnTcpConn? = null
        /** Byte counters already folded into the session totals (reap/sessionDown once). */
        var counted = false
    }

    /** A closed flow's afterglow (design section 2): the tuple and the closing sequence
     * values, kept for 30 s so a retransmitted final segment still gets its ACK. Not a live
     * flow: it holds no descriptor and is never counted in the flow totals. */
    private class Ember(val key: Long, val gen: Int, val serverIp: Int, val serverPort: Int,
                        val srcPort: Int, val seq: Long, val rcvNxt: Long, val expiresAt: Long)

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
            // lch1 (lifecycle-hardening-spec section 4): a session is owned by the
            // supervisor connection that delivered its tun channel, and that connection
            // closing is the one death signal that fires in every variant — the owning
            // process died and its socket died with it, whatever else survived (the agent
            // that would have written SESSION_END, the framework duplicate that keeps the
            // channel from running dry). Declare the session dead through the ordinary
            // teardown instead of going on advertising it as up and feeding it new flows.
            val orphaned = synchronized(lock) { session?.takeIf { it.owner === conn } }
            if (orphaned != null) {
                log("aemu-vpn: the supervisor connection owning session ${orphaned.name} closed; declaring the session dead")
                step(orphaned, "owner-connection-lost (session declared dead)")
                sessionDown(orphaned)
            }
        }
    }

    /** Returns true when the frame's descriptor (if any) was taken over. */
    private fun handle(conn: Conn, type: Int, pl: ByteArray, fd: FileDescriptor?): Boolean {
        when (type) {
            T_HELLO -> {
                conn.send(T_SESSION_STATE, statePayload())
                val s = session
                step(s, "hello state-query -> up=${if (s != null && s.up) 1 else 0} name=${s?.name ?: ""} addr=${s?.let { ip(it.addr) } ?: "-"} mtu=${s?.mtu ?: 0} exempt=$exemptUid")
            }
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
                    s.seq = ++sessionSeq
                    s.owner = conn
                    session = s
                }
                step(s, "tun-channel-acquired" + if (stale != null) " replaces S${stale.seq} (stale session torn down first)" else "")
                Thread({ tunReader(s) }, "vpnhub-tun").apply { isDaemon = true; start() }
                conn.send(T_PEER_OK, ByteArray(0))
                log("aemu-vpn: the hub holds the tun channel; waiting for the session's address")
                return true
            }
            T_SESSION_CFG -> if (pl.size >= 24) {
                val s = synchronized(lock) {
                    session?.also { it.name = name16(pl, 0); it.index = le32(pl, 16); it.mtu = le32(pl, 20) }
                }
                if (s != null) step(s, "session-cfg name=${s.name} index=${s.index} mtu=${s.mtu}")
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
                    val fc: Int; val lc: Int; val cc: Int
                    synchronized(lock) { fc = flows.size; lc = listeners.size; cc = conns.size }
                    step(up, "session-up name=${up.name} index=${up.index} addr=${ip(up.addr)} mtu=${up.mtu} flows=$fc listeners=$lc state-broadcast-to=$cc")
                }
            }
            T_FLOW_NEW -> {
                if (pl.size < 12) return false
                // lch1 (lifecycle-hardening-spec section 2): the live-session decision and
                // the registration are one lock block — the same lock sessionDown clears
                // under. Decided outside it, a flow could register into the maps an instant
                // after the teardown emptied them: an orphan of a dead session that no
                // later teardown would ever see, cleared only by the timer's aging.
                var fNew: Flow? = null
                var sLive: Session? = null
                var fcount = 0
                synchronized(lock) {
                    val live = session
                    if (live != null && live.up && live.alive) {
                        sLive = live
                        val id = nextFlow++
                        // Source ports come from the hub's own cursor and are never reused
                        // across sessions (design section 2): a guest that bound its own port
                        // keeps it, everyone else gets 40000 + flow id — and flow ids never
                        // repeat in the hub's lifetime, so neither does the port.
                        val rawPort = le16(pl, 10)
                        val effPort = if (rawPort != 0) rawPort else 40000 + id
                        val nf = Flow(id, le32(pl, 0), le32(pl, 4), le16(pl, 8), effPort, live,
                            tupleKey(le32(pl, 4), le16(pl, 8), effPort))
                        flows[nf.id] = nf
                        flowsByTuple[nf.tupleKey] = nf
                        fNew = nf
                        fcount = flows.size
                    }
                }
                val f = fNew
                if (f == null) {
                    step(session, "flow-rejected uid=${le32(pl, 0)} dst=${ip(le32(pl, 4))}:${le16(pl, 8)} reason=no-live-session")
                    conn.send(T_FLOW_NO, ByteArray(0)); return false
                }
                val s = sLive
                step(s, "flow-new id=${f.id} uid=${f.uid} dst=${ip(f.dstIp)}:${f.dstPort} srcport=${f.srcPort} flows=$fcount")
                val id = ByteArray(4); putLe32(id, 0, f.id)
                conn.send(T_FLOW_OK, id)
            }
            T_FLOW_FD -> {
                if (fd == null || pl.size < 4) return false
                val f = synchronized(lock) { flows[le32(pl, 0)] }
                val s = session
                if (f == null || s == null) return false
                step(s, "flow-fd id=${f.id}")
                f.fd = fd
                val tc = VpnTcpConn(f.id, s.seq, f.dstIp, f.dstPort, s.addr, f.srcPort, s.mtu,
                    { pkt -> writePacket(s, pkt) },
                    { log("aemu-vpn: flow ${f.id} established dst=${ip(f.dstIp)}:${f.dstPort}") },
                    log)
                f.conn = tc
                tc.open(System.currentTimeMillis())
                Thread({ flowReader(s, f, tc) }, "vpnhub-flow").apply { isDaemon = true; start() }
                Thread({ flowWriter(s, f, tc) }, "vpnhub-flow-w").apply { isDaemon = true; start() }
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
                step(session, "listener-registered id=${l.id} proto=${l.proto} listeners=${synchronized(lock) { listeners.size }}")
                session?.takeIf { it.up }?.let { linkEventTo(l, true, it) }
                return true
            }
            T_LINKUNREG -> if (pl.size >= 4) {
                val lid = le32(pl, 0)
                val l = synchronized(lock) { listeners.remove(lid) }
                if (l != null) closeFd(l.fd)
                step(session, "listener-unregistered id=$lid found=${l != null} listeners=${synchronized(lock) { listeners.size }}")
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

    // ---- VPN 137 measurement: one line per lifecycle step, each opening with the
    // session's sequence number, so two sessions' step sequences can be diffed line
    // by line after a field run and the first divergent step read off directly.
    // Record-only: nothing here feeds back into behaviour, and the volume is capped
    // by construction — lifecycle and netlink events only, never per-packet.
    private fun step(s: Session?, msg: String) {
        log("aemu-137: S${s?.seq?.toString() ?: "-"} $msg")
    }

    // The channel is a datagram pair: one read is one packet the guest wrote into the tunnel.
    private fun tunReader(s: Session) {
        val buf = ByteArray(65536)
        var endedByWord = false
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
                        step(s, "exempt-registered uid=$uid")
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
                    step(s, "session-end-word")
                    endedByWord = true
                    break
                }
                s.appPkts++
                if (s.appPkts == 1L) log("aemu-vpn: first packet into the tunnel: " + describe(buf, n))
                // M3: a real IP packet read back out of the tunnel is the return half of
                // a detoured connection (or a leftover of a dead one). The control lines
                // were handled above; IP packets are dispatched, never silently dropped.
                if (n >= 20 && (buf[0].toInt() ushr 4) == 4) dispatchInbound(s, buf, n)
            }
        } catch (_: Throwable) {
        }
        if (!endedByWord) step(s, "tun-channel-closed (ran dry or read failed)")
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

    /** Routes one inbound IP packet to its flow's terminator. The dispatch key is the
     * reverse of the outbound tuple (design section 4): the packet's source is the server,
     * its destination port is the flow's source port. A packet that matches no live flow
     * of this session is either answered by an ember (same generation only) or counted as
     * a stale-generation drop — the design's "a later session inherits nothing" evidence. */
    private fun dispatchInbound(s: Session, b: ByteArray, n: Int) {
        val ihl = (b[0].toInt() and 0x0f) * 4
        if (ihl < 20 || n < ihl + 20) return
        if ((b[9].toInt() and 0xff) != 6) return   // TCP only; UDP/ICMP stay counted, not detoured (design section 3)
        val key = tupleKey(be32(b, 12), be16(b, ihl), be16(b, ihl + 2))
        val f = synchronized(lock) { flowsByTuple[key] }
        if (f != null && f.session === s) {
            f.conn?.onPacket(b, n)
            return
        }
        val emb = synchronized(lock) { embers.firstOrNull { it.key == key } }
        if (emb != null) {
            if (emb.gen == s.seq) {
                writePacket(s, buildTcpPacket(s.addr, emb.srcPort, emb.serverIp, emb.serverPort,
                    emb.seq, emb.rcvNxt, 0x10, 0, ByteArray(0), false, s.mtu, (key and 0xffff).toInt()))
            } else {
                s.staleDrops++
            }
            return
        }
        s.staleDrops++
    }

    private fun tupleKey(serverIp: Int, serverPort: Int, srcPort: Int): Long =
        ((serverIp.toLong() and 0xffffffffL) shl 32) or
            ((serverPort.toLong() and 0xffff) shl 16) or (srcPort.toLong() and 0xffff)

    private fun be32(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

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
            for (f in flows.values) {
                f.open = false
                f.conn?.let { c ->
                    c.abort("session-down")
                    if (!f.counted) {
                        f.counted = true
                        s.upBytes += c.upBytes; s.downBytes += c.downBytes; s.badDrops += c.badCksum
                    }
                }
                closeFd(f.fd)
            }
            flows.clear()
            flowsByTuple.clear()
            embers.clear()
            listenerCount = listeners.size
            hadExempt = exemptUid
            exemptUid = -1
        }
        closeFd(s.peerFd)
        broadcastState()
        broadcastDown(s.name)
        log("aemu-vpn: session ${s.name} registry cleared (tun registration, $flowCount flow(s), exempt uid $hadExempt released); SESSION_DOWN broadcast")
        step(s, "registry-cleared flows=$flowCount listeners=$listenerCount exempt=$hadExempt down-broadcast-to=${synchronized(lock) { conns.size }}")
        linkEvent(false, s)
        log("aemu-vpn: RTM_DELLINK for ${s.name} injected to $listenerCount link listener(s)")
        log("aemu-vpn: session ${s.name} over: ${s.appPkts} packets from the app, ${s.synthPkts} synthesized packets back, up ${s.upBytes} bytes, down ${s.downBytes} bytes, stale-gen drops ${s.staleDrops}, bad packets dropped ${s.badDrops}")
        step(s, "session-over appPkts=${s.appPkts} synthPkts=${s.synthPkts} upBytes=${s.upBytes} downBytes=${s.downBytes} staleDrops=${s.staleDrops}")
    }

    // The guest-stream half of a detoured connection (M3): bytes the guest wrote are fed
    // to the flow's terminator, which frames them into segments for the tunnel. A read EOF
    // is the guest's half-close — the terminator queues its FIN behind the data and the
    // flow lives on until the server's side finishes too. The descriptor is closed by the
    // reaper or the delivery writer, never here, so the return half can still arrive.
    private fun flowReader(s: Session, f: Flow, conn: VpnTcpConn) {
        val inp = FileInputStream(f.fd)
        val buf = ByteArray(65536)
        try {
            var firstBytes = true
            while (f.open && s.alive) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (firstBytes) {
                    firstBytes = false
                    log("aemu-vpn: flow ${f.id} first guest bytes: $n")
                }
                if (!conn.guestBytes(buf, 0, n)) break
            }
        } catch (_: Throwable) {
        }
        conn.guestEof()
        step(s, "flow-guest-eof id=${f.id}")
    }

    // The delivery half: drains the terminator's deliverables into the guest descriptor,
    // on its own thread, so the tunnel reader never blocks on a slow guest. An Eof
    // deliverable becomes a write-side shutdown of the descriptor: the guest reads its
    // EOF while its writes can still be answered (the design's half-close).
    private fun flowWriter(s: Session, f: Flow, conn: VpnTcpConn) {
        val fd = f.fd ?: return
        val out = FileOutputStream(fd)
        while (true) {
            when (val d = conn.nextDeliverable()) {
                is TcpDeliverable.Data -> {
                    try {
                        out.write(d.bytes); out.flush()
                    } catch (_: Throwable) {
                        conn.guestGone()
                        break
                    }
                }
                is TcpDeliverable.Eof -> {
                    runCatching { Os.shutdown(fd, android.system.OsConstants.SHUT_WR) }
                    return
                }
                is TcpDeliverable.Dead -> break
            }
        }
        closeFd(fd)
    }

    /** The terminator's clock: ticks every live flow (retransmits, connect timeouts),
     * reaps the finished ones, and retires expired embers. Runs for the hub's lifetime;
     * a tick only ever touches flows in the live registry, so nothing here can act
     * inside a later session. */
    private fun timerLoop() {
        while (timerAlive) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
            val now = System.currentTimeMillis()
            val fs = synchronized(lock) { flows.values.toList() }
            for (f in fs) {
                val c = f.conn ?: continue
                c.tick(now)
                if (c.dead || c.fullyClosed()) reapFlow(f)
            }
            synchronized(lock) {
                val it = embers.iterator()
                while (it.hasNext()) { if (it.next().expiresAt <= now) it.remove() }
            }
        }
    }

    /** Takes a finished flow out of every registry, folds its counters into the session
     * totals, logs its closing line, and — when the close was a clean active one — leaves
     * the design's 30-second ember behind for late retransmits of the final segment. */
    private fun reapFlow(f: Flow) {
        val s = f.session
        val c = f.conn ?: return
        if (!c.dead) c.markComplete()
        synchronized(lock) {
            if (flows[f.id] !== f) return
            flows.remove(f.id)
            if (flowsByTuple[f.tupleKey] === f) flowsByTuple.remove(f.tupleKey)
            if (!f.counted) {
                f.counted = true
                s.upBytes += c.upBytes; s.downBytes += c.downBytes; s.badDrops += c.badCksum
            }
            if (c.emberRcvNxt() >= 0) {
                embers.add(Ember(f.tupleKey, s.seq, f.dstIp, f.dstPort, f.srcPort,
                    c.emberSeq(), c.emberRcvNxt(), System.currentTimeMillis() + 30_000))
            }
        }
        f.open = false
        closeFd(f.fd)
        log("aemu-vpn: flow ${f.id} closed reason=${c.closeReason} up=${c.upBytes} down=${c.downBytes}")
        step(s, "flow-closed id=${f.id} reason=${c.closeReason} flows=${synchronized(lock) { flows.size }}")
    }

    /** Writes one packet into the tunnel; returns false when the write failed.
     * Consecutive failures are counted, and at the design's threshold the session is
     * treated as dead — its channel is closed so the reader runs the ordinary teardown —
     * instead of the hub silently believing in a session the app has stopped reading. */
    private fun writePacket(s: Session, pkt: ByteArray): Boolean {
        synchronized(s.writeLock) {
            if (!s.alive) return false
            try {
                s.tunOut!!.write(pkt); s.tunOut!!.flush()
                s.synthPkts++
                s.writeFails = 0
                return true
            } catch (_: Throwable) {
                s.writeFails++
                if (s.writeFails >= 16 && s.alive) {
                    log("aemu-vpn: session ${s.name}: 16 consecutive tunnel writes failed; treating the session as dead")
                    step(s, "tun-write-failed x${s.writeFails} (session treated as dead)")
                    s.alive = false
                    closeFd(s.peerFd)
                }
                return false
            }
        }
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
        val typeName = if (l.proto == PROTO_UEVENT) (if (up) "uevent-add" else "uevent-remove")
            else (if (up) "RTM_NEWLINK" else "RTM_DELLINK")
        try {
            synchronized(l.wlock) { l.out.write(msg); l.out.flush() }
            step(s, "link-event type=$typeName listener=${l.id} proto=${l.proto} name=${s.name} index=${s.index}")
        } catch (_: Throwable) {
            // A listener that will not take the event is gone; the supervisor's own
            // LINKUNREG may never arrive, so drop it here too.
            synchronized(lock) { if (listeners[l.id] === l) listeners.remove(l.id) }
            closeFd(l.fd)
            step(s, "link-event-failed type=$typeName listener=${l.id} proto=${l.proto} (listener dropped)")
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
    }
}
