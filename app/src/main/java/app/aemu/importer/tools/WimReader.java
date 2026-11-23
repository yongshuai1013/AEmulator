/* Added for AEmulator Plus, 2026-11-15: Windows Imaging (WIM) reader for aow.wim of the MS_ProjectA (Windows 10 Mobile "Astoria") packages. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to a Windows Imaging file ({@code .wim}), written from Microsoft's WIM file format description.
 *
 * <p>Supported: single-part WIMs whose resources are stored, XPRESS-Huffman compressed (what DISM and the Windows 10
 * Mobile servicing packages use) or LZX compressed. Not supported, and reported as such: LZMS / solid resources (.esd),
 * split WIMs, and the "XPRESS2" variant. Only what an Android root file system needs is decoded: the directory tree,
 * the data streams of every file, and the named alternate data streams (the "aow" stream of an Astoria image carries
 * the Unix mode, owner and symlink flag of its file, since NTFS metadata cannot).
 *
 * <p>Metadata (the directory tree) is read into memory; file data is decompressed on demand, chunk by chunk, with
 * {@link #open}.
 */
public final class WimReader implements Closeable {
    private static final int HEADER_SIZE = 208;
    private static final int FLAG_COMPRESSION = 0x2, FLAG_SPANNED = 0x8;
    /** 0x200000 is how WIMBoot-style images (4 KB chunks, as in aow.wim) flag XPRESS; the chunks are the same Huffman XPRESS. */
    private static final int FLAG_XPRESS = 0x20000 | 0x200000, FLAG_LZX = 0x40000, FLAG_LZMS = 0x80000;
    private static final int RES_METADATA = 0x2, RES_COMPRESSED = 0x4, RES_SOLID = 0x10;
    private static final long MAX_METADATA = 256L << 20;

    /** True when {@code head} starts with the WIM signature. */
    public static boolean probe(byte[] head) {
        return head != null && head.length >= 8 && head[0] == 'M' && head[1] == 'S' && head[2] == 'W'
            && head[3] == 'I' && head[4] == 'M' && head[5] == 0 && head[6] == 0 && head[7] == 0;
    }

    /** Where a blob lives in the file, and how it is packed. */
    public static final class Resource {
        final long storedSize, offset, originalSize;
        final int flags;
        Resource(long storedSize, int flags, long offset, long originalSize) {
            this.storedSize = storedSize; this.flags = flags; this.offset = offset; this.originalSize = originalSize;
        }
        public long size() { return originalSize; }
    }

    /** One data stream of a file: the unnamed one, or a named alternate stream. */
    public static final class Stream {
        public final String name;     // "" for the unnamed stream
        public final long size;
        final Resource res;           // null for an empty stream
        Stream(String name, long size, Resource res) { this.name = name; this.size = size; this.res = res; }
    }

    /** A file, directory or other object of an image. */
    public static final class Entry {
        /** Path without a leading slash, '/'-separated; empty for the root. */
        public String path;
        public boolean isDir;
        public int attributes;
        public int reparseTag;
        public long hardLinkGroup;
        /** The unnamed data stream (never null; empty for a directory). */
        public Stream data;
        /** Named alternate data streams, in on-disk order. */
        public final Map<String, Stream> named = new LinkedHashMap<>();
    }

    public interface Visitor {
        /** Directories come before their contents. */
        void visit(Entry e) throws IOException;
    }

    private final RandomAccessFile raf;
    private final int flags;
    private final int chunkSize;
    private final int imageCount;
    private final Map<String, Resource> blobs = new HashMap<>();   // by SHA-1 (hex)
    private final List<Resource> metadata = new ArrayList<>();
    public final long fileSize;

