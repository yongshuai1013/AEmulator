package app.aemu.agent;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The smallest real VpnService: the test for milestone M2. It asks the framework for a tunnel
 * exactly like a shipping VPN app (Builder, one address, a default route, MTU) and reports where
 * establish() lands. At M2 guest traffic is detoured into the tunnel and the hub's synthesizer
 * frames it back as TCP segments, so the reader below counts real packets — the first one's
 * summary is the milestone's evidence. The return path (segments read back out and answered)
 * is M3: a detoured connection gets no reply yet, and the page says so.
 */
public final class VpnTestService extends VpnService {
    static final String ACTION_STOP = "app.aemu.agent.VPN_TEST_STOP";

    private ParcelFileDescriptor tunnel;
    private Thread reader;
    private FileInputStream readerIn;

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
            // Register this app's uid with the hub, through the tunnel itself: the hub exempts
            // it from the detour, which is what VpnService.protect() promises the app's own
            // sockets. Written before the reader starts so it is the channel's first content.
            try {
                FileOutputStream out = new FileOutputStream(p.getFileDescriptor());
                out.write(("EXEMPT " + android.os.Process.myUid() + "\n").getBytes("UTF-8"));
                out.flush();
            } catch (Throwable ignored) { }
            reader = new Thread(new Runnable() {
                @Override public void run() { readLoop(); }
            }, "aemu-vpn-reader");
            reader.setDaemon(true);
            reader.start();
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
                    VpnTestState.set("tun 讀取結束：" + e);
                    return;
                }
                if (n > 0) {
                    VpnTestState.addPacket(buf, n);
                } else if (n < 0) {
                    VpnTestState.set("tun 已關閉（read 回 EOF）");
                    return;
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (InterruptedException e) {
            // stopped: fall through quietly
        }
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
        if (tunnel != null) {
            try {
                FileOutputStream out = new FileOutputStream(tunnel.getFileDescriptor());
                out.write("SESSION_END\n".getBytes("UTF-8"));
                out.flush();
                Log.i("aemu-vpn", "SESSION_END written into the tunnel: the hub is told the session is over");
            } catch (Throwable ignored) { }
        }
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
        VpnTestState.established = false;
    }
}
