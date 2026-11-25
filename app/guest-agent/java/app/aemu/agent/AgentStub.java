package app.aemu.agent;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guest agent (started by the host like the Wi-Fi service; see AgentSupport on the host side).
 *
 * Three jobs, all needing a process inside the guest:
 *  - the hide list: only the guest's package manager knows which uid a package has. The resolved set is
 *    kept in /dev/.aemu-hideuids, which is where the mount layer reads it from; it is re-resolved on
 *    start, on the host's SYNC and every minute (installs change uids). The list itself lives in the
 *    guest — /data/system/aemu-hideapps.txt, one package per line — and is owned by the Root Manager
 *    app (below): the host only reads it at boot (to seed the uid set before the agent is up and to
 *    hand it to the Magisk daemon as its denylist), it never writes it.
 *  - the import inbox: the host streams an APK over the socket and the agent writes it into the guest's
 *    own external storage (AEmuImport/), a file born in the guest's view of the sdcard — the host writing
 *    into the tree from outside was the step that made the old import path fail.
 *  - the Root Manager's server end: the manager app (package app.aemu.agent, the same package the inbox
 *    app ships under) connects to the abstract socket "aemu-agent-mgr" and asks about the guest's root
 *    state (STATUS), reads and changes the hide list (LIST / SETHIDE) and asks what a listed app would
 *    see (SELFTEST). Every answer comes from surfaces that exist inside the guest — files the agent can
 *    read, the package manager, the overlay journal, the mount layer's rules — and a cell the agent
 *    cannot answer says so instead of guessing. Callers are admitted by peer uid: root, system, or the
 *    manager app itself; the Magisk app and the manager itself can never be put on the hide list.
 *
 * Host protocol (line-based, PUT payload raw): HELLO/UIDS/LOG/DENY to the host, SYNC, PUT and the
 * DENIED/DENYDEFER/DENYERR answers from it, OK/ERR back.
 * Usage: app_process -Djava.class.path=/system/framework/aemu-agent.jar /system/bin app.aemu.agent.AgentStub
 */
public final class AgentStub {
    private static final String SETTINGS = "/data/system/aemu-agent.properties";
    private static final String SOCKET = "/dev/socket/aemu-agent";
    private static final String HIDEUIDS = "/dev/.aemu-hideuids";
    /** the hide list itself: guest-owned, written only through the manager protocol (atomically) */
    private static final String LIST_FILE = "/data/system/aemu-hideapps.txt";
    private static final String INBOX = "AEmuImport";
    /** the manager app's channel (abstract namespace: no file, admission is the peer-uid check) */
    private static final String MGR_SOCKET = "aemu-agent-mgr";
    private static final String OWN_PACKAGE = "app.aemu.agent";
    private static final String MAGISK_PACKAGE = "com.topjohnwu.magisk";
    private static final String MAGISK_STATE = "/data/adb/aemu-magisk.properties";
    private static final String MAGISK_BIN = "/data/adb/magisk/magisk";
    private static final String MODULES = "/data/adb/modules";
    private static final String MODULES_UPDATE = "/data/adb/modules_update";
    private static final String JOURNAL = "/data/adb/aemu-overlay/journal";

    /** the host link's sending side, while connected: SETHIDE's DENY goes out over it */
    private static volatile OutputStream hostOut;
    private static volatile Object hostLock;
    /** what the host last answered to a DENY, per package: "applied on", "deferred off", "error: ..." */
    private static final ConcurrentHashMap<String, String> denyState = new ConcurrentHashMap<String, String>();

    /** the isolation probe's setup: the host's ISOSETUP answer, and the marker set it names */
    private static volatile String isoSetup;
    private static volatile CountDownLatch isoLatch;
    private static volatile String isoMarker, isoOut, isoIn;