    public WimReader(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        boolean ok = false;
        try {
            fileSize = raf.length();
            byte[] h = new byte[HEADER_SIZE];
            if (fileSize < HEADER_SIZE) throw new IOException("WIM: file is shorter than its header");
            raf.readFully(h);
            if (!probe(h)) throw new IOException("WIM: bad signature");
            flags = (int) u32(h, 16);
            chunkSize = (int) u32(h, 20);
            int part = u16(h, 40), parts = u16(h, 42);
            imageCount = (int) u32(h, 44);
            if ((flags & FLAG_SPANNED) != 0 || parts > 1 || part > 1) throw new IOException("WIM: split WIMs are not supported");
            if ((flags & FLAG_COMPRESSION) != 0) {
                if ((flags & FLAG_LZMS) != 0) throw new IOException("WIM: LZMS-compressed images (.esd) are not supported");
                if ((flags & (FLAG_XPRESS | FLAG_LZX)) == 0) throw new IOException("WIM: unknown compression type");
                if (chunkSize < 4096 || chunkSize > (1 << 26) || (chunkSize & (chunkSize - 1)) != 0)
                    throw new IOException("WIM: bad chunk size " + chunkSize);
                if ((flags & FLAG_LZX) != 0 && chunkSize != 32768) throw new IOException("WIM: LZX chunk size " + chunkSize + " is not supported");
            }
            Resource table = readResHdr(h, 48);
            if (table.originalSize > MAX_METADATA) throw new IOException("WIM: lookup table is too large");
            byte[] t = readAll(table);
            if (t.length % 50 != 0) throw new IOException("WIM: lookup table is cut short");
            for (int p = 0; p + 50 <= t.length; p += 50) {
                Resource r = readResHdr(t, p);
                if ((r.flags & RES_SOLID) != 0) throw new IOException("WIM: solid resources (.esd) are not supported");
                if ((r.flags & RES_METADATA) != 0) { metadata.add(r); continue; }
                if (u16(t, p + 24) != 1) continue;     // another part
                blobs.put(hex(t, p + 30), r);
            }
            ok = true;
        } finally {
            if (!ok) raf.close();
        }
    }

    public int imageCount() { return imageCount; }

    @Override public void close() throws IOException { raf.close(); }

    // ================================================================== tree

    /** Walks image {@code index} (1-based); the root itself is not visited. */
    public void walk(int index, Visitor v) throws IOException {
        if (index < 1 || index > metadata.size()) throw new IOException("WIM: no image " + index);
        Resource r = metadata.get(index - 1);
        if (r.originalSize > MAX_METADATA) throw new IOException("WIM: metadata is too large");
        byte[] m = readAll(r);
        if (m.length < 16) throw new IOException("WIM: metadata is cut short");
        long sec = u32(m, 0);
        long rootOff = sec == 0 ? 8 : (sec + 7) & ~7L;
        Dent root = parseDentry(m, rootOff);
        if (root == null) throw new IOException("WIM: image has no root");
        if (root.subdir != 0) walkChildren(m, root.subdir, "", v, 0);
    }

    private static final class Dent {
        long length, subdir, next;
        int attrs, reparseTag;
        long group;
        String name = "";
        byte[] hash = new byte[20];
        int numAds;
        final List<String> adsNames = new ArrayList<>();
        final List<byte[]> adsHashes = new ArrayList<>();
    }

    private Dent parseDentry(byte[] m, long off) throws IOException {
        if (off < 0 || off + 8 > m.length) throw new IOException("WIM: directory entry outside the metadata");
        int o = (int) off;
        long len = u64(m, o);
        if (len == 0) return null;
        if (len < 102 || off + len > m.length) throw new IOException("WIM: bad directory entry length " + len);
        Dent d = new Dent();
        d.length = len;
        d.attrs = (int) u32(m, o + 8);
        d.subdir = u64(m, o + 16);
        System.arraycopy(m, o + 64, d.hash, 0, 20);
        d.reparseTag = (int) u32(m, o + 84);
        d.group = u64(m, o + 88);
        d.numAds = u16(m, o + 96);
        int shortBytes = u16(m, o + 98), nameBytes = u16(m, o + 100);
        if (102 + nameBytes > len) throw new IOException("WIM: file name outside its directory entry");
        d.name = new String(m, o + 102, nameBytes, StandardCharsets.UTF_16LE);
        long p = off + len;
        for (int i = 0; i < d.numAds; i++) {
            if (p + 40 > m.length) throw new IOException("WIM: alternate stream entry is cut short");
            int q = (int) p;
            long l = u64(m, q);
            int nb = u16(m, q + 36);
            if (l < 38 || p + l > m.length || 38 + nb > l) throw new IOException("WIM: bad alternate stream entry");
            byte[] hs = new byte[20];
            System.arraycopy(m, q + 16, hs, 0, 20);
            d.adsNames.add(new String(m, q + 38, nb, StandardCharsets.UTF_16LE));
            d.adsHashes.add(hs);
            p += (l + 7) & ~7L;
        }
        d.next = p;
        return d;
    }

