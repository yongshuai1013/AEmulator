/* Added for AEmulator Plus, 2026-11-15: Microsoft LZX decompression (CAB folders of Windows servicing packages). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import java.io.IOException;

/**
 * LZX decoder as used by Microsoft CAB (compression type 3, window 2^15 .. 2^21) and by WIM (LZX chunks, window 2^15).
 *
 * <p>The format is described in [MS-PATCH] 2.2 and, for CAB, in the Microsoft Cabinet SDK documentation. This is an
 * independent implementation of that description (the same decoder state machine every LZX reader has: three block
 * types, a 3-slot repeated-offset queue, the optional "E8" x86 call translation); it shares no code with cabextract.
 *
 * <p>A CAB folder is decoded frame by frame: each CFDATA block carries one frame of at most 32768 output bytes and the
 * decoder state (window, repeated offsets, Huffman lengths, the block in progress) lives on from one frame to the next.
 * {@link #decodeFrame} takes one frame; a WIM chunk is one frame of its own stream, so a decoder is made per chunk.
 */
final class LzxDecoder {
    private static final int MIN_MATCH = 2;
    private static final int NUM_CHARS = 256;
    private static final int BLOCK_VERBATIM = 1, BLOCK_ALIGNED = 2, BLOCK_UNCOMPRESSED = 3;
    private static final int PRETREE_SYMS = 20;
    private static final int ALIGNED_SYMS = 8;
    private static final int LENGTH_SYMS = 249;
    private static final int FRAME_SIZE = 32768;

    private static final int[] EXTRA_BITS = new int[52];
    private static final int[] POSITION_BASE = new int[52];
    static {
        for (int i = 0, j = 0; i <= 50; i += 2) {
            EXTRA_BITS[i] = EXTRA_BITS[i + 1] = j;
            if (i != 0 && j < 17) j++;
        }
        for (int i = 0, j = 0; i <= 50; i++) {
            POSITION_BASE[i] = j;
            j += 1 << EXTRA_BITS[i];
        }
    }

    // ---------------------------------------------------------------- canonical Huffman

    private static final class Huff {
        final int tableBits;
        final int[] table;                 // (len << 16) | symbol, or 0 when the code is longer than tableBits
        final int[] firstCode = new int[18];
        final int[] count = new int[18];
        final int[] offset = new int[18];  // index into sorted[] of the first symbol of each length
        int[] sorted;
        boolean empty;

        Huff(int tableBits) {
            this.tableBits = tableBits;
            this.table = new int[1 << tableBits];
        }

        void build(byte[] lens, int n) throws IOException {
            java.util.Arrays.fill(count, 0);
            java.util.Arrays.fill(table, 0);
            for (int i = 0; i < n; i++) count[lens[i]]++;
            count[0] = 0;
            empty = true;
            for (int l = 1; l <= 16; l++) if (count[l] != 0) { empty = false; break; }
            if (sorted == null || sorted.length < n) sorted = new int[n];
            int code = 0, idx = 0;
            for (int l = 1; l <= 16; l++) {
                firstCode[l] = code;
                offset[l] = idx;
                idx += count[l];
                code = (code + count[l]) << 1;
                if (code > (1 << (l + 1))) throw new IOException("LZX: over-subscribed Huffman tree");
            }
            int[] next = new int[18];
            for (int l = 1; l <= 16; l++) next[l] = offset[l];
            for (int i = 0; i < n; i++) if (lens[i] != 0) sorted[next[lens[i]]++] = i;
            // direct table for the short codes
            for (int l = 1; l <= tableBits; l++) {
                for (int k = 0; k < count[l]; k++) {
                    int c = firstCode[l] + k;
                    int sym = sorted[offset[l] + k];
                    int from = c << (tableBits - l), to = (c + 1) << (tableBits - l);
                    for (int t = from; t < to; t++) table[t] = (l << 16) | sym;
                }
            }
        }
    }

    // ---------------------------------------------------------------- state

    private final int windowBits;
    private final boolean wim;
    private final int windowSize;
    private final int windowMask;
    private final byte[] window;
    private final int mainElements;

    private int r0 = 1, r1 = 1, r2 = 1;
    private int windowPos;
    private int blockType;                 // 0 = no block yet
    private int blockRemaining;
    private boolean headerRead;
    private boolean intelStarted;
    private int intelFileSize;
    private long intelCurPos;
    private long framesDone;

    private final byte[] mainLens;
    private final byte[] lengthLens = new byte[LENGTH_SYMS + 8];
    private final byte[] alignedLens = new byte[ALIGNED_SYMS];
    private final Huff pretree = new Huff(6);
    private final Huff maintree = new Huff(12);
    private final Huff lengthtree = new Huff(12);
    private final Huff alignedtree = new Huff(7);

    // bit reader over the current frame's input
    private byte[] in;
    private int inPos, inLen;
    private long bitBuf;
    private int bitsLeft;

