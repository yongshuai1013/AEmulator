package app.aemu.stub;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One LE scan session's filters, settings, batch buffer and first-match bookkeeping (23+ scan APIs). */
final class LeScanState {
    List<ScanFilter> filters;
    int callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
    long reportDelay;
    boolean active;
    final List<ScanResult> batch = new ArrayList<ScanResult>();
    final Set<String> seen = new HashSet<String>();

    void configure(ScanSettings settings, List<ScanFilter> newFilters) {
        filters = newFilters;
        if (settings != null) {
            callbackType = settings.getCallbackType();
            reportDelay = settings.getReportDelayMillis();
        } else {
            callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
            reportDelay = 0;
        }
        synchronized (batch) { batch.clear(); }
        synchronized (seen) { seen.clear(); }
    }

    /** True when this sighting is the first for its address in this session (CALLBACK_TYPE_FIRST_MATCH). */
    boolean firstSight(String addr) {
        synchronized (seen) { return seen.add(addr); }
    }

    void buffer(ScanResult r) {
        synchronized (batch) { if (batch.size() < 512) batch.add(r); }
    }

    List<ScanResult> drainBatch() {
        synchronized (batch) {
            List<ScanResult> out = new ArrayList<ScanResult>(batch);
            batch.clear();
            return out;
        }
    }
}