    private void walkChildren(byte[] m, long off, String prefix, Visitor v, int depth) throws IOException {
        if (depth > 256) throw new IOException("WIM: directory tree is too deep");
        long pos = off;
        while (true) {
            Dent d = parseDentry(m, pos);
            if (d == null) return;
            pos = d.next;
            if (d.name.isEmpty() || d.name.indexOf('/') >= 0 || d.name.equals(".") || d.name.equals("..") || d.name.indexOf('\0') >= 0)
                throw new IOException("WIM: unsafe file name");
            Entry e = new Entry();
            e.path = prefix.isEmpty() ? d.name : prefix + "/" + d.name;
            e.attributes = d.attrs;
            e.isDir = (d.attrs & 0x10) != 0;
            e.reparseTag = d.reparseTag;
            e.hardLinkGroup = d.group;
            Stream unnamed = null;
            for (int i = 0; i < d.numAds; i++) {
                Stream s = stream(d.adsNames.get(i), d.adsHashes.get(i));
                if (d.adsNames.get(i).isEmpty()) unnamed = s; else e.named.put(d.adsNames.get(i), s);
            }
            e.data = !isZero(d.hash) ? stream("", d.hash) : unnamed != null ? unnamed : new Stream("", 0, null);
            v.visit(e);
            if (e.isDir && d.subdir != 0) walkChildren(m, d.subdir, e.path, v, depth + 1);
        }
    }

    private Stream stream(String name, byte[] hash) throws IOException {
        if (isZero(hash)) return new Stream(name, 0, null);
        Resource r = blobs.get(hex(hash, 0));
        if (r == null) throw new IOException("WIM: data of a file is missing from the lookup table");
        return new Stream(name, r.originalSize, r);
    }

    // ================================================================== data

    /** Opens the contents of a stream; read it front to back. */
    public InputStream open(Stream s) throws IOException {
        if (s.res == null || s.size == 0) return new java.io.ByteArrayInputStream(new byte[0]);
        return new ResourceStream(s.res);
    }

    /** The whole of a (small) stream. */
    public byte[] read(Stream s) throws IOException {
        if (s.res == null) return new byte[0];
        if (s.size > MAX_METADATA) throw new IOException("WIM: stream is too large to read into memory");
        return readAll(s.res);
    }

    private byte[] readAll(Resource r) throws IOException {
        byte[] out = new byte[(int) r.originalSize];
        try (InputStream in = new ResourceStream(r)) {
            int n = 0;
            while (n < out.length) {
                int k = in.read(out, n, out.length - n);
                if (k < 0) throw new IOException("WIM: resource ends early");
                n += k;
            }
        }
        return out;
    }

    private final class ResourceStream extends InputStream {
        private final Resource r;
        private final boolean packed;
        private final int nChunks, entry;
        private long[] chunkOff;          // offsets of chunks 1..n-1 from the end of the chunk table
        private long tableEnd;
        private int nextChunk;
        private long produced;            // bytes handed out of the original data
        private long rawPos;              // stored (uncompressed) resources: bytes already read
        private byte[] buf = new byte[0];
        private int bufPos;
        private byte[] inBuf;

