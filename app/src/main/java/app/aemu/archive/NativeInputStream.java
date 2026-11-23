package app.aemu.archive;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Decompressing stream over libaemuarc. Output goes straight into the caller's array: no copy on the Java side. */
final class NativeInputStream extends InputStream {
    private static final int IN_CAP = 1 << 18;

    private final InputStream src;
    private final int codec;
    private long h;
    private final byte[] in = new byte[IN_CAP];
    private int inPos, inLen;
    private boolean srcEof, done;
    private final int[] res = new int[3];
    private byte[] one;           // staging for tiny reads
    private byte[] stage;
    private int stagePos, stageLen;

    NativeInputStream(int codec, InputStream src) throws IOException {
        this.codec = codec;
        this.src = src;
        this.h = NativeArc.decOpen(codec);
    }

    @Override public int read() throws IOException {
        if (one == null) one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        if (h == 0) throw new IOException("Stream closed");
        if (len < 512) {                               // small reads are served from a staging buffer
            if (stagePos == stageLen) {
                if (stage == null) stage = new byte[1 << 16];
                int n = pump(stage, 0, stage.length);
                if (n < 0) return -1;
                stagePos = 0; stageLen = n;
            }
            int n = Math.min(len, stageLen - stagePos);
            System.arraycopy(stage, stagePos, b, off, n);
            stagePos += n;
            return n;
        }
        if (stagePos < stageLen) {
            int n = Math.min(len, stageLen - stagePos);
            System.arraycopy(stage, stagePos, b, off, n);
            stagePos += n;
            return n;
        }
        return pump(b, off, len);
    }

    private int pump(byte[] b, int off, int len) throws IOException {
        if (done) return -1;
        for (;;) {
            NativeArc.decStep(h, in, inPos, inLen - inPos, b, off, len, srcEof, res);
            int status = res[0], used = res[1], made = res[2];
            inPos += used;
            if (status < 0) throw error(status);
            if (status == NativeArc.END) {
                done = true;
                return made > 0 ? made : -1;
            }
            if (made > 0) return made;
            if (used > 0) continue;
            if (srcEof) throw new EOFException("Unexpected end of compressed data");
            fill();
        }
    }

    /** Appends source bytes behind what the decoder has not consumed yet. */
    private void fill() throws IOException {
        int rem = inLen - inPos;
        if (inPos > 0 && rem > 0) System.arraycopy(in, inPos, in, 0, rem);
        inPos = 0; inLen = rem;
        if (inLen == in.length) throw new IOException("Corrupt compressed data");
        int n = src.read(in, inLen, in.length - inLen);
        if (n < 0) srcEof = true; else inLen += n;
    }

    private IOException error(int status) {
        switch (status) {
            case -2: return new IOException(new OutOfMemoryError("native decoder out of memory"));
            case -3: return new IOException("Unsupported compression method");
            case -4: return new EOFException("Unexpected end of compressed data");
            default: return new IOException("Corrupt compressed data");
        }
    }

    @Override public int available() { return stageLen - stagePos; }

    @Override public void close() throws IOException {
        long x = h;
        h = 0;
        if (x != 0) NativeArc.decClose(x);
        src.close();
    }

    @SuppressWarnings({"deprecation", "FinalizeDeclaration"})
    @Override protected void finalize() { long x = h; h = 0; if (x != 0) NativeArc.decClose(x); }
}
