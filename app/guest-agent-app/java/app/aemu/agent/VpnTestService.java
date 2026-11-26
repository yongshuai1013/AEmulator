package app.aemu.agent;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The VPN test service, M3: a real forwarder. It asks the framework for a tunnel exactly
 * like a shipping VPN app (Builder, one address, a default route, MTU), and then does the
 * job a VPN app's forwarding core does: segments the hub synthesizes arrive as packets
 * (session address -> real server), each new flow becomes a real socket to that server,
 * and whatever the server answers is segmented back into the tunnel. The hub terminates
 * the guest side of every connection (VpnTcp.kt host-side); this service is the far end
 * of the tunnel it talks to. UDP is not forwarded — it never reaches the tunnel in the
 * first place (the page says so) — and DNS stays on the host-side proxy.
 */
public final class VpnTestService extends VpnService {
    static final String ACTION_STOP = "app.aemu.agent.VPN_TEST_STOP";

    // The forwarding path logs under its own tag: before the M3 fix this whole path had
    // no Log calls at all, which is why a dead reader or a parked connect was invisible
    // from outside the guest (field #122: the service never dialed and nothing said so).
    private static final String TAG = "aemu-vpn-fwd";

    private static final int FLAG_FIN = 0x01;
    private static final int FLAG_SYN = 0x02;
    private static final int FLAG_RST = 0x04;
    private static final int FLAG_PSH = 0x08;
    private static final int FLAG_ACK = 0x10;
    private static final int MAX_WRITE_Q = 256 * 1024;

    private ParcelFileDescriptor tunnel;
    private Thread reader;
    private FileInputStream readerIn;
    private FileOutputStream tunOut;
    private final Object tunWriteLock = new Object();
    private volatile boolean running = false;

    private final Map<String, FwdConn> conns = new ConcurrentHashMap<String, FwdConn>();
    private final AtomicInteger connSeq = new AtomicInteger();
    private int ipIdCounter = 0;

    /** One translated connection: the hub's flow, this service's real socket, and the
     * small amount of TCP state the far end of the tunnel has to keep. */
    private final class FwdConn {
        final int no;
        final String key;
        final int clientIp, clientPort;      // the hub side: session address + flow source port
        final int serverIp, serverPort;      // the real server the guest thinks it called
        final String dstText;
        final Object lock = new Object();
        long rcvNxt;                          // next sequence expected from the hub
        long isn;
        long sndNxt;                          // next sequence toward the hub
        long hubAck;                          // latest ack from the hub (our sndUna)
        int hubWnd = 65535;
        int hubScale = 0;
        boolean clientFin = false;            // the hub sent its FIN (guest half-closed)
        boolean finSent = false;
        long finSeq = -1;
        boolean sockEof = false;
        volatile boolean closed = false;
        Socket socket;
        OutputStream sockOut;
        byte[] synAckPacket;
        final ArrayDeque<byte[]> writeQ = new ArrayDeque<byte[]>();
        long writeQBytes = 0;
        final ArrayDeque<Unacked> unacked = new ArrayDeque<Unacked>();
        long lastAckProgress = System.currentTimeMillis();
        volatile long upBytes = 0;            // payload toward the server (written to the socket)
        volatile long downBytes = 0;          // payload toward the guest (written into the tunnel)

        FwdConn(int no, String key, int clientIp, int clientPort, int serverIp, int serverPort) {
            this.no = no;
            this.key = key;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.serverIp = serverIp;
            this.serverPort = serverPort;
            this.dstText = ipText(serverIp) + ":" + serverPort;
        }
    }

