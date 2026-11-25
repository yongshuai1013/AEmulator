package app.aemu.stub;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LE advertising helpers for the 23+ scan APIs: ScanResult construction (ScanRecord.parseFromBytes is
 * hidden API, reached by reflection like the rest of the plumbing) and ScanFilter matching against the
 * raw advertising record. A filter aspect we cannot parse out of the record counts as matching, so an
 * exotic filter degrades to "deliver" instead of silently swallowing every result.
 */
final class ScanHelp {
    private static Method parseFromBytes;
    private static boolean parseFailed;

    private ScanHelp() { }

    static ScanRecord parseRecord(byte[] record) {
        if (record == null) return null;
        try {
            synchronized (ScanHelp.class) {
                if (parseFromBytes == null && !parseFailed) {
                    try { parseFromBytes = ScanRecord.class.getMethod("parseFromBytes", byte[].class); }
                    catch (Throwable e) { parseFailed = true; BtLog.w("ScanRecord.parseFromBytes unavailable", e); }
                }
            }
            if (parseFromBytes == null) return null;
            return (ScanRecord) parseFromBytes.invoke(null, record);
        } catch (Throwable e) {
            return null;
        }
    }

    static ScanResult makeResult(String addr, int rssi, byte[] record) {
        try {
            return new ScanResult(BtUtil.device(addr), parseRecord(record), rssi, SystemClock.elapsedRealtimeNanos());
        } catch (Throwable e) {
            BtLog.w("cannot build a ScanResult", e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // advertising record parsing

    static final class Adv {
        final List<UUID> uuids = new ArrayList<UUID>();
        String name;
        int manuId = -1;
        byte[] manuData;
        UUID svcDataUuid;
        byte[] svcData;
    }

    static Adv parseAdv(byte[] rec) {
        Adv adv = new Adv();
        if (rec == null) return adv;
        int i = 0;
        while (i + 1 < rec.length) {
            int len = rec[i] & 255;
            if (len == 0 || i + 1 + len > rec.length) break;
            int type = rec[i + 1] & 255;
            int start = i + 2, end = i + 1 + len;
            switch (type) {
                case 0x02: case 0x03:
                    for (int p = start; p + 2 <= end; p += 2) adv.uuids.add(uuid16(le16(rec, p)));
                    break;
                case 0x04: case 0x05:
                    for (int p = start; p + 4 <= end; p += 4) adv.uuids.add(uuid32(le32(rec, p)));
                    break;
                case 0x06: case 0x07:
                    for (int p = start; p + 16 <= end; p += 16) adv.uuids.add(uuid128(rec, p));
                    break;
                case 0x08: case 0x09:
                    try { adv.name = new String(rec, start, end - start, "UTF-8"); } catch (Exception ignored) { }
                    break;
                case 0xFF:
                    if (end - start >= 2) {
                        adv.manuId = le16(rec, start);
                        adv.manuData = slice(rec, start + 2, end);
                    }
                    break;
                case 0x16:
                    if (end - start >= 2) { adv.svcDataUuid = uuid16(le16(rec, start)); adv.svcData = slice(rec, start + 2, end); }
                    break;
                case 0x20:
                    if (end - start >= 4) { adv.svcDataUuid = uuid32(le32(rec, start)); adv.svcData = slice(rec, start + 4, end); }
                    break;
                case 0x21:
                    if (end - start >= 16) { adv.svcDataUuid = uuid128(rec, start); adv.svcData = slice(rec, start + 16, end); }
                    break;
                default: break;
            }
            i += len + 1;
        }
        return adv;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    private static int le16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }

    private static long le32(byte[] b, int at) {
        long v = 0;
        for (int k = 0; k < 4; k++) v |= (long) (b[at + k] & 255) << (8 * k);
        return v;
    }

    static UUID uuid16(int v) { return new UUID((v << 32) | 0x1000L, 0x800000805F9B34FBL); }
    static UUID uuid32(long v) { return new UUID((v << 32) | 0x1000L, 0x800000805F9B34FBL); }

    static UUID uuid128(byte[] b, int at) {
        long hi = 0, lo = 0;
        for (int k = 0; k < 8; k++) { lo = (lo << 8) | (b[at + 7 - k] & 255); hi = (hi << 8) | (b[at + 15 - k] & 255); }
        return new UUID(hi, lo);
    }

    // ---------------------------------------------------------------------------------------------------------
    // filter matching (AOSP semantics: a result is delivered when it matches at least one filter)

    static boolean matchesAny(List<ScanFilter> filters, String addr, byte[] record) {
        if (filters == null || filters.isEmpty()) return true;
        Adv adv = null;
        for (ScanFilter f : filters) {
            if (f == null) return true;
            if (adv == null) adv = parseAdv(record);
            if (matches(f, addr, adv)) return true;
        }
        return false;
    }

    private static boolean matches(ScanFilter f, String addr, Adv adv) {
        String fa = f.getDeviceAddress();
        if (fa != null && !fa.equalsIgnoreCase(addr)) return false;
        String fn = f.getDeviceName();
        if (fn != null && !fn.equals(adv.name)) return false;
        ParcelUuid su = f.getServiceUuid();
        if (su != null) {
            ParcelUuid mask = f.getServiceUuidMask();
            boolean hit = false;
            for (UUID u : adv.uuids) if (uuidMatches(u, su.getUuid(), mask == null ? null : mask.getUuid())) { hit = true; break; }
            if (!hit) return false;
        }
        if (f.getManufacturerId() >= 0) {
            if (adv.manuId != f.getManufacturerId()) return false;
            if (!dataMatches(adv.manuData, f.getManufacturerData(), f.getManufacturerDataMask())) return false;
        }
        ParcelUuid sd = f.getServiceDataUuid();
        if (sd != null) {
            if (adv.svcDataUuid == null || !adv.svcDataUuid.equals(sd.getUuid())) return false;
            if (!dataMatches(adv.svcData, f.getServiceData(), f.getServiceDataMask())) return false;
        }
        return true;
    }

    private static boolean uuidMatches(UUID adv, UUID filter, UUID mask) {
        if (mask == null) return adv.equals(filter);
        return (adv.getMostSignificantBits() & mask.getMostSignificantBits()) == (filter.getMostSignificantBits() & mask.getMostSignificantBits())
                && (adv.getLeastSignificantBits() & mask.getLeastSignificantBits()) == (filter.getLeastSignificantBits() & mask.getLeastSignificantBits());
    }

    private static boolean dataMatches(byte[] advData, byte[] filterData, byte[] mask) {
        if (filterData == null) return true;
        if (advData == null || advData.length < filterData.length) return false;
        for (int i = 0; i < filterData.length; i++) {
            int m = mask != null && i < mask.length ? mask[i] & 255 : 255;
            if ((advData[i] & m) != (filterData[i] & m)) return false;
        }
        return true;
    }
}