        ResourceStream(Resource r) throws IOException {
            this.r = r;
            this.packed = (r.flags & RES_COMPRESSED) != 0 && (flags & FLAG_COMPRESSION) != 0;
            if (packed) {
                nChunks = (int) ((r.originalSize + chunkSize - 1) / chunkSize);
                entry = r.originalSize > 0xFFFFFFFFL ? 8 : 4;
                long tableLen = (long) (nChunks - 1) * entry;
                if (tableLen > r.storedSize) throw new IOException("WIM: chunk table is cut short");
                byte[] t = new byte[(int) tableLen];
                synchronized (raf) { raf.seek(r.offset); raf.readFully(t); }
                chunkOff = new long[nChunks];
                for (int i = 1; i < nChunks; i++) chunkOff[i] = entry == 4 ? u32(t, (i - 1) * 4) : u64(t, (i - 1) * 8);
                tableEnd = r.offset + tableLen;
            } else {
                nChunks = 0; entry = 0;
            }
        }

        private boolean fill() throws IOException {
            if (produced >= r.originalSize) return false;
            if (!packed) {
                int n = (int) Math.min(1 << 16, r.originalSize - rawPos);
                if (buf.length < n) buf = new byte[n];
                synchronized (raf) { raf.seek(r.offset + rawPos); raf.readFully(buf, 0, n); }
                rawPos += n; bufPos = 0; bufLen = n;
                return true;
            }
            int c = nextChunk++;
            int outLen = (int) Math.min(chunkSize, r.originalSize - (long) c * chunkSize);
            long start = chunkOff[c];
            long end = c + 1 < nChunks ? chunkOff[c + 1] : r.storedSize - (tableEnd - r.offset);
            long inLen = end - start;
            if (inLen <= 0 || inLen > chunkSize + 1024L || tableEnd + end > r.offset + r.storedSize)
                throw new IOException("WIM: bad chunk " + c + " of a resource");
            if (inBuf == null || inBuf.length < inLen) inBuf = new byte[(int) inLen];
            synchronized (raf) { raf.seek(tableEnd + start); raf.readFully(inBuf, 0, (int) inLen); }
            if (buf.length < outLen) buf = new byte[chunkSize];
            if (inLen == outLen) {
                System.arraycopy(inBuf, 0, buf, 0, outLen);          // stored chunk
            } else if ((flags & FLAG_XPRESS) != 0) {
                Xpress.decode(inBuf, (int) inLen, buf, outLen);
            } else {
                new LzxDecoder(15, true).decodeFrame(inBuf, (int) inLen, buf, outLen);
            }
            bufPos = 0; bufLen = outLen;
            return true;
        }