    private static final class Unacked {
        final long endSeq;
        final byte[] packet;
        long lastSent;
        Unacked(long endSeq, byte[] packet) {
            this.endSeq = endSeq;
            this.packet = packet;
            this.lastSent = System.currentTimeMillis();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            VpnTestState.set("已停止（使用者）");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel != null) {
            VpnTestState.set("已在運行——會話已建立");
            return START_STICKY;
        }
        VpnTestState.reset();
        VpnTestState.set("establish() 進行中…");
        try {
            Builder b = new Builder();
            b.setSession("AEmu VPN 測試");
            b.addAddress("10.99.0.2", 32);
            b.addRoute("0.0.0.0", 0);
            b.setMtu(1500);
            ParcelFileDescriptor p = b.establish();
            if (p == null) {
                VpnTestState.set("establish() 回傳 null——框架拒絕（同意未給、或已有其他 VPN 在席）");
                stopSelf();
                return START_NOT_STICKY;
            }
            tunnel = p;
            VpnTestState.onEstablished();
            Log.i(TAG, "establish() ok: session 10.99.0.2/32, route 0.0.0.0/0, mtu 1500");
            tunOut = new FileOutputStream(p.getFileDescriptor());
            running = true;
            // Register this app's uid with the hub, through the tunnel itself: the hub exempts
            // it from the detour, which is what VpnService.protect() promises the app's own
            // sockets (the translator's sockets below among them). Written before the reader
            // starts so it is the channel's first content.
            try {
                writeRaw(("EXEMPT " + android.os.Process.myUid() + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) { }
            reader = new Thread(new Runnable() {
                @Override public void run() { readLoop(); }
            }, "aemu-vpn-reader");
            reader.setDaemon(true);
            reader.start();
            Log.i(TAG, "reader thread started; EXEMPT uid=" + android.os.Process.myUid()
                    + " written as the channel's first content");
        } catch (Throwable t) {
            VpnTestState.set("establish() 失敗：" + t);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void readLoop() {
        byte[] buf = new byte[32767];
        FileInputStream in = new FileInputStream(tunnel.getFileDescriptor());
        readerIn = in;
        Log.i(TAG, "reader loop entered");
        boolean firstPacket = true;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("Try again") || m.contains("Resource temporarily unavailable")) {
                        Thread.sleep(50);   // the framework opens tun non-blocking; nothing yet
                        continue;
                    }
                    // Any other read error used to end the reader silently (the state page
                    // got one line, logcat got none) — the M3 field failure mode: the
                    // service looked alive while its only input was dead. Now: log it and
                    // keep reading; only a real shutdown (running cleared / interrupt) or
                    // EOF below ends the loop, and every exit says why.
                    if (!running || Thread.currentThread().isInterrupted()) {
                        Log.i(TAG, "reader exiting: read error during shutdown: " + e);
                        return;
                    }
                    Log.w(TAG, "tun read error (continuing): " + e);
                    VpnTestState.set("tun 讀取錯誤（續讀中）：" + e);
                    Thread.sleep(100);
                    continue;
                }
                if (n > 0) {
                    if (firstPacket) {
                        firstPacket = false;
                        Log.i(TAG, "first tun packet: " + n + " bytes");
                    }
                    VpnTestState.addPacket(buf, n);
                    if (running && n >= 20 && (buf[0] >> 4) == 4 && (buf[9] & 0xff) == 6) {
                        forwardPacket(buf, n);
                    }
                } else if (n < 0) {
                    Log.i(TAG, "reader exiting: tun read EOF");
                    VpnTestState.set("tun 已關閉（read 回 EOF）");
                    return;
                } else {
                    Thread.sleep(20);
                }
            }
            Log.i(TAG, "reader exiting: interrupted (shutdown)");
        } catch (InterruptedException e) {
            Log.i(TAG, "reader exiting: interrupted (shutdown)");
        }
    }

    // ---- the translator: hub segments in, real sockets out, answers back in

