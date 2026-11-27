package app.aemu.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One-shot TCP timing probe. Runs three connect shapes against a chosen
 * destination and timestamps every step on the guest's own clock
 * (SystemClock.elapsedRealtime), so the numbers can be lined up against the
 * host hub's log of the same connections:
 *   A: blocking Socket (connect -> write at once -> read first reply)
 *   B: non-blocking SocketChannel + Selector (the shape Chrome uses:
 *      OP_CONNECT, then OP_WRITE, write, then OP_READ)
 *   C: like B, but right after connect() returns - before any OP_WRITE
 *      wait - it tries one non-blocking 1-byte probe write and records the
 *      outcome and how long the write call itself took. If the channel's
 *      non-blocking flag were lost on the way through the detour, that call
 *      would block instead of returning at once; the probe byte is payload
 *      byte 0, so the wire total stays 212 either way.
 * Every connection prints the same field set: t_sock, fd_flags (Java cannot
 * read F_GETFL; for channels the isBlocking() state is printed instead and
 * the note says so), t_conn_pre, t_conn_ret (with the wall-clock second -
 * the join key against the hub's flow-ok lines, in flow-id order, and its
 * first-guest-bytes line), t_poll_pre / t_poll_ret for the connect wait
 * (B/C suffix the later waits pollw / pollr), t_write_ret and t_close.
 * Every line goes to the on-screen log, to logcat (tag AEMU-PROBE) and to
 * /sdcard/aemu-probe-last.txt (overwritten each run). The payload is a
 * 212-byte dummy - the size of the TLS ClientHello the field runs kept
 * losing; its content means nothing.
 */
public class MainActivity extends Activity {
    private static final String TAG = "AEMU-PROBE";
    private static final int PAYLOAD_LEN = 212;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 12000;

    private EditText ipField;
    private EditText portField;
    private Button runButton;
    private TextView logView;
    private ScrollView scroller;

    private final StringBuilder all = new StringBuilder();
    private long lastTs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ipField = new EditText(this);
        ipField.setText("142.250.197.238");
        ipField.setHint("destination IP");
        ipField.setSingleLine(true);
        root.addView(ipField);

        portField = new EditText(this);
        portField.setText("443");
        portField.setHint("port");
        portField.setSingleLine(true);
        root.addView(portField);

