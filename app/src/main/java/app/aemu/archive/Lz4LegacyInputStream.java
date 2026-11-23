package app.aemu.archive;

import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * LZ4 legacy format ({@code lz4 -l}) in plain Java: the fallback of {@link ArcStreams#lz4Legacy} when libaemuarc cannot be loaded
 * (JVM unit tests). Same rules as native/archive/src/c_lz4l.c: concatenated streams, skippable frames, 8 MiB blocks, zero padding
 * behind the stream ends it, a stream cut inside a size field or a block is an {@link EOFException}.
 */
final class Lz4LegacyInputStream extends InputStream {
    private static final int MAGIC = 0x184C2102;
    private static final int BLOCK_MAX = 8 << 20;
    private static final int COMPRESS_BOUND = BLOCK_MAX + BLOCK_MAX / 255 + 16;

    private final InputStream src;
    private byte[] cur = new byte[0];
    private int pos;
    private boolean started, done, closed;

    Lz4LegacyInputStream(InputStream src) { this.src = src; }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("Stream closed");
        if (len == 0) return 0;
        while (pos == cur.length) {
            if (done || !nextBlock()) return -1;
        }
        int n = Math.min(len, cur.length - pos);
        System.arraycopy(cur, pos, b, off, n);
        pos += n;
        return n;
    }

    /** Reads up to 4 bytes; returns how many came (0..4). */
    private int field(byte[] f) throws IOException {
        int n = 0;
        while (n < 4) {
            int r = src.read(f, n, 4 - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static int le32(byte[] f) { return (f[0] & 0xff) | (f[1] & 0xff) << 8 | (f[2] & 0xff) << 16 | (f[3] & 0xff) << 24; }

    private boolean isSkippable(int v) { return (v & 0xfffffff0) == 0x184D2A50; }

    private void skipSource(long n) throws IOException {
        byte[] sink = new byte[8192];
        while (n > 0) {
            int r = src.read(sink, 0, (int) Math.min(sink.length, n));
            if (r < 0) throw new EOFException("Unexpected end of compressed data");
            n -= r;
        }
    }

    /** Decodes the next block into [cur]; false at the end of the stream. */
    private boolean nextBlock() throws IOException {
        byte[] f = new byte[4];
        if (!started) {                                   // the leading magic (or skippable frames in front of it)
            for (;;) {
                int n = field(f);
                if (n == 0) throw new EOFException("Unexpected end of compressed data");
                if (n < 4) throw new EOFException("Unexpected end of compressed data");
                int m = le32(f);
                if (m == MAGIC) break;
                if (!isSkippable(m)) throw new IOException("Corrupt compressed data");
                if (field(f) < 4) throw new EOFException("Unexpected end of compressed data");
                skipSource(le32(f) & 0xffffffffL);
            }
            started = true;
        }
        for (;;) {
            int n = field(f);
            if (n == 0) { done = true; return false; }   // clean end at a block boundary
            if (n < 4) {
                for (int i = 0; i < n; i++) if (f[i] != 0) throw new EOFException("Unexpected end of compressed data");
                done = true; return false;               // 1..3 bytes of zero padding
            }
            int size = le32(f);
            if (size == 0) { done = true; return false; } // zero padding behind the stream
            if (size == MAGIC) continue;                  // concatenated stream
            if (isSkippable(size)) {
                if (field(f) < 4) throw new EOFException("Unexpected end of compressed data");
                skipSource(le32(f) & 0xffffffffL);
                continue;
            }
            if (size < 0 || size > COMPRESS_BOUND) throw new IOException("Corrupt compressed data");
            byte[] block = new byte[size];
            int got = 0;
            while (got < size) {
                int r = src.read(block, got, size - got);
                if (r < 0) throw new EOFException("Unexpected end of compressed data");
                got += r;
            }
            byte[] out;
            try (BlockLZ4CompressorInputStream s = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(block))) {
                out = s.readNBytes(BLOCK_MAX + 1);
            } catch (IOException | RuntimeException e) {
                throw new IOException("Corrupt compressed data", e);
            }
            if (out.length > BLOCK_MAX) throw new IOException("Corrupt compressed data");
            cur = out; pos = 0;
            if (out.length > 0) return true;
        }
    }

    @Override public int available() { return cur.length - pos; }

    @Override public void close() throws IOException {
        closed = true;
        cur = new byte[0]; pos = 0;
        src.close();
    }
}