    LzxDecoder(int windowBits) throws IOException {
        this(windowBits, false);
    }

    /**
     * {@code wim}: the stream is one WIM chunk. WIM leaves out the CAB header that says whether E8 translation is on and
     * for what file size (it is on, with 12,000,000, whenever the chunk uses the E8 byte), and writes the block size as
     * one flag bit (set: 32768) or, when clear, the usual 24 bits.
     */
    LzxDecoder(int windowBits, boolean wim) throws IOException {
        if (windowBits < 15 || windowBits > 21) throw new IOException("LZX: unsupported window size 2^" + windowBits);
        this.windowBits = windowBits;
        this.wim = wim;
        this.windowSize = 1 << windowBits;
        this.windowMask = windowSize - 1;
        this.window = new byte[windowSize];
        int slots = windowBits == 21 ? 50 : windowBits == 20 ? 42 : windowBits * 2;
        this.mainElements = NUM_CHARS + slots * 8;
        this.mainLens = new byte[mainElements + 8];
        if (wim) { headerRead = true; intelFileSize = 12000000; }
    }

    // ---------------------------------------------------------------- bit input (16-bit little-endian words, MSB first)

    private void ensure(int n) {
        while (bitsLeft < n) {
            int w = 0;
            if (inPos + 1 < inLen) w = (in[inPos] & 255) | (in[inPos + 1] & 255) << 8;
            else if (inPos < inLen) w = in[inPos] & 255;
            inPos += 2;
            bitBuf = (bitBuf << 16) | w;
            bitsLeft += 16;
        }
    }

    private int peek(int n) {
        return (int) ((bitBuf >>> (bitsLeft - n)) & ((1L << n) - 1));
    }

    private void drop(int n) {
        bitsLeft -= n;
        bitBuf &= (1L << bitsLeft) - 1;
    }

    private int bits(int n) {
        if (n == 0) return 0;
        ensure(n);
        int v = peek(n);
        drop(n);
        return v;
    }

    private int decode(Huff h) throws IOException {
        ensure(16);
        int p = peek(16);
        int e = h.table[p >>> (16 - h.tableBits)];
        if (e != 0) { drop(e >>> 16); return e & 0xFFFF; }
        for (int l = h.tableBits + 1; l <= 16; l++) {
            int c = p >>> (16 - l);
            int d = c - h.firstCode[l];
            if (d >= 0 && d < h.count[l]) { drop(l); return h.sorted[h.offset[l] + d]; }
        }
        throw new IOException("LZX: invalid Huffman code");
    }

    // ---------------------------------------------------------------- tree headers

    private void readLengths(byte[] lens, int first, int last) throws IOException {
        byte[] pre = new byte[PRETREE_SYMS];
        for (int i = 0; i < PRETREE_SYMS; i++) pre[i] = (byte) bits(4);
        pretree.build(pre, PRETREE_SYMS);
        for (int x = first; x < last; ) {
            int sym = decode(pretree);
            if (sym == 17) {
                int z = bits(4) + 4;
                if (x + z > last) z = last - x;
                while (z-- > 0) lens[x++] = 0;
            } else if (sym == 18) {
                int z = bits(5) + 20;
                if (x + z > last) z = last - x;
                while (z-- > 0) lens[x++] = 0;
            } else if (sym == 19) {
                int z = bits(1) + 4;
                if (x + z > last) z = last - x;
                int s = decode(pretree);
                int v = lens[x] - s;
                if (v < 0) v += 17;
                while (z-- > 0) lens[x++] = (byte) v;
            } else {
                int v = lens[x] - sym;
                if (v < 0) v += 17;
                lens[x++] = (byte) v;
            }
        }
    }

    // ---------------------------------------------------------------- one frame

    /**
     * Decodes one frame. {@code out[0, outLen)} receives the bytes; {@code outLen} is at most 32768 (the last frame of a
     * folder is the only one allowed to be shorter).
     */
    void decodeFrame(byte[] input, int inputLen, byte[] out, int outLen) throws IOException {
        if (outLen > FRAME_SIZE) throw new IOException("LZX: frame larger than 32 KB");
        this.in = input; this.inPos = 0; this.inLen = inputLen;
        this.bitBuf = 0; this.bitsLeft = 0;

        int frameStart = windowPos;
        int todo = outLen;
        while (todo > 0) {
            if (blockRemaining == 0) readBlockHeader();
            int run = Math.min(blockRemaining, todo);
            if (blockType == BLOCK_UNCOMPRESSED) {
                if (inPos + run > inLen) throw new IOException("LZX: uncompressed block runs past the frame");
                int p = windowPos;
                for (int k = 0; k < run; k++) window[(p + k) & windowMask] = in[inPos + k];
                inPos += run;
                windowPos += run;
            } else {
                decodeRun(run);
            }
            blockRemaining -= run;
            todo -= run;
        }
        for (int k = 0; k < outLen; k++) out[k] = window[(frameStart + k) & windowMask];
        if (windowPos >= windowSize) windowPos = 0;

        // E8 call translation
        if (intelStarted && intelFileSize != 0 && framesDone < 32768 && outLen > 10) {
            long cur = intelCurPos;
            int end = outLen - 10;
            int i = 0;
            while (i < end) {
                if (out[i++] != (byte) 0xE8) { cur++; continue; }
                int abs = (out[i] & 255) | (out[i + 1] & 255) << 8 | (out[i + 2] & 255) << 16 | (out[i + 3] & 255) << 24;
                if (abs >= -cur && abs < intelFileSize) {
                    int rel = abs >= 0 ? (int) (abs - cur) : abs + intelFileSize;
                    out[i] = (byte) rel; out[i + 1] = (byte) (rel >> 8); out[i + 2] = (byte) (rel >> 16); out[i + 3] = (byte) (rel >> 24);
                }
                i += 4; cur += 5;
            }
        }
        intelCurPos += outLen;
        framesDone++;
    }