        runButton = new Button(this);
        runButton.setText("Run once");
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun();
            }
        });
        root.addView(runButton);

        logView = new TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextSize(11);
        scroller = new ScrollView(this);
        scroller.addView(logView);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void startRun() {
        final String ip = ipField.getText().toString().trim();
        int p;
        try {
            p = Integer.parseInt(portField.getText().toString().trim());
        } catch (Throwable t) {
            p = -1;
        }
        final int port = p;
        runButton.setEnabled(false);
        all.setLength(0);
        logView.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    raw("probe start dst=" + ip + ":" + port + " wall=" + wallFull());
                    raw("# key: join each form's t_conn_ret wall= second + dst against"
                            + " the hub log's flow-ok lines (in flow-id order) and its"
                            + " first-guest-bytes line");
                    if (port < 1 || port > 65535) {
                        raw("bad port, aborting");
                    } else {
                        runBlocking(ip, port);
                        saveFile();
                        runNonBlocking(ip, port, false);
                        saveFile();
                        runNonBlocking(ip, port, true);
                    }
                } catch (Throwable t) {
                    raw("RUN-LEVEL " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    saveFile();
                    raw("probe end");
                    saveFile();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            runButton.setEnabled(true);
                        }
                    });
                }
            }
        }, "aemu-probe").start();
    }

    private static String wallFull() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
    }

    private static String wallSec() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** One timestamped step line: form, step, absolute elapsedRealtime, delta. */
    private void step(String form, String name) {
        long now = SystemClock.elapsedRealtime();
        long delta = (lastTs == 0) ? 0 : now - lastTs;
        lastTs = now;
        raw(form + " " + name + " t=" + now + " delta=" + delta);
    }

    /** The t_conn_ret line plus the join-key line that belongs to it. */
    private void connRet(String form, String ip, int port, String result) {
        step(form, "t_conn_ret " + result + " wall=" + wallSec());
        raw(form + " key dst=" + ip + ":" + port + " conn_ret_wall=" + wallSec());
    }

    private void note(String form, String name, Throwable t) {
        raw(form + " " + name + " EX " + t.getClass().getSimpleName()
                + ": " + String.valueOf(t.getMessage()));
    }

    private void raw(final String line) {
        synchronized (all) {
            all.append(line).append('\n');
        }
        Log.i(TAG, line);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logView.append(line + "\n");
                scroller.post(new Runnable() {
                    @Override
                    public void run() {
                        scroller.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void saveFile() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(),
                    "aemu-probe-last.txt");
            FileOutputStream out = new FileOutputStream(f, false);
            synchronized (all) {
                out.write(all.toString().getBytes("UTF-8"));
            }
            out.close();
        } catch (Throwable t) {
            Log.i(TAG, "save failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static byte[] payload() {
        byte[] b = new byte[PAYLOAD_LEN];
        b[0] = 0x16; // TLS handshake record header, like the lost ClientHellos
        b[1] = 0x03;
        b[2] = 0x01;
        for (int i = 3; i < b.length; i++) b[i] = (byte) (i & 0xff);
        return b;
    }

    /** Shape A: plain blocking Socket. Its connect() is its wait, so the
     *  poll pair covers the blocking read instead (said on the lines). */
    private void runBlocking(String ip, int port) {
        lastTs = 0;
        Socket s = new Socket();
        step("A", "t_sock");
        step("A", "fd_flags note=java.net.Socket-is-blocking-only;"
                + "F_GETFL-unreadable-from-Java");
        try {
            step("A", "t_conn_pre (=t0)");
            try {
                s.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                connRet("A", ip, port, "(=t1) value=ok");
            } catch (Throwable t) {
                connRet("A", ip, port, "EX " + t.getClass().getSimpleName()
                        + ": " + String.valueOf(t.getMessage()));
                return;
            }
            OutputStream out = s.getOutputStream();
            out.write(payload());
            out.flush();
            step("A", "t_write_ret (=t2) bytes=" + PAYLOAD_LEN);
            s.setSoTimeout(READ_TIMEOUT_MS);
            step("A", "t_poll_pre op=blocking-read timeout=" + READ_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            try {
                int n = in.read(buf);
                if (n < 0) {
                    step("A", "t_poll_ret (=t3) result=eof");
                } else {
                    step("A", "t_poll_ret (=t3) result=bytes=" + n);
                }
            } catch (java.net.SocketTimeoutException t) {
                step("A", "t_poll_ret (=t3) result=TIMEOUT");
            } catch (Throwable t) {
                note("A", "t_poll_ret (=t3)", t);
            }
        } catch (Throwable t) {
            note("A", "write", t);
        } finally {
            try {
                s.close();
            } catch (Throwable t) {
                note("A", "close", t);
            }
            step("A", "t_close");
        }
    }

    /** Shapes B and C: non-blocking SocketChannel + Selector, the way Chrome
     *  connects. C (probeForm) adds the 1-byte probe write right after
     *  connect() returns, before any OP_WRITE wait. */
    private void runNonBlocking(String ip, int port, boolean probeForm) {
        final String form = probeForm ? "C" : "B";
        lastTs = 0;
        SocketChannel ch = null;
        Selector sel = null;
        boolean probed = false;
        try {
            ch = SocketChannel.open();
            step(form, "t_sock");
            ch.configureBlocking(false);
            step(form, "fd_flags isBlocking=" + ch.isBlocking()
                    + " (configureBlocking(false) set"
                    + (probeForm ? " before connect" : "")
                    + "; F_GETFL unreadable from Java)");
            sel = Selector.open();
            step(form, "t_conn_pre (=t0)");
            boolean immediate;
            try {
                immediate = ch.connect(new InetSocketAddress(ip, port));
                connRet(form, ip, port, "value=" + immediate);
            } catch (Throwable t) {
                connRet(form, ip, port, "EX " + t.getClass().getSimpleName()
                        + ": " + String.valueOf(t.getMessage()));
                return;
            }
            if (probeForm) {
                ByteBuffer one = ByteBuffer.wrap(payload(), 0, 1);
                long w0 = SystemClock.elapsedRealtime();
                String result;
                int n = -1;
                try {
                    n = ch.write(one);
                    result = (n == 1) ? "written" : "would-block";
                } catch (Throwable t) {
                    result = "EX " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                }
                long callMs = SystemClock.elapsedRealtime() - w0;
                probed = (n == 1);
                step(form, "t_probe_write bytes=1 result=" + result
                        + " callMs=" + callMs);
            }
            SelectionKey key = ch.register(sel, SelectionKey.OP_CONNECT);
            step(form, "t_poll_pre op=OP_CONNECT timeout=" + CONNECT_TIMEOUT_MS);
            if (sel.select(CONNECT_TIMEOUT_MS) > 0 && key.isConnectable()) {
                try {
                    ch.finishConnect();
                } catch (Throwable t) {
                    note(form, "t_poll_ret (=t1)", t);
                    return;
                }
                step(form, "t_poll_ret (=t1) result=connectable");
            } else {
                step(form, "t_poll_ret (=t1) result=TIMEOUT");
                return;
            }
            key.interestOps(SelectionKey.OP_WRITE);
            step(form, "t_pollw_pre op=OP_WRITE timeout=" + READ_TIMEOUT_MS);
            if (sel.select(READ_TIMEOUT_MS) > 0 && key.isWritable()) {
                step(form, "t_pollw_ret (=t2) result=writable");
            } else {
                step(form, "t_pollw_ret (=t2) result=TIMEOUT");
                return;
            }
            byte[] pl = payload();
            ByteBuffer buf = probed
                    ? ByteBuffer.wrap(pl, 1, PAYLOAD_LEN - 1)
                    : ByteBuffer.wrap(pl);
            int total = buf.remaining();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (buf.hasRemaining()) {
                int n = ch.write(buf);
                if (n == 0) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        step(form, "t_write_ret result=STALLED");
                        return;
                    }
                    sel.select(500);
                }
            }
            step(form, "t_write_ret (=t3) bytes=" + total);
            key.interestOps(SelectionKey.OP_READ);
            step(form, "t_pollr_pre op=OP_READ timeout=" + READ_TIMEOUT_MS);
            if (sel.select(READ_TIMEOUT_MS) > 0 && key.isReadable()) {
                ByteBuffer rb = ByteBuffer.allocate(4096);
                try {
                    int n = ch.read(rb);
                    if (n < 0) {
                        step(form, "t_pollr_ret (=t4) result=eof");
                    } else {
                        step(form, "t_pollr_ret (=t4) result=bytes=" + n);
                    }
                } catch (Throwable t) {
                    note(form, "t_pollr_ret (=t4)", t);
                }
            } else {
                step(form, "t_pollr_ret (=t4) result=TIMEOUT");
            }
        } catch (Throwable t) {
            note(form, "run", t);
        } finally {
            try {
                if (ch != null) ch.close();
            } catch (Throwable t) {
                note(form, "close-channel", t);
            }
            try {
                if (sel != null) sel.close();
            } catch (Throwable t) {
                note(form, "close-selector", t);
            }
            step(form, "t_close");
        }
    }
}