        private int bufLen;

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (bufPos >= bufLen && !fill()) return -1;
            int n = Math.min(len, bufLen - bufPos);
            System.arraycopy(buf, bufPos, b, off, n);
            bufPos += n; produced += n;
            return n;
        }
    }

    // ================================================================== XPRESS Huffman

    /** XPRESS with Huffman coding, as used for WIM chunks ("plain LZ77 + Huffman" of MS-XCA). */
    static final class Xpress {
        private Xpress() {}

        static void decode(byte[] in, int inLen, byte[] out, int outLen) throws IOException {
            if (inLen < 256) throw new IOException("XPRESS: chunk shorter than its code table");
            int[] count = new int[16];
            byte[] lens = new byte[512];
            for (int i = 0; i < 256; i++) {
                lens[2 * i] = (byte) (in[i] & 15);
                lens[2 * i + 1] = (byte) ((in[i] >> 4) & 15);
            }
            for (int i = 0; i < 512; i++) count[lens[i]]++;
            count[0] = 0;
            int[] first = new int[17], offs = new int[17];
            int code = 0, idx = 0;
            for (int l = 1; l <= 15; l++) {
                first[l] = code; offs[l] = idx;
                idx += count[l];
                code = (code + count[l]) << 1;
                if (code > (1 << (l + 1))) throw new IOException("XPRESS: over-subscribed Huffman code");
            }
            int[] sorted = new int[512];
            int[] next = offs.clone();
            for (int i = 0; i < 512; i++) if (lens[i] != 0) sorted[next[lens[i]]++] = i;
            final int TB = 10;
            int[] table = new int[1 << TB];
            for (int l = 1; l <= TB; l++) {
                for (int k = 0; k < count[l]; k++) {
                    int c = first[l] + k, sym = sorted[offs[l] + k];
                    for (int t = c << (TB - l), e = (c + 1) << (TB - l); t < e; t++) table[t] = (l << 16) | sym;
                }
            }

            int pos = 256;
            long bitBuf = 0;
            int bits = 0;
            // the bit string is kept at 16..32 bits: two words up front, one more whenever fewer than 16 are left
            for (int i = 0; i < 2; i++) { bitBuf = (bitBuf << 16) | word(in, pos, inLen); pos += 2; bits += 16; }

            int o = 0;
            while (o < outLen) {
                int p15 = (int) ((bitBuf >>> (bits - 15)) & 0x7FFF);
                int e = table[p15 >>> (15 - TB)];
                int sym, used;
                if (e != 0) { sym = e & 0xFFFF; used = e >>> 16; }
                else {
                    sym = -1; used = 0;
                    for (int l = TB + 1; l <= 15; l++) {
                        int d = (p15 >>> (15 - l)) - first[l];
                        if (d >= 0 && d < count[l]) { sym = sorted[offs[l] + d]; used = l; break; }
                    }
                    if (sym < 0) throw new IOException("XPRESS: invalid Huffman code");
                }
                bits -= used; bitBuf &= (1L << bits) - 1;
                if (bits < 16) { bitBuf = (bitBuf << 16) | word(in, pos, inLen); pos += 2; bits += 16; }
                if (sym < 256) { out[o++] = (byte) sym; continue; }
                sym -= 256;
                int len = sym & 15, ob = sym >> 4;
                if (len == 15) {
                    if (pos >= inLen) throw new IOException("XPRESS: input ends inside a match length");
                    len += in[pos++] & 255;
                    if (len == 15 + 255) {
                        if (pos + 2 > inLen) throw new IOException("XPRESS: input ends inside a match length");
                        len = (in[pos] & 255) | (in[pos + 1] & 255) << 8;
                        pos += 2;
                    }
                }
                len += 3;
                int off = 1 << ob;
                if (ob > 0) {
                    off |= (int) ((bitBuf >>> (bits - ob)) & ((1L << ob) - 1));
                    bits -= ob; bitBuf &= (1L << bits) - 1;
                    if (bits < 16) { bitBuf = (bitBuf << 16) | word(in, pos, inLen); pos += 2; bits += 16; }
                }
                if (off > o) throw new IOException("XPRESS: match offset before the start of the chunk");
                if (o + len > outLen) throw new IOException("XPRESS: match runs past the end of the chunk");
                for (int k = 0; k < len; k++, o++) out[o] = out[o - off];
            }
        }

        private static int word(byte[] in, int pos, int inLen) {
            if (pos + 1 < inLen) return (in[pos] & 255) | (in[pos + 1] & 255) << 8;
            if (pos < inLen) return in[pos] & 255;
            return 0;
        }
    }

    // ================================================================== little helpers

    private Resource readResHdr(byte[] b, int o) {
        long size = 0;
        for (int i = 6; i >= 0; i--) size = (size << 8) | (b[o + i] & 255);
        return new Resource(size, b[o + 7] & 255, u64(b, o + 8), u64(b, o + 16));
    }

    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static long u32(byte[] b, int o) { return (b[o] & 255L) | (b[o + 1] & 255L) << 8 | (b[o + 2] & 255L) << 16 | (b[o + 3] & 255L) << 24; }
    private static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }

    private static boolean isZero(byte[] h) {
        for (byte x : h) if (x != 0) return false;
        return true;
    }

    private static String hex(byte[] b, int o) {
        char[] c = new char[40];
        for (int i = 0; i < 20; i++) {
            c[2 * i] = Character.forDigit((b[o + i] >> 4) & 15, 16);
            c[2 * i + 1] = Character.forDigit(b[o + i] & 15, 16);
        }
        return new String(c);
    }
}
