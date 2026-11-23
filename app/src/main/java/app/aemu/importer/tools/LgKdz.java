/* Modified for AEmulator Plus, 2026-11-06: encrypted (RC4 + CAB) LG KDZ and legacy MSTXMETX DZ. */
package app.aemu.importer.tools;

import app.aemu.importer.tools.FirmwareToolset.Artifact;
import app.aemu.archive.ArcStreams;
import app.aemu.importer.tools.FirmwareToolset.Role;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * LG firmware packages: .kdz (container) and .dz (partition chunks inside it).
 *
 * <pre>
 * KDZ   [u32 header size][u32 magic] then records: name[256] + size + offset
 *       (u64 in the 64-bit variants, u32 in the old ones). The table layout is not trusted blindly:
 *       several record layouts are tried and the one whose ranges fit the real file (non-overlapping,
 *       contiguous, summing to the file length) wins, so unknown header-size/magic variants still work.
 * DZ    header (magic 32 96 18 74, major/minor, device, build string) followed by chunks:
 *       magic 30 12 95 78, slice[32], name[64], target_size, data_size, md5[16], start_sector, trim, device, crc.
 *       Chunk data is zlib (all versions seen so far), zstd, lz4-frame, gzip or stored; headers are padded to
 *       0x200 in DZ v2 and tightly packed in the older ones (auto-detected per chunk).
 * </pre>
 *
 * Only what the emulator needs is unpacked: the system, boot and recovery slices, each rebuilt as a raw
 * partition image (chunks are placed at their start sector, gaps stay zero, all-zero blocks are left as
 * file holes). Chunks are streamed, nothing large is held in memory. Older LG BIN/TOT containers that
 * sit inside a KDZ are passed on to the existing {@link FirmwareToolset} LG handlers.
 *
 * <p>The oldest KDZ files (2008-2010 phones) are not this table at all but an RC4-encrypted Microsoft CAB; those are
 * unpacked by {@link LgCab} and the .dz inside goes through the same DZ code ({@link #extractEncryptedKdz}).
 */
final class LgKdz {
    private LgKdz() {}

    static final long DZ_MAGIC = 0x74189632L;               // bytes 32 96 18 74
    static final byte[] DZ_MAGIC_BYTES = {0x32, (byte) 0x96, 0x18, 0x74};
    static final byte[] CHUNK_MAGIC = {0x30, 0x12, (byte) 0x95, 0x78};
    static final int CHUNK_FIXED = 0x8C, CHUNK_PADDED = 0x200, SECTOR = 512;
    static final long[] KDZ_MAGICS = {0x25223824L, 0x20247932L, 0x80253134L};
    private static final long MAX_IMAGE = 64L << 30;

    // ================================================================== detection

    static boolean isDz(byte[] head) { return head.length >= 4 && u32(head, 0) == DZ_MAGIC; }

    /** The older DZ of the 2009-2010 phones ("MSTXMETX"), see {@link LgLegacyDz}. */
    static boolean isLegacyDz(byte[] head) { return LgLegacyDz.isMagic(head); }

    /** RC4-encrypted CAB (old KDZ): the key comes from the file size, so the size is needed along with the first bytes. */
    static boolean isEncryptedKdz(byte[] head, long size) { return LgCab.isEncryptedKdz(head, size); }

    static boolean isEncryptedKdz(File f) { return LgCab.isEncryptedKdz(f); }

    /** The same cabinet after decryption (what kdz_decrypt_encrypt writes as .cab), as long as it is named like one. */
    static boolean isPlainKdzCab(byte[] head, String lowerName) {
        return LgCab.isCabHeader(head) && (lowerName.endsWith(".kdz") || lowerName.endsWith(".cab"));
    }

    /** True if the file's header parses as a KDZ file table (or carries a known KDZ magic). */
    static boolean looksLikeKdz(File f, byte[] head, boolean nameSaysKdz) {
        if (head.length < 0x40) return false;
        long hs = u32(head, 0);
        boolean magic = false;
        for (long m : KDZ_MAGICS) if (u32(head, 4) == m) magic = true;
        if (!magic && !nameSaysKdz && (hs < 0x40 || hs > 0x20000)) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            Table t = readTable(r);
            if (t == null) return magic || nameSaysKdz;
            if (magic || nameSaysKdz) return true;
            for (Entry e : t.files) {
                String n = e.name.toLowerCase(Locale.US);
                if (n.endsWith(".dz") || n.endsWith(".dll") || n.endsWith(".bin")) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    // ================================================================== KDZ container

    static final class Entry { String name; long size, offset; }
    static final class Table { final List<Entry> files = new ArrayList<>(); long headerSize; String layout; int score; }

    /** Parses the file table. Returns null when no record layout fits the file. */
    static Table readTable(RandomAccessFile r) throws IOException {
        long len = r.length();
        byte[] head = readAt(r, 0, (int) Math.min(len, 0x20000));
        if (head.length < 0x40) return null;
        long hs = u32(head, 0);
        boolean hsOk = hs >= 0x40 && hs <= head.length && hs < len;
        int hdrLen = hsOk ? (int) hs : head.length;
        long minOff = hsOk ? hs : 0x10;

        Table best = null;
        int[] starts = {8, 4, 12, 16};
        int[] nameLens = {256, 128};
        int[] widths = {8, 4};
        int[] extras = {0, 4, 8, 16};
        for (int start : starts) for (int nameLen : nameLens) for (int w : widths)
            for (int order = 0; order < 2; order++) for (int extra : extras) {
                Table t = tryLayout(head, hdrLen, len, minOff, hsOk, hs, start, nameLen, w, order == 0, extra);
                if (t != null && (best == null || t.score > best.score)) best = t;
            }
        return best;
    }

    private static Table tryLayout(byte[] head, int hdrLen, long len, long minOff, boolean hsOk, long hs,
                                   int start, int nameLen, int w, boolean sizeFirst, int extra) {
        int stride = nameLen + 2 * w + extra;
        Table t = new Table();
        for (long pos = start; pos + stride <= hdrLen; pos += stride) {
            int p = (int) pos;
            String name = cstrPrintable(head, p, nameLen);
            if (name == null || name.isEmpty()) break;
            long a = w == 8 ? u64(head, p + nameLen) : u32(head, p + nameLen);
            long b = w == 8 ? u64(head, p + nameLen + w) : u32(head, p + nameLen + w);
            long size = sizeFirst ? a : b, off = sizeFirst ? b : a;
            boolean bad = size < 0 || off < minOff || size > len || off + size > len;
            if (bad) { if (t.files.isEmpty()) return null; break; }
            Entry e = new Entry(); e.name = name; e.size = size; e.offset = off;
            t.files.add(e);
        }
        if (t.files.isEmpty()) return null;
        List<Entry> sorted = new ArrayList<>(t.files);
        Collections.sort(sorted, Comparator.comparingLong(e -> e.offset));
        boolean contiguous = true; long sum = hsOk ? hs : 0;
        long expect = hsOk ? hs : sorted.get(0).offset;
        for (Entry e : sorted) {
            if (e.offset < expect && e.size > 0) return null;               // overlapping ranges
            if (e.offset != expect) contiguous = false;
            expect = e.offset + e.size; sum += e.size;
        }
        t.headerSize = hsOk ? hs : 0;
        t.score = t.files.size() * 1000 + (contiguous ? 3000 : 0) + (hsOk && sum == len ? 5000 : 0)
            + (nameLen == 256 ? 10 : 0) + (extra == 0 ? 10 : 0) + (start == 8 ? 10 : 0) + (w == 8 ? 5 : 0) + (sizeFirst ? 1 : 0);
        t.layout = "name" + nameLen + "+" + (w == 8 ? "u64" : "u32") + (sizeFirst ? " size,offset" : " offset,size")
            + " @" + start + (extra != 0 ? " +pad" + extra : "");
        return t;
    }

    static List<Artifact> extractKdz(File f, File work, Predicate<String> want, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            Table t = readTable(r);
            if (t == null) {
                // unknown table layout: the .dz is still recognisable by its own magic
                long at = scanMagic(r, DZ_MAGIC_BYTES);
                if (at < 0) throw new IOException("LG KDZ: unrecognized file table and no DZ payload found");
                log.accept("LG KDZ: unknown table layout, DZ payload found at 0x" + Long.toHexString(at));
                return extractDzRange(r, at, r.length() - at, work, want, log);
            }
            log.accept("LG KDZ: " + t.files.size() + " file(s) [" + t.layout + "]");
            int n = 0;
            for (Entry e : t.files) {
                log.accept("LG KDZ:   " + e.name + " (" + (e.size >> 10) + " KB @0x" + Long.toHexString(e.offset) + ")");
            }
            for (Entry e : t.files) {
                String lower = e.name.toLowerCase(Locale.US);
                byte[] mag = readAt(r, e.offset, (int) Math.min(e.size, 16));
                try {
                    if (isDz(mag) || (lower.endsWith(".dz") && mag.length >= 4)) {
                        out.addAll(extractDzRange(r, e.offset, e.size, new File(work, "dz" + (n++)), want, log));
                    } else if (mag.length >= 4 && isLgBin(u32(mag, 0))) {
                        File dir = new File(work, "bin" + (n++)); dir.mkdirs();
                        File copy = new File(dir, safe(e.name));
                        copyRange(r, e.offset, e.size, copy);
                        try { out.addAll(FirmwareToolset.extract(copy, new File(dir, "out"), want, log)); }
                        catch (IOException | RuntimeException ex) { throw ex; }
                        catch (Exception ex) { throw new IOException(ex); }
                        finally { copy.delete(); }
                    } else if (lower.endsWith(".img") || lower.endsWith(".bin")) {
                        String base = partBase(e.name);
                        if (want.test(base)) {
                            File dir = new File(work, "img" + (n++)); dir.mkdirs();
                            File d = new File(dir, base + ".img");
                            copyRange(r, e.offset, e.size, d);
                            out.add(new Artifact(d, FirmwareToolset.roleFor(base + ".img")));
                        } else {
                            log.accept("LG KDZ: skipping " + e.name);
                        }
                    } else {
                        log.accept("LG KDZ: skipping " + e.name);
                    }
                } catch (IOException | RuntimeException ex) {
                    log.accept("LG KDZ: " + e.name + " failed: " + ex.getMessage());
                    if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
                }
            }
        }
        out = firstPerName(out);
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG KDZ: no system/boot/recovery image inside");
        }
        return out;
    }

    // ================================================================== encrypted (CAB) KDZ

    /**
     * Old KDZ: decrypts on the fly while reading the cabinet (no decrypted copy of the whole file), pulls out the
     * firmware payloads and hands each to the DZ / LG BIN / raw image handling used for the newer KDZ files.
     * Also accepts the already decrypted cabinet.
     */
    static List<Artifact> extractEncryptedKdz(File f, File work, Predicate<String> want, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        byte[] head = readHead(f, 12);
        boolean encrypted = isEncryptedKdz(head, f.length());
        byte[] key = encrypted ? LgCab.deriveKey(f.length()) : null;
        if (!encrypted && !LgCab.isCabHeader(head)) throw new IOException("LG KDZ: not an encrypted KDZ / CAB file");
        log.accept("LG KDZ: " + (encrypted ? "RC4-encrypted CAB" : "plain CAB") + ", " + (f.length() >> 20) + " MB");

        File cabDir = new File(work, "cab");
        List<File> members;
        long dllSize = -1;
        try (LgCab.Src src = encrypted ? LgCab.decrypting(f, key) : LgCab.plain(f)) {
            LgCab.Cab cab = LgCab.parse(src);
            for (LgCab.Member m : cab.members) {
                log.accept("LG KDZ:   " + m.name + " (" + (m.size >> 10) + " KB)");
            }
            // LGUP "WebDLL" packages (Optimus L9 II / D605 ...): the payload is a .wdb whose key comes from the size of the
            // package's own LGUP_*.DLL (see decryptWdb); only that size is needed, the DLL itself is not unpacked
            for (LgCab.Member m : cab.members) if (m.name.toLowerCase(Locale.US).endsWith(".dll")) dllSize = m.size;
            members = LgCab.extract(src, cab, cabDir, LgKdz::wantCabMember, log);
        }
        if (members.isEmpty()) throw new IOException("LG KDZ: the cabinet holds no firmware payload");

        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        int n = 0;
        for (File m : members) {
            String lower = m.getName().toLowerCase(Locale.US);
            byte[] mag = readHead(m, 16);
            try {
                if (lower.endsWith(".wdb")) {
                    File dir = new File(work, "wdb" + (n++)); dir.mkdirs();
                    File tot = new File(dir, m.getName().replaceFirst("(?i)\\.wdb$", "") + ".tot");
                    decryptWdb(m, dllSize, tot, log);
                    m.delete();                               // the encrypted copy is not needed any more
                    try { out.addAll(FirmwareToolset.extract(tot, new File(dir, "out"), want, log)); }
                    catch (IOException | RuntimeException ex) { throw ex; }
                    catch (Exception ex) { throw new IOException(ex); }
                    finally { tot.delete(); }
                } else if (isDz(mag) || isLegacyDz(mag) || (lower.endsWith(".dz") && mag.length >= 4)) {
                    out.addAll(extractDz(m, new File(work, "dz" + (n++)), want, log));
                } else if (mag.length >= 4 && isLgBin(u32(mag, 0))) {
                    File dir = new File(work, "bin" + (n++)); dir.mkdirs();
                    try { out.addAll(FirmwareToolset.extract(m, new File(dir, "out"), want, log)); }
                    catch (IOException | RuntimeException ex) { throw ex; }
                    catch (Exception ex) { throw new IOException(ex); }
                } else if (lower.endsWith(".img") || lower.endsWith(".bin")) {
                    String base = partBase(m.getName());
                    if (want.test(base)) {
                        File dir = new File(work, "img" + (n++)); dir.mkdirs();
                        File d = new File(dir, base + ".img");
                        if (m.renameTo(d)) out.add(new Artifact(d, FirmwareToolset.roleFor(base + ".img")));
                    } else {
                        log.accept("LG KDZ: skipping " + m.getName());
                    }
                } else {
                    log.accept("LG KDZ: skipping " + m.getName());
                }
            } catch (IOException | RuntimeException ex) {
                log.accept("LG KDZ: " + m.getName() + " failed: " + ex.getMessage());
                if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
            } finally {
                m.delete();                                   // the unpacked copy is no longer needed once its images exist
            }
        }
        deleteTree(cabDir);
        out = firstPerName(out);
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG KDZ: no system/boot/recovery image inside");
        }
        return out;
    }

    // ================================================================== LGUP .wdb (WebDLL packages)

    /**
     * An LGUP ".wdb" (the payload of the 2012-2015 Optimus-era KDZ cabinets, next to LGUP_xxx.DLL and a .wdh), unpacked to the
     * LG .tot it carries. Layout, as LGExtract 0.2.1 reads it:
     * <pre>
     *   u32 n, n bytes   container header   (RC4)  u32 size, u32 count, name strings
     *   u32 n, u32, n-4  file header         (RC4 from the 2nd field on)
     *   u32              size of the unpacked file
     *   ...              the file: zlib stream, the whole of it one RC4 stream (a footer follows and is ignored)
     * </pre>
     * Every RC4 stream starts afresh with the same key: MD5 of a password made from the decimal size of the package's DLL, digit
     * by digit {@code (digit char + {1,1,3,8,4,1}[i] + '0') & 255} (LGExtract warns above six digits and so does this).
     */
    static void decryptWdb(File wdb, long dllSize, File out, Consumer<String> log) throws IOException {
        if (dllSize <= 0) throw new IOException("LG WDB: the package has no .dll to take the key from");
        String sz = Long.toString(dllSize);
        if (sz.length() > 6) throw new IOException("LG WDB: the key of a " + sz.length() + "-digit DLL size is not known");
        final int[] add = {1, 1, 3, 8, 4, 1};
        byte[] pw = new byte[sz.length()];
        for (int i = 0; i < pw.length; i++) pw[i] = (byte) (sz.charAt(i) + add[i] + 0x30);
        final byte[] key;
        try { key = MessageDigest.getInstance("MD5").digest(pw); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }

        try (RandomAccessFile r = new RandomAccessFile(wdb, "r")) {
            byte[] b4 = new byte[4];
            r.readFully(b4);
            long hl = u32(b4, 0);
            if (hl < 16 || hl > 4096) throw new IOException("LG WDB: bad container header");
            byte[] hdr = new byte[(int) hl];
            r.readFully(hdr);
            new LgCab.Rc4(key).crypt(hdr, 0, hdr.length);
            if (u32(hdr, 0) != hl || u32(hdr, 4) < 1 || u32(hdr, 4) > 64) {
                throw new IOException("LG WDB: the container header does not decrypt (wrong key: DLL size " + dllSize + ")");
            }
            long files = u32(hdr, 4);
            if (files > 1) log.accept("LG WDB: " + files + " files listed, only the first is unpacked");
            r.readFully(b4);
            long fl = u32(b4, 0);
            if (fl < 8 || fl > 65536) throw new IOException("LG WDB: bad file header");
            r.seek(r.getFilePointer() + fl);                  // file header: nothing in it is needed
            r.readFully(b4);                                  // unpacked size
            final long unpacked = u32(b4, 0);
            log.accept("LG WDB: " + (unpacked >> 20) + " MB unpacked payload");

            final LgCab.Rc4 rc = new LgCab.Rc4(key);
            InputStream raw = new BufferedInputStream(new java.io.FileInputStream(r.getFD()), 1 << 20);
            InputStream dec = new java.io.FilterInputStream(raw) {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int k = in.read(b, off, len);
                    if (k > 0) rc.crypt(b, off, k);
                    return k;
                }
            };
            long total = 0;
            try (InputStream z = new InflaterInputStream(dec, new Inflater(), 1 << 16);
                 java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out), 1 << 20)) {
                byte[] buf = new byte[1 << 20];
                for (int k; (k = z.read(buf)) > 0; ) { o.write(buf, 0, k); total += k; }
            } catch (java.util.zip.ZipException e) {
                throw new IOException("LG WDB: payload does not inflate (" + e.getMessage() + ")");
            }
            if (total < 4) throw new IOException("LG WDB: empty payload");
            if (total != unpacked) log.accept("LG WDB: warning, " + total + " bytes unpacked, the header says " + unpacked);
        }
    }

    /** What is worth unpacking from the cabinet: the firmware containers by name, and anything big enough to be one. */
    private static boolean wantCabMember(LgCab.Member m) {
        String n = m.name.toLowerCase(Locale.US);
        return n.endsWith(".dz") || n.endsWith(".wdb") || n.endsWith(".bin") || n.endsWith(".tot") || n.endsWith(".img") || m.size >= (1L << 20);
    }

    private static byte[] readHead(File f, int n) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { return readAt(r, 0, (int) Math.min(n, r.length())); }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ================================================================== DZ

    static final class Chunk {
        String slice, name; long targetSize, dataSize, addr; byte[] md5; long pos, dataPos;
    }

    static List<Artifact> extractDz(File f, File work, Predicate<String> want, Consumer<String> log) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return extractDzRange(r, 0, r.length(), work, want, log);
        }
    }

    static List<Artifact> extractDzRange(RandomAccessFile r, long base, long len, File work, Predicate<String> want, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        byte[] hd = readAt(r, base, (int) Math.min(len, 0xC0));
        if (isLegacyDz(hd)) return LgLegacyDz.extract(r, base, len, work, log);
        if (!isDz(hd)) throw new IOException("LG DZ: bad magic");
        long major = u32(hd, 4), minor = u32(hd, 8);
        String device = hd.length >= 0x30 ? cstrPrintable(hd, 0x10, 32) : null;
        String build = hd.length >= 0xC0 ? cstrPrintable(hd, 0x30, 144) : null;
        List<Chunk> chunks = readChunks(r, base, len);
        Map<String, List<Chunk>> slices = new LinkedHashMap<>();
        for (Chunk c : chunks) slices.computeIfAbsent(c.slice.toLowerCase(Locale.US), k -> new ArrayList<>()).add(c);
        StringBuilder names = new StringBuilder();
        for (Map.Entry<String, List<Chunk>> e : slices.entrySet()) names.append(names.length() > 0 ? ", " : "").append(e.getKey()).append('(').append(e.getValue().size()).append(')');
        log.accept("LG DZ v" + major + "." + minor + (device != null ? " " + device : "") + (build != null && !build.isEmpty() ? " " + build : "")
            + ": " + chunks.size() + " chunks: " + names);

        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        // every slice the guest can use, not just system / boot / recovery / oem: a DZ of an Android 10+ phone keeps system_ext, vendor,
        // product and odm inside "super" (or as slices of their own). What is wanted is decided by [want] (the importer's own rules);
        // of an A/B pair the "_a" slice (or the unsuffixed one) is taken.
        Map<String, String> best = new LinkedHashMap<>();
        for (String key : slices.keySet()) {
            String pb = partBase(key);
            if (!want.test(pb)) { log.accept("LG DZ: skipping slice " + key); continue; }
            String cur = best.get(pb);
            if (cur == null || slotRank(key) < slotRank(cur)) best.put(pb, key);
        }
        for (Map.Entry<String, String> be : best.entrySet()) {
            String pb = be.getKey(), used = be.getValue();
            File dst = new File(work, pb + ".img");
            try {
                assemble(r, slices.get(used), dst, log);
                out.add(new Artifact(dst, FirmwareToolset.roleFor(pb + ".img")));
                log.accept("LG DZ: " + used + " -> " + pb + ".img (" + (dst.length() >> 20) + " MB)");
            } catch (IOException | RuntimeException ex) {
                dst.delete();
                log.accept("LG DZ: " + used + " failed: " + ex.getMessage());
                if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
            }
        }
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG DZ: no system/boot/recovery slice (found: " + names + ")");
        }
        return out;
    }

    /** Walks every chunk header of the DZ. The first one is located by magic, so any DZ header size works. */
    static List<Chunk> readChunks(RandomAccessFile r, long base, long len) throws IOException {
        long end = base + len;
        byte[] buf = readAt(r, base, (int) Math.min(len, 4L << 20));
        long first = -1;
        for (int i = 0; i + CHUNK_FIXED <= buf.length; i++) {
            if (buf[i] == CHUNK_MAGIC[0] && buf[i + 1] == CHUNK_MAGIC[1] && buf[i + 2] == CHUNK_MAGIC[2] && buf[i + 3] == CHUNK_MAGIC[3]
                && parseChunk(r, base + i, end) != null) { first = base + i; break; }
        }
        if (first < 0) throw new IOException("LG DZ: no chunk table found");
        List<Chunk> list = new ArrayList<>();
        long pos = first;
        while (list.size() < 200000) {
            Chunk c = parseChunk(r, pos, end);
            if (c == null) break;
            // padded (0x200) or tight (0x8C) header: take the one after which the next chunk header is found
            byte[] tail = readAt(r, pos + CHUNK_FIXED, CHUNK_PADDED - CHUNK_FIXED);
            boolean zeros = tail.length == CHUNK_PADDED - CHUNK_FIXED && allZero(tail, 0, tail.length);
            int[] cands = zeros ? new int[]{CHUNK_PADDED, CHUNK_FIXED} : new int[]{CHUNK_FIXED, CHUNK_PADDED};
            int hdr = -1; long next = -1;
            for (int h : cands) {
                long dEnd = pos + h + c.dataSize;
                if (dEnd > end) continue;
                long nx = findNext(r, base, dEnd, end);
                if (nx >= 0 || end - dEnd < 0x4000) { hdr = h; next = nx; break; }
            }
            if (hdr < 0) { hdr = cands[0]; if (pos + hdr + c.dataSize > end) break; }
            c.pos = pos; c.dataPos = pos + hdr;
            list.add(c);
            if (next < 0) break;
            pos = next;
        }
        if (list.isEmpty()) throw new IOException("LG DZ: no chunks");
        return list;
    }

    private static Chunk parseChunk(RandomAccessFile r, long pos, long end) throws IOException {
        if (pos < 0 || pos + CHUNK_FIXED > end) return null;
        byte[] h = readAt(r, pos, CHUNK_FIXED);
        if (h.length < CHUNK_FIXED || h[0] != CHUNK_MAGIC[0] || h[1] != CHUNK_MAGIC[1] || h[2] != CHUNK_MAGIC[2] || h[3] != CHUNK_MAGIC[3]) return null;
        String slice = cstrPrintable(h, 4, 32), name = cstrPrintable(h, 36, 64);
        if (slice == null || slice.isEmpty() || name == null) return null;
        Chunk c = new Chunk();
        c.slice = slice; c.name = name;
        c.targetSize = u32(h, 0x64); c.dataSize = u32(h, 0x68);
        c.md5 = Arrays.copyOfRange(h, 0x6C, 0x7C);
        c.addr = u32(h, 0x7C);
        if (c.dataSize <= 0 || c.dataSize > end - pos - CHUNK_FIXED) return null;
        return c;
    }

    private static long findNext(RandomAccessFile r, long base, long from, long end) throws IOException {
        long rel = from - base;
        long[] cand = {from, base + ((rel + 0x1FF) & ~0x1FFL), base + ((rel + 0xFFF) & ~0xFFFL)};
        for (long c : cand) if (parseChunk(r, c, end) != null) return c;
        int win = (int) Math.min(0x2000, end - from);
        if (win >= CHUNK_FIXED) {
            byte[] b = readAt(r, from, win);
            for (int i = 0; i + CHUNK_FIXED <= b.length; i++)
                if (b[i] == CHUNK_MAGIC[0] && b[i + 1] == CHUNK_MAGIC[1] && b[i + 2] == CHUNK_MAGIC[2] && b[i + 3] == CHUNK_MAGIC[3]
                    && parseChunk(r, from + i, end) != null) return from + i;
        }
        return -1;
    }

    /** Rebuilds one partition image: every chunk is decompressed to its start sector (relative to the slice's first). */
    private static void assemble(RandomAccessFile r, List<Chunk> cl, File dst, Consumer<String> log) throws IOException {
        int n = cl.size();
        long[] at = new long[n];
        boolean sequential = n == 1;
        if (n > 1) {
            sequential = true;
            for (Chunk c : cl) if (c.addr != cl.get(0).addr) { sequential = false; break; }
        }
        long total = 0;
        if (sequential) {
            long cur = 0;
            for (int i = 0; i < n; i++) { at[i] = cur; cur += cl.get(i).targetSize; }
            total = cur;
        } else {
            long min = Long.MAX_VALUE;
            for (Chunk c : cl) min = Math.min(min, c.addr);
            for (int i = 0; i < n; i++) {
                at[i] = (cl.get(i).addr - min) * SECTOR;
                total = Math.max(total, at[i] + cl.get(i).targetSize);
            }
        }
        if (total > MAX_IMAGE) throw new IOException("image size implausible: " + total);
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (RandomAccessFile w = new RandomAccessFile(dst, "rw")) {
            w.setLength(total);
            for (int i = 0; i < n; i++) {
                Chunk c = cl.get(i);
                long wrote = decodeChunk(r, c, w, at[i], log);
                if (wrote != c.targetSize) log.accept("LG DZ: " + c.name + " expanded to " + wrote + " bytes, header says " + c.targetSize);
            }
        }
    }

    private static long decodeChunk(RandomAccessFile r, Chunk c, RandomAccessFile w, long dstOff, Consumer<String> log) throws IOException {
        try (BufferedInputStream raw = new BufferedInputStream(new RangeIn(r, c.dataPos, c.dataSize), 1 << 20)) {
            raw.mark(8);
            byte[] m = new byte[4];
            int got = 0;
            while (got < 4) { int k = raw.read(m, got, 4 - got); if (k < 0) break; got += k; }
            raw.reset();
            int b0 = m[0] & 255, b1 = m[1] & 255;
            InputStream in;
            if (got >= 2 && (b0 & 0x0F) == 8 && ((b0 << 8) | b1) % 31 == 0) in = new InflaterInputStream(raw, new Inflater(), 1 << 16);
            else if (got >= 4 && b0 == 0x28 && b1 == 0xB5 && (m[2] & 255) == 0x2F && (m[3] & 255) == 0xFD) in = ArcStreams.zstd(raw);
            else if (got >= 4 && b0 == 0x04 && b1 == 0x22 && (m[2] & 255) == 0x4D && (m[3] & 255) == 0x18) in = ArcStreams.lz4(raw);
            else if (got >= 2 && b0 == 0x1F && b1 == 0x8B) in = ArcStreams.gzip(raw);
            else if (c.dataSize == c.targetSize) in = raw;              // stored
            else throw new IOException("chunk " + c.name + ": unknown compression");
            MessageDigest md;
            try { md = MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IOException(e); }
            long wrote = copyTo(in, w, dstOff, md);
            if (!allZero(c.md5, 0, c.md5.length) && !Arrays.equals(md.digest(), c.md5))
                log.accept("LG DZ: " + c.name + " MD5 mismatch (image may be corrupt)");
            return wrote;
        }
    }

    /** Streams into the output; all-zero 4 KB blocks are skipped (the pre-sized file already reads zero there). */
    private static long copyTo(InputStream in, RandomAccessFile w, long dst, MessageDigest md) throws IOException {
        byte[] buf = new byte[1 << 20];
        long pos = dst; int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) {
            md.update(buf, 0, n);
            int run = -1;
            for (int i = 0; i < n; i += 4096) {
                int blk = Math.min(4096, n - i);
                if (allZero(buf, i, blk)) {
                    if (run >= 0) { w.seek(pos + run); w.write(buf, run, i - run); run = -1; }
                } else if (run < 0) run = i;
            }
            if (run >= 0) { w.seek(pos + run); w.write(buf, run, n - run); }
            pos += n;
        }
        return pos - dst;
    }

    // ================================================================== helpers

    static final class RangeIn extends InputStream {
        private final RandomAccessFile r; private long pos; private final long end;
        RangeIn(RandomAccessFile r, long off, long len) { this.r = r; pos = off; end = off + len; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (pos >= end) return -1;
            int n = (int) Math.min(l, end - pos);
            r.seek(pos);
            int k = r.read(b, o, n);
            if (k > 0) pos += k;
            return k;
        }
    }

    /** One artifact per partition file name: the same partition found twice (two DZ files, a KDZ table entry and a slice) is kept once. */
    private static List<Artifact> firstPerName(List<Artifact> in) {
        List<Artifact> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Artifact a : in) {
            if (!seen.add(a.file.getName().toLowerCase(Locale.US))) continue;
            out.add(a);
        }
        return out;
    }

    /** "system_a" / "systema" / "system.img" -> "system", "kernel" -> "boot": the partition a slice or table entry is, whatever it is called. */
    static String partBase(String n) {
        String x = n.toLowerCase(Locale.US);
        int slash = Math.max(x.lastIndexOf('/'), x.lastIndexOf('\\'));
        if (slash >= 0) x = x.substring(slash + 1);
        if (x.endsWith(".img") || x.endsWith(".bin")) x = x.substring(0, x.length() - 4);
        switch (x) {
            case "kernel": case "boota": case "bootb": return "boot";
            case "systema": case "systemb": return "system";
            case "recoverya": case "recoveryb": return "recovery";
            case "oema": case "oemb": return "oem";
            default: break;
        }
        if (x.endsWith("_a") || x.endsWith("_b")) x = x.substring(0, x.length() - 2);
        return x;
    }

    /** 1 for the inactive slot ("_b"), 0 otherwise. */
    static int slotRank(String n) {
        String x = n.toLowerCase(Locale.US);
        if (x.endsWith(".img") || x.endsWith(".bin")) x = x.substring(0, x.length() - 4);
        return x.endsWith("_b") || x.equals("systemb") || x.equals("bootb") || x.equals("recoveryb") || x.equals("oemb") ? 1 : 0;
    }

    private static boolean isLgBin(long m) { return m == 0xAA55EC44L || m == 0xAA55A5A5L || m == 0xAA55DD44L; }

    private static long scanMagic(RandomAccessFile r, byte[] magic) throws IOException {
        byte[] buf = new byte[(1 << 20) + 3];
        long pos = 0, len = r.length();
        while (pos < len) {
            r.seek(pos);
            int n = r.read(buf, 0, (int) Math.min(buf.length, len - pos));
            if (n <= 0) break;
            for (int i = 0; i + magic.length <= n; i++) {
                int j = 0; while (j < magic.length && buf[i + j] == magic[j]) j++;
                if (j == magic.length) return pos + i;
            }
            if (pos + n >= len) break;
            pos += n - (magic.length - 1);
        }
        return -1;
    }

    private static void copyRange(RandomAccessFile r, long off, long len, File dst) throws IOException {
        try (java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dst), 1 << 20)) {
            byte[] b = new byte[1 << 20]; long left = len; r.seek(off);
            while (left > 0) {
                int k = r.read(b, 0, (int) Math.min(b.length, left));
                if (k <= 0) throw new IOException("unexpected end of file");
                o.write(b, 0, k); left -= k;
            }
        }
    }

    private static String safe(String n) {
        String s = n.replace('\\', '/'); s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "entry" : s;
    }

    /** NUL-terminated printable ASCII inside a fixed field, or null when the field is not a clean name. */
    private static String cstrPrintable(byte[] b, int off, int max) {
        if (off + max > b.length) max = b.length - off;
        int e = 0;
        while (e < max && b[off + e] != 0) { int c = b[off + e] & 255; if (c < 0x20 || c > 0x7E) return null; e++; }
        if (e == max && max > 0 && b[off + max - 1] != 0) return null;       // no terminator inside the field
        return new String(b, off, e, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean allZero(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) if (b[i] != 0) return false;
        return true;
    }

    static byte[] readAt(RandomAccessFile r, long pos, int n) throws IOException {
        if (n <= 0 || pos < 0) return new byte[0];
        r.seek(pos);
        byte[] b = new byte[n]; int got = 0;
        while (got < n) { int k = r.read(b, got, n - got); if (k < 0) break; got += k; }
        return got == n ? b : Arrays.copyOf(b, got);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 255L) | (b[o + 1] & 255L) << 8 | (b[o + 2] & 255L) << 16 | (b[o + 3] & 255L) << 24;
    }

    static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }
}
