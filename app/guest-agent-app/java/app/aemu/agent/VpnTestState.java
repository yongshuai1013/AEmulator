package app.aemu.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared state between the VPN test service and its page. Same process, plain statics:
 * the service writes, the page polls. Nothing here persists — it is a test readout,
 * not a record.
 *
 * M3 adds the per-flow evidence table: one row per translated connection with its state,
 * both directions' payload byte counts and its closing reason, so the page can be checked
 * line by line against the hub's own flow lines in the host log.
 */
public final class VpnTestState {
    static volatile String status = "尚未開始";
    static volatile boolean established = false;
    static volatile long packets = 0;
    static volatile long bytes = 0;
    static volatile String firstPacket = "";

    /** One translated connection's evidence row. */
    static final class FlowRow {
        int no;
        String dst = "";
        volatile String state = "SYN";
        volatile long up = 0;
        volatile long down = 0;
        volatile String reason = "";
    }

    private static final Map<Integer, FlowRow> FLOWS = new LinkedHashMap<Integer, FlowRow>();

    private VpnTestState() { }

    static void set(String s) {
        status = s;
    }

    static void onEstablished() {
        established = true;
        status = "establish() 成功——會話已建立，流量將繞進隧道";
    }

    static void addPacket(byte[] buf, int n) {
        packets++;
        bytes += n;
        if (packets == 1) firstPacket = describe(buf, n);
    }

    static void flowOpened(int no, String dst) {
        FlowRow r = new FlowRow();
        r.no = no;
        r.dst = dst;
        synchronized (FLOWS) { FLOWS.put(no, r); }
    }

    static void flowState(int no, String state) {
        synchronized (FLOWS) {
            FlowRow r = FLOWS.get(no);
            if (r != null) r.state = state;
        }
    }

    static void flowBytes(int no, long up, long down) {
        synchronized (FLOWS) {
            FlowRow r = FLOWS.get(no);
            if (r != null) { r.up = up; r.down = down; }
        }
    }

    static void flowClosed(int no, String reason) {
        synchronized (FLOWS) {
            FlowRow r = FLOWS.get(no);
            if (r != null) { r.state = "CLOSED"; r.reason = reason; }
        }
    }

    /** The evidence table as page lines: "#no dst state up=<n> down=<n> [reason]". */
    static String flowsText() {
        synchronized (FLOWS) {
            if (FLOWS.isEmpty()) return "（尚無 flow）";
            StringBuilder sb = new StringBuilder();
            for (FlowRow r : FLOWS.values()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append('#').append(r.no).append(' ').append(r.dst)
                        .append(' ').append(r.state)
                        .append(" up=").append(r.up)
                        .append(" down=").append(r.down);
                if (!r.reason.isEmpty()) sb.append(" (").append(r.reason).append(')');
            }
            return sb.toString();
        }
    }

    /** One-line summary of a packet, in the same shape the hub logs host-side, so the two readouts can be checked against each other. */
    static String describe(byte[] b, int n) {
        if (n < 20 || (b[0] >> 4) != 4) return n + " bytes，非 IPv4";
        int ihl = (b[0] & 0xf) * 4;
        int proto = b[9] & 0xff;
        String src = ipAt(b, 12);
        String dst = ipAt(b, 16);
        String pname = proto == 6 ? "TCP" : proto == 17 ? "UDP" : proto == 1 ? "ICMP" : "proto " + proto;
        if ((proto == 6 || proto == 17) && n >= ihl + 4) {
            return "IPv4/" + pname + " " + src + ":" + be16(b, ihl) + " -> " + dst + ":" + be16(b, ihl + 2)
                    + "（" + n + " bytes）";
        }
        return "IPv4/" + pname + " " + src + " -> " + dst + "（" + n + " bytes）";
    }

    private static String ipAt(byte[] b, int off) {
        return (b[off] & 0xff) + "." + (b[off + 1] & 0xff) + "." + (b[off + 2] & 0xff) + "." + (b[off + 3] & 0xff);
    }

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static void reset() {
        established = false;
        packets = 0;
        bytes = 0;
        firstPacket = "";
        synchronized (FLOWS) { FLOWS.clear(); }
    }
}