    private AgentStub() { }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }

    private static Properties loadSettings() {
        File f = new File(SETTINGS);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
            return p;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void run() throws Exception {
        Looper.prepare();
        Properties p = loadSettings();
        if (p == null || !"true".equals(p.getProperty("enabled", "false"))) {
            System.out.println("aemu-agent: disabled or no settings file, exiting");
            System.out.flush();
            return;
        }
        syncUids(null, null);   // once before the link: the mount layer reads the file, not the socket
        Thread mgr = new Thread("aemu-agent-mgr") {
            @Override public void run() { serveManager(); }
        };
        mgr.setDaemon(true);
        mgr.start();
        long backoff = 1000;
        while (true) {
            LocalSocket s = null;
            try {
                s = new LocalSocket();
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
                backoff = 1000;
                serve(s);
            } catch (Throwable e) {
                System.out.println("aemu-agent: link: " + e);
                System.out.flush();
            } finally {
                hostOut = null;
                hostLock = null;
                if (s != null) try { s.close(); } catch (Throwable ignored) { }
            }
            Thread.sleep(backoff);
            backoff = Math.min(backoff * 2, 30000);
        }
    }

    private static void serve(LocalSocket s) throws Exception {
        final OutputStream out = s.getOutputStream();
        final InputStream in = new BufferedInputStream(s.getInputStream());
        final Object sendLock = new Object();
        hostOut = out;
        hostLock = sendLock;
        send(out, sendLock, "HELLO api=" + Build.VERSION.SDK_INT);
        int n = syncUids(out, sendLock);
        if (n >= 0) send(out, sendLock, "UIDS " + n);
        Thread ticker = new Thread("aemu-agent-sync") {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                    try {
                        int c = syncUids(null, null);
                        send(out, sendLock, "UIDS " + c);
                    } catch (Throwable t) {
                        return;   // the link is gone; the serve loop notices on its own
                    }
                }
            }
        };
        ticker.setDaemon(true);
        ticker.start();
        String line;
        while ((line = readLine(in)) != null) {
            if (line.equals("SYNC")) {
                int c = syncUids(null, null);
                send(out, sendLock, "UIDS " + c);
            } else if (line.startsWith("PUT ")) {
                handlePut(line, in, out, sendLock);
            } else if (line.startsWith("DENIED ") || line.startsWith("DENYDEFER ") || line.startsWith("DENYERR ")) {
                recordDeny(line);
            } else if (line.startsWith("ISOSETUP ") || line.startsWith("ISOERR")) {
                isoSetup = line;
                CountDownLatch l = isoLatch;
                if (l != null) l.countDown();
            } else {
                send(out, sendLock, "ERR unknown command");
            }
        }
    }

    private static void recordDeny(String line) {
        // DENIED <pkg> <on|off> / DENYDEFER <pkg> <on|off> / DENYERR <pkg> <on|off> <message...>
        String[] parts = line.split(" ", 4);
        if (parts.length < 3) return;
        String state = parts[0].equals("DENIED") ? "applied " + parts[2]
            : parts[0].equals("DENYDEFER") ? "deferred " + parts[2]
            : "error: " + (parts.length > 3 ? parts[3] : "the host refused");
        denyState.put(parts[1], state);
    }

    private static void send(OutputStream out, Object lock, String line) throws IOException {
        byte[] b = (line + "\n").getBytes("UTF-8");
        synchronized (lock) {
            out.write(b);
            out.flush();
        }
    }

    /** one line without the terminator, from the same buffered stream the PUT payload is read from */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }

    private static void handlePut(String line, InputStream in, OutputStream out, Object lock) throws IOException {
        String[] parts = line.split(" ", 3);
        long size = -1;
        String name = null;
        try {
            size = Long.parseLong(parts[1]);
            name = new File(parts[2]).getName();
        } catch (Throwable ignored) { }
        if (size < 0 || name == null || !name.endsWith(".apk")) {
            send(out, lock, "ERR bad PUT header");
            throw new IOException("bad PUT header, dropping the link");   // the payload state is unknown now
        }
        File dir = new File(Environment.getExternalStorageDirectory(), INBOX);
        File dst = new File(dir, name);
        FileOutputStream fos = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            fos = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("the stream ended " + left + " byte(s) early");
                fos.write(buf, 0, r);
                left -= r;
            }
            fos.getFD().sync();
            fos.close();
            fos = null;
            send(out, lock, "OK " + dst.getAbsolutePath());
        } catch (IOException e) {
            if (fos != null) try { fos.close(); } catch (Throwable ignored) { }
            dst.delete();
            try { send(out, lock, "ERR " + e.getMessage()); } catch (Throwable ignored) { }
            throw e;   // a short payload desyncs the protocol; reconnect instead of guessing
        }
    }

    // ---- the isolation probe ----------------------------------------------------------------------------

    /**
     * ISOPROBE: asks the host (ISOBEGIN) to plant this run's markers — one outside the guest root,
     * one decoy at the same path string inside it — and reports where they are. The agent cannot
     * plant them itself: the outside marker is outside by definition. The answer is the host's own
     * ISOSETUP line, taken apart; a host that does not answer is reported as such.
     */
    private static List<String> isoProbe() {
        List<String> out = new ArrayList<String>();
        OutputStream ho = hostOut;
        Object hl = hostLock;
        if (ho == null || hl == null) {
            out.add("ERR the host link is down; the isolation markers cannot be planted");
            return out;
        }
        CountDownLatch latch = new CountDownLatch(1);
        isoLatch = latch;
        isoSetup = null;
        try {
            send(ho, hl, "ISOBEGIN");
        } catch (Throwable t) {
            out.add("ERR ISOBEGIN could not be sent: " + t.getMessage());
            return out;
        }
        boolean answered;
        try {
            answered = latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            answered = false;
        }
        String setup = isoSetup;
        if (!answered || setup == null) {
            out.add("ERR the host did not answer ISOBEGIN");
            return out;
        }
        if (setup.startsWith("ISOERR")) {
            out.add("ERR the host refused to plant the markers: " + setup.substring(6).trim());
            return out;
        }
        String[] parts = setup.split(" ");
        if (parts.length != 4 || !parts[0].equals("ISOSETUP")) {
            out.add("ERR unexpected answer to ISOBEGIN: " + setup);
            return out;
        }
        isoMarker = parts[1];
        isoOut = parts[2];
        isoIn = parts[3];
        out.add("marker " + isoMarker);
        out.add("out " + isoOut);
        out.add("in " + isoIn);
        return out;
    }

    /**
     * ISORUN: runs the probe binary the manager app carries (it is inside the agent app copy the
     * host delivered to /data/adb) against the markers ISOPROBE planted, returns its lines to the
     * manager, and mirrors every verdict line to the host log. A probe that cannot be started, or
     * does not finish, is reported in the same channel — never as a quiet success.
     */
    private static List<String> isoRun() {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        if (isoMarker == null || isoOut == null || isoIn == null) {
            lines.add("ERR ISOPROBE has not planted the markers yet");
            return lines;
        }
        final String bin;
        try {
            bin = extractProbe();
        } catch (Throwable t) {
            lines.add("ERR the probe binary could not be staged: " + t.getMessage());
            return lines;
        }
        final Process proc;
        try {
            proc = new ProcessBuilder(bin, isoMarker, isoOut, isoIn, "/data/local/tmp")
                .redirectErrorStream(true)
                .start();
        } catch (Throwable t) {
            lines.add("ERR the probe binary could not be started: " + t.getMessage());
            return lines;
        }
        Thread reader = new Thread("aemu-isoprobe-out") {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                } catch (Throwable ignored) { }
            }
        };
        reader.setDaemon(true);
        reader.start();
        Thread waiter = new Thread("aemu-isoprobe-wait") {
            @Override public void run() {
                try { proc.waitFor(); } catch (Throwable ignored) { }
            }
        };
        waiter.start();
        try {
            waiter.join(90000);
        } catch (InterruptedException ignored) { }
        if (waiter.isAlive()) {
            try { proc.destroy(); } catch (Throwable ignored) { }
            lines.add("ERR the probe did not finish in 90 s and was destroyed");
        }
        try {
            reader.join(5000);
        } catch (InterruptedException ignored) { }
        int exit = -1;
        try { exit = proc.exitValue(); } catch (Throwable ignored) { }
        if (lines.isEmpty()) {
            lines.add("ERR the probe produced no output (exit " + exit + ")");
            return lines;
        }
        if (exit != 0) lines.add("note: the probe exited with code " + exit);
        OutputStream ho = hostOut;
        Object hl = hostLock;
        int cells = 0;
        for (String l : new ArrayList<String>(lines)) {
            if (!l.startsWith("ISO ")) continue;
            String[] p = l.split(" ", 4);
            if (p.length >= 3 && (p[2].equals("PASS") || p[2].equals("LEAK") || p[2].equals("NA"))) cells++;
            if (ho != null && hl != null) {
                try { send(ho, hl, "LOG iso-probe: " + l.substring(4)); } catch (Throwable ignored) { }
            }
        }
        if (ho != null && hl != null) {
            try { send(ho, hl, "LOG iso-probe run complete: " + cells + " cell(s) reported"); } catch (Throwable ignored) { }
        }
        return lines;
    }

    /** The probe binary out of the delivered agent app copy, into /data/local/tmp, executable. */
    private static String extractProbe() throws IOException {
        File apk = new File("/data/adb/aemu-agent.apk");
        if (!apk.isFile()) throw new IOException("the agent app copy is not in the guest (" + apk + ")");
        File dst = new File("/data/local/tmp/aemu-isoprobe");
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry e = zip.getEntry("aemu-isoprobe");
            if (e == null) throw new IOException("this agent app build does not carry the probe binary");
            InputStream in = zip.getInputStream(e);
            FileOutputStream fos = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            } finally {
                fos.close();
                in.close();
            }
        } finally {
            zip.close();
        }
        if (!dst.setExecutable(true)) throw new IOException("the probe binary could not be made executable");
        return dst.getAbsolutePath();
    }

    // ---- the hide list --------------------------------------------------------------------------------

    /** The list is the guest's own file now; the host's settings file no longer carries it. */
    private static List<String> hiddenList() {
        List<String> out = new ArrayList<String>();
        File f = new File(LIST_FILE);
        if (!f.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        } catch (Throwable t) {
            System.out.println("aemu-agent: reading the hide list failed: " + t);
            System.out.flush();
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** Persists the list (an empty list stays an empty file: "set to nothing" differs from "never set"). */
    private static void saveHiddenList(List<String> list) throws IOException {
        File dst = new File(LIST_FILE);
        File tmp = new File(LIST_FILE + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            StringBuilder sb = new StringBuilder();
            for (String pkg : list) sb.append(pkg).append('\n');
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("rename to " + dst + " failed"); }
    }

    /**
     * Resolves the hide list to uids and rewrites /dev/.aemu-hideuids (atomically, through a rename).
     * An empty list removes the file: no reader is hidden then. Returns the number of uids written, or
     * -1 when writing failed. Unresolved packages are reported (the host's log via [hostOut] when given,
     * stdout otherwise) — a name the package manager does not know is a fact to surface, not to hide.
     */
    private static int syncUids(OutputStream hostOut, Object lock) {
        List<String> hidden = hiddenList();
        File dst = new File(HIDEUIDS);
        if (hidden.isEmpty()) {
            if (dst.isFile()) dst.delete();
            return 0;
        }
        TreeSet<Integer> uids = new TreeSet<Integer>();
        List<String> missing = new ArrayList<String>();
        for (String pkg : hidden) {
            int uid = pmUid(pkg);
            if (uid < 0) uid = xmlUid(pkg);
            if (uid >= 0) uids.add(uid); else missing.add(pkg);
        }
        if (!missing.isEmpty()) {
            String msg = "LOG unresolved hide-list package(s): " + join(missing);
            if (hostOut != null) {
                try { send(hostOut, lock, msg); } catch (Throwable ignored) { }
            } else {
                System.out.println("aemu-agent: " + msg.substring(4));
                System.out.flush();
            }
        }
        File tmp = new File(HIDEUIDS + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            StringBuilder sb = new StringBuilder();
            for (Integer u : uids) sb.append(u.intValue()).append('\n');
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (!tmp.renameTo(dst)) throw new IOException("rename to " + dst + " failed");
        } catch (Throwable t) {
            if (fos != null) try { fos.close(); } catch (Throwable ignored) { }
            tmp.delete();
            System.out.println("aemu-agent: writing the uid set failed: " + t);
            System.out.flush();
            return -1;
        }
        return uids.size();
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) { if (sb.length() > 0) sb.append(", "); sb.append(x); }
        return sb.toString();
    }

    /** the package manager's own answer: IPackageManager.getPackageUid through ServiceManager */
    private static int pmUid(String pkg) {
        try {
            Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder b = (IBinder) getService.invoke(null, "package");
            if (b == null) return -1;
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Object pm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            if (pm == null) return -1;
            try {
                Method m = pm.getClass().getMethod("getPackageUid", String.class, int.class, int.class);
                Object r = m.invoke(pm, pkg, Integer.valueOf(0), Integer.valueOf(0));
                return r == null ? -1 : ((Integer) r).intValue();
            } catch (NoSuchMethodException e) {
                Method m = pm.getClass().getMethod("getPackageUid", String.class, int.class);
                Object r = m.invoke(pm, pkg, Integer.valueOf(0));
                return r == null ? -1 : ((Integer) r).intValue();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** fallback: the package manager's database, read in the guest (text form only) */
    private static int xmlUid(String pkg) {
        try {
            File f = new File("/data/system/packages.xml");
            if (!f.isFile()) return -1;
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 16L * 1024 * 1024)];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            in.close();
            if (off < 1 || buf[0] != '<') return -1;   // the binary ABX form is not parsed
            String text = new String(buf, 0, off, "UTF-8");
            Matcher tags = Pattern.compile("<package\\b[^>]*>").matcher(text);
            Pattern name = Pattern.compile("\\bname=\"([^\"]+)\"");
            Pattern uid = Pattern.compile("\\buserId=\"(\\d+)\"");
            while (tags.find()) {
                String t = tags.group();
                Matcher nm = name.matcher(t);
                if (!nm.find() || !nm.group(1).equals(pkg)) continue;
                Matcher um = uid.matcher(t);
                if (um.find()) return Integer.parseInt(um.group(1));
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- the Root Manager's server end ------------------------------------------------------------------

    /**
     * Serves the manager app on the abstract socket, one client at a time. Admission is by peer uid:
     * root (0), system (1000) or the manager app's own uid — anything else is dropped before it can ask.
     * The Magisk app and the manager itself are refused as hide-list entries here as well as in the
     * host's boot path: the places root is managed from always see the full guest.
     */
    private static void serveManager() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(MGR_SOCKET);
        } catch (Throwable t) {
            System.out.println("aemu-agent: the manager socket failed: " + t);
            System.out.flush();
            return;
        }
        while (true) {
            LocalSocket c = null;
            try {
                c = server.accept();
                Credentials cred = c.getPeerCredentials();
                int uid = cred == null ? -1 : cred.getUid();
                int mgrUid = pmUid(OWN_PACKAGE);
                if (uid != 0 && uid != 1000 && (mgrUid < 0 || uid != mgrUid)) {
                    System.out.println("aemu-agent: manager socket: dropped a caller with uid " + uid);
                    System.out.flush();
                    c.close();
                    c = null;
                    continue;
                }
                handleManager(c);
            } catch (Throwable t) {
                System.out.println("aemu-agent: manager socket: " + t);
                System.out.flush();
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void handleManager(LocalSocket c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream());
        OutputStream out = c.getOutputStream();
        Object lock = new Object();
        String line;
        while ((line = readLine(in)) != null) {
            if (line.equals("STATUS")) {
                for (String l : status()) send(out, lock, l);
                send(out, lock, "END");
            } else if (line.equals("LIST")) {
                for (String pkg : hiddenList()) send(out, lock, "pkg " + pkg);
                send(out, lock, "END");
            } else if (line.startsWith("SETHIDE ")) {
                send(out, lock, setHide(line));
            } else if (line.startsWith("SELFTEST ")) {
                for (String l : selfTest(line.substring(9).trim())) send(out, lock, l);
                send(out, lock, "END");
            } else if (line.equals("ISOPROBE")) {
                for (String l : isoProbe()) send(out, lock, l);
                send(out, lock, "END");
            } else if (line.equals("ISORUN")) {
                for (String l : isoRun()) send(out, lock, l);
                send(out, lock, "END");
            } else {
                send(out, lock, "ERR unknown command");
            }
        }
    }

    /**
     * SETHIDE <pkg> <1|0>: the guest-side write path of the hide list. The list file is updated first
     * (it is the single source: the next boot's seed and denylist read it), then the uid set the mount
     * layer filters by, then the running daemon's denylist through the host link — when the link is
     * down the answer says so, and the boot path applies the list instead. Nothing here pretends a part
     * it could not do.
     */
    private static String setHide(String line) {
        String[] parts = line.split(" ");
        if (parts.length != 3) return "ERR usage: SETHIDE <package> <1|0>";
        String pkg = parts[1];
        boolean on = parts[2].equals("1");
        if (!pkg.matches("[A-Za-z0-9._]+")) return "ERR not a package name: " + pkg;
        if (pkg.equals(MAGISK_PACKAGE)) return "ERR the Magisk app always sees the full guest";
        if (pkg.equals(OWN_PACKAGE)) return "ERR the manager always sees the full guest";
        synchronized (AgentStub.class) {
            List<String> list = hiddenList();
            boolean has = list.contains(pkg);
            if (on && !has) list.add(pkg);
            if (!on && has) list.remove(pkg);
            try {
                saveHiddenList(list);
            } catch (Throwable t) {
                return "ERR the list could not be written: " + t.getMessage();
            }
            int n = syncUids(null, null);
            String deny;
            OutputStream ho = hostOut;
            Object hl = hostLock;
            if (ho != null && hl != null) {
                try {
                    send(ho, hl, "DENY " + pkg + (on ? " on" : " off"));
                    deny = "sent";
                } catch (Throwable t) {
                    deny = "failed";
                }
            } else {
                deny = "offline";
            }
            return "OK hideuids=" + n + " denylist=" + deny;
        }
    }

    // ---- discovery (STATUS) ------------------------------------------------------------------------------

    /**
     * The guest's root state, every cell from a surface inside the guest: Magisk's own state file (the
     * host's installer wrote it into /data/adb), the binaries on disk, the package manager, the module
     * directories and their prop files, the overlay journal, the native-bridge property the daemon sets
     * for Zygisk. There is no cell for "the daemon is alive": nothing in here can ask it without
     * executing it, so the report does not claim one.
     */
    private static List<String> status() {
        List<String> out = new ArrayList<String>();
        out.add("agent.api=" + Build.VERSION.SDK_INT);
        Properties st = new Properties();
        boolean stateFile = false;
        File sf = new File(MAGISK_STATE);
        if (sf.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(sf);
                st.load(in);
                stateFile = true;
            } catch (Throwable ignored) { } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        boolean bin = new File(MAGISK_BIN).isFile();
        out.add("magisk.installed=" + (bin && stateFile ? "1" : "0"));
        if (stateFile) {
            out.add("magisk.version=" + st.getProperty("version", "?"));
            out.add("magisk.code=" + st.getProperty("code", "?"));
        }
        out.add("magisk.bin=" + (bin ? "1" : "0"));
        out.add("magisk.manager=" + (pmUid(MAGISK_PACKAGE) >= 0 ? "1" : "0"));
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/system/bin/magisk", "/system/bin/resetprop", "/system/bin/magiskpolicy"}) {
            File f = new File(path);
            out.add("path." + path + "=" + (f.exists() ? "1" : "0"));
            if (f.exists()) out.add("path." + path + ".ours=" + (new File(path + ".aemu-orig").exists() ? "1" : "0"));
        }
        String bridge = systemProperty("ro.dalvik.vm.native.bridge");
        out.add("zygisk.bridge=" + (bridge == null || bridge.isEmpty() ? "-" : bridge));
        out.add("zygisk.on=" + (bridge != null && bridge.startsWith("libzygisk.so") ? "1" : "0"));
        // modules: /data/adb/modules/<id>/module.prop (+ disable/remove flags), updates beside them
        File mods = new File(MODULES);
        File[] dirs = mods.isDirectory() ? mods.listFiles() : null;
        int count = 0;
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                count++;
                Properties mp = new Properties();
                File pf = new File(d, "module.prop");
                if (pf.isFile()) {
                    InputStream in = null;
                    try {
                        in = new FileInputStream(pf);
                        mp.load(in);
                    } catch (Throwable ignored) { } finally {
                        if (in != null) try { in.close(); } catch (Throwable ignored) { }
                    }
                }
                String id = d.getName();
                out.add("module." + id + ".name=" + mp.getProperty("name", id));
                out.add("module." + id + ".version=" + mp.getProperty("version", "?"));
                out.add("module." + id + ".disabled=" + (new File(d, "disable").exists() ? "1" : "0"));
                out.add("module." + id + ".remove=" + (new File(d, "remove").exists() ? "1" : "0"));
                out.add("module." + id + ".update=" + (new File(MODULES_UPDATE, id).isDirectory() ? "1" : "0"));
            }
        }
        out.add("modules.count=" + count);
        // /data/adb at a glance: what is in there, not what is in the files
        File adb = new File("/data/adb");
        File[] entries = adb.isDirectory() ? adb.listFiles() : null;
        out.add("adb.entries=" + (entries == null ? "0" : String.valueOf(entries.length)));
        long[] bytes = { 0 };
        int[] budget = { 100000 };
        if (entries != null) for (File e : entries) addBytes(e, bytes, budget);
        out.add("adb.bytes=" + bytes[0]);
        // the overlay journal, by kind (F/D new, R/P replaced): what the mount layer would filter
        int[] kinds = journalKinds();
        out.add("journal.present=" + (kinds == null ? "0" : "1"));
        if (kinds != null) {
            out.add("journal.f=" + kinds[0]);
            out.add("journal.d=" + kinds[1]);
            out.add("journal.r=" + kinds[2]);
            out.add("journal.p=" + kinds[3]);
        }
        out.add("hideapps.count=" + hiddenList().size());
        List<Integer> uids = hideUids();
        out.add("hideuids.present=" + (uids == null ? "0" : "1"));
        if (uids != null) out.add("hideuids.count=" + uids.size());
        return out;
    }

    private static void addBytes(File f, long[] bytes, int[] budget) {
        if (budget[0]-- <= 0) return;
        if (f.isFile()) { bytes[0] += f.length(); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) addBytes(k, bytes, budget);
    }

    /** journal kind counts [F, D, R, P], or null when there is no journal */
    private static int[] journalKinds() {
        File j = new File(JOURNAL);
        if (!j.isFile()) return null;
        int[] kinds = { 0, 0, 0, 0 };
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(j), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("F\t")) kinds[0]++;
                else if (line.startsWith("D\t")) kinds[1]++;
                else if (line.startsWith("R\t")) kinds[2]++;
                else if (line.startsWith("P\t")) kinds[3]++;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return kinds;
    }

    private static List<Integer> hideUids() {
        File f = new File(HIDEUIDS);
        if (!f.isFile()) return null;
        List<Integer> out = new ArrayList<Integer>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                try { out.add(Integer.valueOf(line.trim())); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static String systemProperty(String key) {
        try {
            Method m = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            Object r = m.invoke(null, key);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- the self test (SELFTEST) -------------------------------------------------------------------------

    /**
     * What a listed app would see, cell by cell, each cell labelled with where its answer comes from:
     * "measured" (the agent read the layer's own files just now), "journal" (counted from the overlay
     * journal), "rule" (the mount layer's fixed behaviour in vmount.h — the agent cannot read the guest
     * through another uid's eyes, so this cell is the rule, not a probe) and "daemon" (the host's answer
     * to the denylist request). A cell with no surface says "unproven" instead of borrowing confidence.
     */
    private static List<String> selfTest(String pkg) {
        List<String> out = new ArrayList<String>();
        if (pkg.isEmpty() || !pkg.matches("[A-Za-z0-9._]+")) {
            out.add("ERR not a package name: " + pkg);
            return out;
        }
        int uid = pmUid(pkg);
        if (uid < 0) uid = xmlUid(pkg);
        if (uid < 0) {
            out.add("ERR the package manager does not know " + pkg);
            return out;
        }
        out.add("uid=" + uid);
        boolean listed = hiddenList().contains(pkg);
        out.add("listed=" + (listed ? "1" : "0"));
        List<Integer> uids = hideUids();
        boolean inSet = uids != null && uids.contains(Integer.valueOf(uid));
        out.add("hideuids.contains=" + (inSet ? "1" : "0"));
        if (!listed) {
            out.add("cell summary|not listed: this app sees the full guest|nothing is filtered for it|measured");
            return out;
        }
        boolean su = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists();
        out.add("cell su paths|now: " + (su ? "present" : "absent") + "|listed view: not there (ENOENT)|rule: the mount layer's fixed set in vmount.h");
        out.add("cell /data/adb|now: " + (new File("/data/adb").isDirectory() ? "present" : "absent") + "|listed view: not there (ENOENT)|rule: the mount layer's fixed set in vmount.h");
        int[] kinds = journalKinds();
        if (kinds == null) {
            out.add("cell module files|now: no overlay journal|listed view: nothing to filter|measured: no journal file");
        } else {
            out.add("cell module files (new)|journal entries: " + (kinds[0] + kinds[1]) + "|listed view: not there (ENOENT)|journal: counted from the overlay journal");
            out.add("cell module files (replaced)|journal entries: " + (kinds[2] + kinds[3]) + "|listed view: the original file, from the journal's backup|journal: counted from the overlay journal");
        }
        out.add("cell /proc/mounts|now: the base table plus the mount layer's entries|listed view: the base table only|rule: the mount layer serves the base table to listed uids — not probed, the agent has no second pair of eyes");
        String deny = denyState.get(pkg);
        out.add("cell zygisk (denylist)|daemon state: " + (deny == null ? "no answer recorded this boot" : deny) + "|listed view: zygisk stays out when the daemon applied it|daemon: the host's answer to the DENY request" + (deny == null ? " — unproven" : ""));
        if (!inSet) out.add("cell warning|the uid is not in the uid set: the mount layer is not filtering this app right now|fix: toggle it off and on, or reboot|measured: /dev/.aemu-hideuids");
        return out;
    }
}
