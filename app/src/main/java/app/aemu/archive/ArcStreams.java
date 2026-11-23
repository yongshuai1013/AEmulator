package app.aemu.archive;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.brotli.dec.BrotliInputStream;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decompressing streams for every format the importer meets. libaemuarc does the work when it is loaded; otherwise (unit tests on a
 * JVM) the same stream comes from the Java libraries. Closing the result closes [in].
 */
public final class ArcStreams {
    private ArcStreams() {}

    public static InputStream gzip(InputStream in) throws IOException { return open(NativeArc.GZIP, in); }
    public static InputStream zlib(InputStream in) throws IOException { return open(NativeArc.ZLIB, in); }
    public static InputStream xz(InputStream in) throws IOException { return open(NativeArc.XZ, in); }
    public static InputStream lzma(InputStream in) throws IOException { return open(NativeArc.LZMA, in); }
    public static InputStream bzip2(InputStream in) throws IOException { return open(NativeArc.BZIP2, in); }
    public static InputStream lz4(InputStream in) throws IOException { return open(NativeArc.LZ4F, in); }
    /** The LZ4 legacy format ({@code lz4 -l}, magic 02 21 4C 18): what Linux initramfs / old Android ramdisks use. */
    public static InputStream lz4Legacy(InputStream in) throws IOException { return open(NativeArc.LZ4L, in); }

    /**
     * An {@code .lz4} file of either flavour: the frame format ({@code lz4}, magic 04 22 4D 18) or the legacy one
     * ({@code lz4 -l}). Decided by the first four bytes; anything else is handed to the frame decoder, which reports it.
     */
    public static InputStream lz4Any(InputStream in) throws IOException {
        PushbackInputStream p = new PushbackInputStream(in, 4);
        byte[] m = new byte[4];
        int n = 0;
        while (n < 4) {
            int r = p.read(m, n, 4 - n);
            if (r < 0) break;
            n += r;
        }
        if (n > 0) p.unread(m, 0, n);
        boolean legacy = n == 4 && m[0] == 0x02 && m[1] == 0x21 && m[2] == 0x4c && m[3] == 0x18;
        return legacy ? lz4Legacy(p) : lz4(p);
    }
    public static InputStream zstd(InputStream in) throws IOException { return open(NativeArc.ZSTD, in); }
    public static InputStream brotli(InputStream in) throws IOException { return open(NativeArc.BROTLI, in); }

    public static InputStream open(int codec, InputStream in) throws IOException {
        if (NativeArc.AVAILABLE) {
            try {
                return new NativeInputStream(codec, in);
            } catch (UnsatisfiedLinkError e) { /* fall through to Java */ }
        }
        return javaStream(codec, in);
    }

    private static InputStream javaStream(int codec, InputStream raw) throws IOException {
        InputStream i = new BufferedInputStream(raw, 1 << 16);
        switch (codec) {
            case NativeArc.GZIP: return new GZIPInputStream(i, 1 << 16);
            case NativeArc.ZLIB: return new InflaterInputStream(i, new Inflater(), 1 << 16);
            case NativeArc.DEFLATE: return new InflaterInputStream(i, new Inflater(true), 1 << 16);
            case NativeArc.XZ: return new XZInputStream(i);
            case NativeArc.LZMA: return new LZMAInputStream(i, -1); // -1: no memory limit (system images use big dictionaries)
            case NativeArc.BZIP2: return new BZip2CompressorInputStream(i, true);
            case NativeArc.LZ4F: return new FramedLZ4CompressorInputStream(i, true);
            case NativeArc.LZ4L: return new Lz4LegacyInputStream(i);
            case NativeArc.ZSTD: return new ZstdInputStream(i);
            case NativeArc.BROTLI: return new BrotliInputStream(i);
            case NativeArc.COPY: return i;
            default: throw new IOException("Unknown codec " + codec);
        }
    }

    /** The stream for a Commons Compress compressor name ({@code CompressorStreamFactory.detect} result); other names go to Commons. */
    public static InputStream byName(String name, InputStream in) throws IOException {
        switch (name) {
            case "gz": return gzip(in);
            case "bzip2": return bzip2(in);
            case "xz": return xz(in);
            case "lzma": return lzma(in);
            case "lz4-framed": return lz4(in);
            case "zstd": return zstd(in);
            case "deflate": return zlib(in);
            case "br": return brotli(in);
            default: return new CompressorStreamFactory(true, 1 << 16).createCompressorInputStream(name, in);
        }
    }

    /** A compressed byte range decoded to memory. [sizeHint] is the decoded size when known, else 0. */
    public static byte[] decodeAll(int codec, byte[] data, int off, int len, long sizeHint) throws IOException {
        if (NativeArc.AVAILABLE) {
            try {
                return NativeArc.decodeAll(codec, data, off, len, sizeHint);
            } catch (UnsatisfiedLinkError e) { /* fall through to Java */ }
        }
        try (InputStream s = javaStream(codec, new ByteArrayInputStream(data, off, len))) {
            return s.readAllBytes();
        }
    }

    public static byte[] decodeAll(int codec, byte[] data) throws IOException { return decodeAll(codec, data, 0, data.length, 0); }

    /** A raw LZ4 block (no frame) whose decoded size is [outSize]. */
    public static byte[] lz4Block(byte[] data, int off, int len, int outSize) throws IOException {
        if (NativeArc.AVAILABLE) {
            try {
                return NativeArc.lz4Block(data, off, len, outSize);
            } catch (UnsatisfiedLinkError e) { /* fall through to Java */ }
        }
        try (InputStream s = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(data, off, len))) {
            return s.readAllBytes();
        }
    }
}