    private void forwardPacket(byte[] b, int n) {
        int ihl = (b[0] & 0xf) * 4;
        if (ihl < 20 || n < ihl + 20) return;
        int total = be16(b, 2);
        if (total >= ihl + 20 && total < n) n = total;
        int t = ihl;
        int srcIp = be32(b, 12), dstIp = be32(b, 16);
        int srcPort = be16(b, t), dstPort = be16(b, t + 2);
        long seq = be32u(b, t + 4), ack = be32u(b, t + 8);
        int doff = ((b[t + 12] >> 4) & 0xf) * 4;
        if (doff < 20 || ihl + doff > n) return;
        int flags = b[t + 13] & 0xff;
        int wnd = be16(b, t + 14);
        int pOff = ihl + doff, pLen = n - pOff;
        String key = srcIp + ":" + srcPort + ">" + dstIp + ":" + dstPort;
        FwdConn c = conns.get(key);
        if (c == null) {
            if ((flags & FLAG_SYN) != 0 && (flags & FLAG_ACK) == 0) {
                startConn(key, srcIp, srcPort, dstIp, dstPort, seq, b, t, doff);
            }
            return;   // anything else for an unknown flow is a leftover: drop it
        }
        handleSegment(c, seq, ack, flags, wnd, b, pOff, pLen);
    }

    private void startConn(final String key, int clientIp, int clientPort, int serverIp, int serverPort,
                           long synSeq, byte[] synPkt, int tcpOff, int doff) {
        final FwdConn c = new FwdConn(connSeq.incrementAndGet(), key, clientIp, clientPort, serverIp, serverPort);
        c.rcvNxt = synSeq + 1;
        c.isn = (System.nanoTime() >>> 8) & 0xffffffffL;
        c.sndNxt = c.isn;
        c.hubAck = c.isn;
        // The hub's window scale rides in its SYN options; without it our send window
        // would be read at face value and stall at 64 KB like anyone else's.
        for (int i = tcpOff + 20; i + 1 < tcpOff + doff; ) {
            int kind = synPkt[i] & 0xff;
            if (kind == 0) break;
            if (kind == 1) { i++; continue; }
            int len = synPkt[i + 1] & 0xff;
            if (len < 2 || i + len > tcpOff + doff) break;
            if (kind == 3 && len == 3) c.hubScale = synPkt[i + 2] & 0xff;
            i += len;
        }
        conns.put(key, c);
        Log.i(TAG, "conn " + c.no + " startConn: SYN from hub, dst=" + c.dstText
                + " srcport=" + clientPort);
        VpnTestState.flowOpened(c.no, c.dstText);
        Thread th = new Thread(new Runnable() {
            @Override public void run() { connSocketLoop(c); }
        }, "aemu-vpn-fwd-" + c.no);
        th.setDaemon(true);
        th.start();
    }

