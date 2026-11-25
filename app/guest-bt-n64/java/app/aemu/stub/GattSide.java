package app.aemu.stub;

/**
 * What BtCore needs from the per-API-group GATT implementation: host-loss teardown, LE result routing and
 * the live connection table behind IBluetooth.getConnectionState.
 */
interface GattSide {
    /** The phone's adapter went off or the bridge link dropped: every GATT link is gone. */
    void hostLost();
    /** One LE advertising report from the host's shared scan. */
    void leResult(String addr, int rssi, byte[] record);
    /** BluetoothProfile.STATE_* for the address: 2 (CONNECTED) when a GATT link is up, else 0. */
    int connectionState(String addr);
    /** One EV_GATT_* frame from the host bridge, payload reader positioned at the start. */
    void onHostEvent(int op, BtHost.Rd r);
}
