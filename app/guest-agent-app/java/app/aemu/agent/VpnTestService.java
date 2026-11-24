package app.aemu.agent;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * The smallest real VpnService: the control-plane test for milestone M1. It asks the framework
 * for a tunnel exactly like a shipping VPN app (Builder, one address, a default route, MTU) and
 * reports where establish() lands. M1 is the control plane only: in this build no guest traffic
 * enters the tunnel yet, so the reader below is expected to count zero packets — that is the
 * honest readout, and the page says so. Carrying traffic is M2.
 */
public final class VpnTestService extends VpnService {
    static final String ACTION_STOP = "app.aemu.agent.VPN_TEST_STOP";

    private ParcelFileDescriptor tunnel;
    private Thread reader;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            VpnTestState.set("已停止（使用者）");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel != null) {
            VpnTestState.set("已在運行——控制面已建立");
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
                    VpnTestState.addPacket(n);
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
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (Throwable ignored) { }
            tunnel = null;
        }
        VpnTestState.established = false;
    }
}
