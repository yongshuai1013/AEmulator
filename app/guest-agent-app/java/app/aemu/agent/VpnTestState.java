package app.aemu.agent;

/**
 * Shared state between the VPN control-plane test service and its page. Same process, plain
 * statics: the service writes, the page polls. Nothing here persists — it is a test readout,
 * not a record.
 */
public final class VpnTestState {
    static volatile String status = "尚未開始";
    static volatile boolean established = false;
    static volatile long packets = 0;
    static volatile long bytes = 0;
    static volatile String firstPacket = "";

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
    }
}