    /** Connects the real socket, answers the SYN, then pumps server bytes back into the
     * tunnel until both sides are done. Runs on the connection's own thread. */
    private void connSocketLoop(final FwdConn c) {
        Socket s = new Socket();
        try {
            // No protect() here, deliberately. The detour exemption in this architecture
            // is the design's uid registration (the EXEMPT line at session start -> hub ->
            // the supervisor passes this uid's connects at its trap point, before any
            // guest routing exists); protect()'s fwmark has no consumer inside the guest,
            // and the call sat on the critical path unlogged — in the #122 field it is one
            // of the two places the connect could have parked without a trace. The uid
            // exemption is what carries this socket out, and this line says so per conn.
            Log.i(TAG, "conn " + c.no + " connecting to " + c.dstText
                    + " via uid exemption (no protect())");
            byte[] ipb = new byte[] { (byte) (c.serverIp >>> 24), (byte) (c.serverIp >>> 16),
                    (byte) (c.serverIp >>> 8), (byte) c.serverIp };
            s.connect(new InetSocketAddress(InetAddress.getByAddress(ipb), c.serverPort), 15000);
            Log.i(TAG, "conn " + c.no + " connected to " + c.dstText);
        } catch (Throwable e) {
            // Refused or unreachable: the hub's optimistic connect gets its answer as a RST.
            Log.w(TAG, "conn " + c.no + " connect to " + c.dstText + " failed: " + e);
            writeRaw(buildPacket(c, 0, c.rcvNxt, FLAG_RST | FLAG_ACK, null, 0, 0));
            closeConn(c, "connect-failed");
            return;
        }
        synchronized (c.lock) {
            if (c.closed) {
                try { s.close(); } catch (Throwable ignored) { }
                return;
            }
            c.socket = s;
            try { c.sockOut = s.getOutputStream(); } catch (IOException e) { closeConnLocked(c, "socket-error"); return; }
            c.sndNxt = c.isn + 1;
            c.synAckPacket = buildPacket(c, c.isn, c.rcvNxt, FLAG_SYN | FLAG_ACK, null, 0, 0);
            c.unacked.add(new Unacked(c.isn + 1, c.synAckPacket));
        }
        writeRaw(c.synAckPacket);
        Log.i(TAG, "conn " + c.no + " SYN-ACK written back to the hub");
        VpnTestState.flowState(c.no, "ESTABLISHED");
        Thread pump = new Thread(new Runnable() {
            @Override public void run() { connPumpLoop(c); }
        }, "aemu-vpn-pump-" + c.no);
        pump.setDaemon(true);
        pump.start();

        boolean sockDone = false;
        byte[] rb = new byte[16384];
        try { s.setSoTimeout(250); } catch (Throwable ignored) { }
        while (!c.closed) {
            if (sockDone) {
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                tickRetransmit(c);
                checkClose(c);
                continue;
            }
            final int r;
            try {
                InputStream sin = s.getInputStream();
                r = sin.read(rb);
            } catch (SocketTimeoutException te) {
                tickRetransmit(c);
                continue;
            } catch (IOException e) {
                if (!c.closed) {
                    writeRaw(buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_RST | FLAG_ACK, null, 0, 0));
                    closeConn(c, "socket-error");
                }
                return;
            }
            if (r > 0) {
                if (!sendToHub(c, rb, r)) return;   // the guest side is gone
            } else if (r < 0) {
                // The server is done sending: our FIN goes out behind everything before it.
                synchronized (c.lock) {
                    c.sockEof = true;
                    if (!c.finSent && !c.closed) {
                        c.finSent = true;
                        c.finSeq = c.sndNxt;
                        byte[] pkt = buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_FIN | FLAG_ACK, null, 0, 0);
                        c.sndNxt++;
                        c.unacked.add(new Unacked(c.sndNxt, pkt));
                        writeRaw(pkt);
                    }
                }
                sockDone = true;
                checkClose(c);
            }
        }
    }

    /** Writes hub-bound payload, segmented and window-paced; false when the connection died. */
    private boolean sendToHub(FwdConn c, byte[] data, int len) {
        int off = 0;
        while (off < len) {
            final byte[] pkt;
            synchronized (c.lock) {
                while (!c.closed && c.hubWnd - (c.sndNxt - c.hubAck) <= 0) {
                    try { c.lock.wait(200); } catch (InterruptedException e) { return false; }
                }
                if (c.closed) return false;
                long space = c.hubWnd - (c.sndNxt - c.hubAck);
                int m = (int) Math.min(Math.min(1460, len - off), space);
                pkt = buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_PSH | FLAG_ACK, data, off, m);
                c.sndNxt += m;
                c.downBytes += m;
                c.unacked.add(new Unacked(c.sndNxt, pkt));
                off += m;
            }
            writeRaw(pkt);
            VpnTestState.flowBytes(c.no, c.upBytes, c.downBytes);
        }
        return true;
    }

    /** Drains the hub's in-order payload into the real socket; applies the hub's FIN as
     * the socket's output shutdown once everything before it has been written. */
    private void connPumpLoop(FwdConn c) {
        while (!c.closed) {
            final byte[] chunk;
            final boolean fin;
            synchronized (c.lock) {
                while (c.writeQ.isEmpty() && !c.clientFin && !c.closed) {
                    try { c.lock.wait(200); } catch (InterruptedException e) { return; }
                }
                if (c.closed) return;
                chunk = c.writeQ.poll();
                if (chunk != null) c.writeQBytes -= chunk.length;
                fin = chunk == null && c.clientFin;
            }
            if (chunk != null) {
                try {
                    c.sockOut.write(chunk);
                    c.sockOut.flush();
                } catch (IOException e) {
                    writeRaw(buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_RST | FLAG_ACK, null, 0, 0));
                    closeConn(c, "socket-error");
                    return;
                }
                synchronized (c.lock) { c.upBytes += chunk.length; }
                VpnTestState.flowBytes(c.no, c.upBytes, c.downBytes);
            }
            if (fin) {
                try { c.socket.shutdownOutput(); } catch (Throwable ignored) { }
                checkClose(c);
                return;
            }
        }
    }

    private void handleSegment(FwdConn c, long seq, long ack, int flags, int wnd,
                               byte[] b, int pOff, int pLen) {
        synchronized (c.lock) {
            if (c.closed) return;
            c.hubWnd = wnd << c.hubScale;
            if ((flags & FLAG_ACK) != 0) {
                if (ack > c.hubAck && ack <= c.sndNxt) {
                    c.hubAck = ack;
                    while (!c.unacked.isEmpty() && c.unacked.peek().endSeq <= ack) c.unacked.poll();
                    c.lastAckProgress = System.currentTimeMillis();
                }
                c.lock.notifyAll();
            }
            if ((flags & FLAG_RST) != 0) {
                closeConnLocked(c, "rst");
                return;
            }
            if ((flags & FLAG_SYN) != 0) {
                // A retransmitted SYN: our SYN-ACK was lost; answer it again.
                if (seq == c.rcvNxt - 1 && c.synAckPacket != null) writeRaw(c.synAckPacket);
                return;
            }
            if (pLen > 0) {
                if (seq == c.rcvNxt) {
                    enqueuePayload(c, b, pOff, pLen);
                } else if (seq < c.rcvNxt && seq + pLen > c.rcvNxt) {
                    int trim = (int) (c.rcvNxt - seq);
                    enqueuePayload(c, b, pOff + trim, pLen - trim);
                }
                // Out-of-order or fully old: the ACK below tells the hub where we stand;
                // what it retransmits in order is accepted then.
                writeRaw(buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_ACK, null, 0, 0));
            }
            if ((flags & FLAG_FIN) != 0) {
                long finAt = seq + pLen;
                if (finAt == c.rcvNxt) {
                    c.rcvNxt++;
                    c.clientFin = true;
                    c.lock.notifyAll();
                }
                writeRaw(buildPacket(c, c.sndNxt, c.rcvNxt, FLAG_ACK, null, 0, 0));
            }
        }
    }

    /** Accepts hub payload into the socket-bound queue, up to the queue cap; the accepted
     * length is what rcvNxt advances by, so a full queue reads as a closed window and the
     * hub's retransmits fill the gap later — nothing already accepted is ever dropped. */
    private void enqueuePayload(FwdConn c, byte[] b, int off, int len) {
        int room = (int) Math.min(len, MAX_WRITE_Q - c.writeQBytes);
        if (room <= 0) return;
        c.writeQ.add(Arrays.copyOfRange(b, off, off + room));
        c.writeQBytes += room;
        c.rcvNxt += room;
        c.lock.notifyAll();
    }

    private void tickRetransmit(FwdConn c) {
        synchronized (c.lock) {
            if (c.closed) return;
            Unacked head = c.unacked.peek();
            long now = System.currentTimeMillis();
            if (head != null && now - head.lastSent > 1000 && now - c.lastAckProgress > 1000) {
                writeRaw(head.packet);
                head.lastSent = now;
            }
        }
    }

    private void checkClose(FwdConn c) {
        synchronized (c.lock) {
            if (!c.closed && c.clientFin && c.finSent && c.hubAck >= c.finSeq + 1) {
                closeConnLocked(c, "complete");
            }
        }
    }

    private void closeConn(FwdConn c, String reason) {
        synchronized (c.lock) { closeConnLocked(c, reason); }
    }

    private void closeConnLocked(FwdConn c, String reason) {
        if (c.closed) return;
        c.closed = true;
        conns.remove(c.key);
        if (c.socket != null) {
            try { c.socket.close(); } catch (Throwable ignored) { }
        }
        Log.i(TAG, "conn " + c.no + " closed: " + reason
                + " (up=" + c.upBytes + " down=" + c.downBytes + ")");
        VpnTestState.flowClosed(c.no, reason);
        c.lock.notifyAll();
    }

    // ---- packet plumbing (this side of the tunnel: from the server, to the hub)

    private byte[] buildPacket(FwdConn c, long seq, long ack, int flags,
                               byte[] payload, int pOff, int pLen) {
        int total = 20 + 20 + pLen;
        byte[] p = new byte[total];
        p[0] = 0x45;
        putBe16(p, 2, total);
        putBe16(p, 4, (c.no * 7919 + (++ipIdCounter)) & 0xffff);
        putBe16(p, 6, 0x4000);
        p[8] = 64;
        p[9] = 6;
        putBe32(p, 12, c.serverIp);
        putBe32(p, 16, c.clientIp);
        putBe16(p, 20, c.serverPort);
        putBe16(p, 22, c.clientPort);
        putBe32(p, 24, (int) seq);
        putBe32(p, 28, (int) ack);
        p[32] = (5 << 4);
        p[33] = (byte) flags;
        putBe16(p, 34, 65535);
        if (payload != null && pLen > 0) System.arraycopy(payload, pOff, p, 40, pLen);
        putBe16(p, 10, cksumDone(cksumAdd(p, 0, 20, 0)));
        long sum = cksumAdd(p, 12, 8, 0) + 6 + (total - 20);
        putBe16(p, 36, cksumDone(cksumAdd(p, 20, total - 20, sum)));
        return p;
    }

    private void writeRaw(byte[] pkt) {
        synchronized (tunWriteLock) {
            try {
                if (tunOut != null) {
                    tunOut.write(pkt);
                    tunOut.flush();
                }
            } catch (Throwable e) {
                // the session is going away; the hub hears about it from the channel itself.
                // Still logged: a write failure on a live session is exactly the kind of
                // silence this batch exists to remove.
                Log.w(TAG, "tun write failed (" + pkt.length + " bytes): " + e);
            }
        }
    }

    private static long cksumAdd(byte[] b, int off, int len, long seed) {
        long sum = seed;
        int i = off, end = off + len;
        while (i + 1 < end) { sum += ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); i += 2; }
        if (i < end) sum += (b[i] & 0xff) << 8;
        return sum;
    }

    private static int cksumDone(long sum0) {
        long sum = sum0;
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum & 0xffff);
    }

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static long be32u(byte[] b, int off) {
        return be32(b, off) & 0xffffffffL;
    }

    private static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8); b[off + 1] = (byte) v;
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    private static String ipText(int v) {
        return (v >>> 24) + "." + ((v >>> 16) & 0xff) + "." + ((v >>> 8) & 0xff) + "." + (v & 0xff);
    }

    @Override public void onRevoke() {
        VpnTestState.set("系統撤銷了 VPN（onRevoke）");
        shutdown();
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        // Tell the hub the session is over, explicitly, before anything is closed. The hub
        // used to learn it from the channel running dry, but the framework keeps its own
        // duplicate of the tun descriptor, so the channel never runs dry on a stop — the hub
        // heard nothing, the framework's session stayed registered, and the next establish()
        // stacked a second interface on the corpse of the first. The signal rides the tunnel
        // itself, the same way EXEMPT does; every exit funnels through here (the stop action,
        // onDestroy, onRevoke), so one send covers all three. Sent once per session: the
        // tunnel reference is dropped at the end of this method, so repeat calls skip it.
        running = false;
        if (tunnel != null) {
            try {
                writeRaw("SESSION_END\n".getBytes("UTF-8"));
                Log.i("aemu-vpn", "SESSION_END written into the tunnel: the hub is told the session is over");
            } catch (Throwable ignored) { }
        }
        for (FwdConn c : conns.values()) closeConn(c, "session-end");
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        if (readerIn != null) {
            try { readerIn.close(); } catch (Throwable ignored) { }
            readerIn = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (Throwable ignored) { }
            tunnel = null;
        }
        tunOut = null;
        VpnTestState.established = false;
    }
}