    private void readBlockHeader() throws IOException {
        // an uncompressed block with an odd length is followed by one pad byte
        if (blockType == BLOCK_UNCOMPRESSED && (blockLengthOdd)) {
            if (inPos < inLen) inPos++;
            blockLengthOdd = false;
        }
        if (!headerRead) {
            int hi = 0, lo = 0;
            if (bits(1) != 0) { hi = bits(16); lo = bits(16); }
            intelFileSize = (hi << 16) | lo;
            headerRead = true;
        }
        blockType = bits(3);
        if (wim && bits(1) != 0) {
            blockRemaining = FRAME_SIZE;            // WIM: a set flag bit stands for the default block size, 32768
        } else {
            int hi = bits(16), lo = bits(8);
            blockRemaining = (hi << 8) | lo;
        }
        if (blockRemaining == 0) throw new IOException("LZX: empty block");
        switch (blockType) {
            case BLOCK_ALIGNED:
                for (int i = 0; i < ALIGNED_SYMS; i++) alignedLens[i] = (byte) bits(3);
                alignedtree.build(alignedLens, ALIGNED_SYMS);
                // fall through
            case BLOCK_VERBATIM:
                readLengths(mainLens, 0, NUM_CHARS);
                readLengths(mainLens, NUM_CHARS, mainElements);
                maintree.build(mainLens, mainElements);
                if (mainLens[0xE8] != 0) intelStarted = true;
                readLengths(lengthLens, 0, LENGTH_SYMS);
                lengthtree.build(lengthLens, LENGTH_SYMS);
                break;
            case BLOCK_UNCOMPRESSED: {
                intelStarted = true;
                if (bitsLeft == 0) ensure(16);          // 1..16 bits are always consumed to reach the byte boundary
                bitsLeft = 0; bitBuf = 0;
                if (inPos + 12 > inLen) throw new IOException("LZX: truncated uncompressed block header");
                r0 = le32(inPos); r1 = le32(inPos + 4); r2 = le32(inPos + 8);
                inPos += 12;
                blockLengthOdd = (blockRemaining & 1) != 0;
                break;
            }
            default:
                throw new IOException("LZX: bad block type " + blockType);
        }
    }

    private boolean blockLengthOdd;

    private int le32(int p) {
        return (in[p] & 255) | (in[p + 1] & 255) << 8 | (in[p + 2] & 255) << 16 | (in[p + 3] & 255) << 24;
    }

    private void decodeRun(int run) throws IOException {
        int pos = windowPos;
        final int end = pos + run;
        final boolean aligned = blockType == BLOCK_ALIGNED;
        while (pos < end) {
            int main = decode(maintree);
            if (main < NUM_CHARS) {
                window[pos & windowMask] = (byte) main;
                pos++;
                continue;
            }
            main -= NUM_CHARS;
            int len = main & 7;
            if (len == 7) len += decode(lengthtree);
            len += MIN_MATCH;
            int slot = main >> 3;
            int off;
            if (slot == 0) {
                off = r0;
            } else if (slot == 1) {
                off = r1; r1 = r0; r0 = off;
            } else if (slot == 2) {
                off = r2; r2 = r0; r0 = off;
            } else {
                int extra = EXTRA_BITS[slot];
                if (aligned && extra >= 3) {
                    off = POSITION_BASE[slot] - 2;
                    if (extra > 3) off += bits(extra - 3) << 3;
                    off += decode(alignedtree);
                } else if (extra > 0) {
                    off = POSITION_BASE[slot] - 2 + bits(extra);
                } else {
                    off = 1;
                }
                r2 = r1; r1 = r0; r0 = off;
            }
            if (pos + len > end) throw new IOException("LZX: match crosses the end of its frame");
            if (off > windowSize || off <= 0) throw new IOException("LZX: match offset out of range");
            for (int k = 0; k < len; k++, pos++) window[pos & windowMask] = window[(pos - off) & windowMask];
        }
        windowPos = pos;
    }
}
