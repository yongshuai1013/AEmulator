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

    private VpnTestState() { }

    static void set(String s) {
        status = s;
    }

    static void onEstablished() {
        established = true;
        status = "establish() 成功——控制面已通";
    }

    static void addPacket(int n) {
        packets++;
        bytes += n;
    }

    static void reset() {
        established = false;
        packets = 0;
        bytes = 0;
    }
}
