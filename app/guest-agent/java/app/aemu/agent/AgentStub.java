package app.aemu.agent;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guest agent (started by the host like the Wi-Fi service; see AgentSupport on the host side).
 *
 * Two jobs, both needing a process inside the guest:
 *  - the hide list: the host names packages, only the guest's package manager knows their uids. The
 *    resolved set is kept in /dev/.aemu-hideuids, which is where the mount layer reads it from; it is
 *    re-resolved on start, on the host's SYNC and every minute (installs change uids).
 *  - the import inbox: the host streams an APK over the socket and the agent writes it into the guest's
 *    own external storage (AEmuImport/), a file born in the guest's view of the sdcard — the host writing
 *    into the tree from outside was the step that made the old import path fail.
 *
 * Protocol (line-based, PUT payload raw): HELLO/UIDS/LOG to the host, SYNC and PUT from it, OK/ERR back.
 * Usage: app_process -Djava.class.path=/system/framework/aemu-agent.jar /system/bin app.aemu.agent.AgentStub
 */
public final class AgentStub {
    private static final String SETTINGS = "/data/system/aemu-agent.properties";
    private static final String SOCKET = "/dev/socket/aemu-agent";
    private static final String HIDEUIDS = "/dev/.aemu-hideuids";
    private static final String INBOX = "AEmuImport";

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
        syncUids(p, null);   // once before the link: the mount layer reads the file, not the socket
        long backoff = 1000;
        while (true) {
            LocalSocket s = null;
            try {
                s = new LocalSocket();
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
                backoff = 1000;
                serve(s, p);
            } catch (Throwable e) {
                System.out.println("aemu-agent: link: " + e);
                System.out.flush();
            } finally {
                if (s != null) try { s.close(); } catch (Throwable ignored) { }
            }
            Thread.sleep(backoff);
            backoff = Math.min(backoff * 2, 30000);
        }
    }

    private static void serve(LocalSocket s, Properties first) throws Exception {
        final OutputStream out = s.getOutputStream();
        final InputStream in = new BufferedInputStream(s.getInputStream());
        final Properties[] current = { first };
        final Object sendLock = new Object();
        send(out, sendLock, "HELLO api=" + Build.VERSION.SDK_INT);
        int n = syncUids(current[0], out, sendLock);
        if (n >= 0) send(out, sendLock, "UIDS " + n);
        Thread ticker = new Thread("aemu-agent-sync") {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                    Properties p = loadSettings();
                    if (p != null) current[0] = p;
                    try {
                        int c = syncUids(current[0], null);
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
                Properties p = loadSettings();
                if (p != null) current[0] = p;
                int c = syncUids(current[0], null);
                send(out, sendLock, "UIDS " + c);
            } else if (line.startsWith("PUT ")) {
                handlePut(line, in, out, sendLock);
            } else {
                send(out, sendLock, "ERR unknown command");
            }
        }
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

    // ---- the hide list --------------------------------------------------------------------------------

    /**
     * Resolves the settings' package list to uids and rewrites /dev/.aemu-hideuids (atomically, through a
     * rename). An empty list removes the file: no reader is hidden then. Returns the number of uids
     * written, or -1 when the settings are unusable. Unresolved packages are reported (the host's log via
     * [hostOut] when given, stdout otherwise) — a name the package manager does not know is a fact to
     * surface, not to paper over.
     */
    private static int syncUids(Properties p, OutputStream hostOut, Object lock) {
        List<String> hidden = hiddenList(p);
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

    private static int syncUids(Properties p, Object unused) {
        return syncUids(p, null, null);
    }

    private static List<String> hiddenList(Properties p) {
        List<String> out = new ArrayList<String>();
        String csv = p == null ? null : p.getProperty("hidden", "");
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
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
}
