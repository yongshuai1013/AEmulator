package app.aemu.archive;

/**
 * Bridge to libaemuarc.so (native/archive): decompression of gzip / zlib / deflate / xz / lzma / bzip2 / lz4 (frame and legacy) / zstd / brotli and a
 * streaming 7z reader and a streaming RAR reader (RAR 3/4/5). The natives are registered by the library itself (JNI_OnLoad). When the library cannot be loaded (JVM unit
 * tests, a device without arm64) {@link #AVAILABLE} is false and {@link ArcStreams} / {@link SevenZReaders} use the Java libraries ({@link RarReaders} has none: the importer then uses junrar).
 */
public final class NativeArc {
    public static final int COPY = 0, GZIP = 1, ZLIB = 2, DEFLATE = 3, XZ = 4, LZMA = 5, BZIP2 = 6, LZ4F = 7, ZSTD = 8, BROTLI = 9, LZ4L = 10;
    public static final int OK = 0, END = 1;

    public static final boolean AVAILABLE = load();

    private NativeArc() {}

    private static boolean load() {
        if ("off".equals(System.getProperty("aemu.nativearc"))) return false;
        try {
            String path = System.getProperty("aemu.nativearc.lib"); // host tests: the library built by native/archive/build.sh host
            if (path != null) System.load(path); else System.loadLibrary("aemuarc");
            nativeVersion(); // fails with UnsatisfiedLinkError when the natives were not registered
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static native String nativeVersion();

    /** A push decoder; see arc_dec_step in aemuarc.h. */
    static native long decOpen(int codec);
    static native void decClose(long h);
    /** res = { status, in_used, out_made } */
    static native void decStep(long h, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outCap, boolean eof, int[] res);
    /** Whole-buffer decode; sizeHint 0 = unknown. */
    public static native byte[] decodeAll(int codec, byte[] in, int off, int len, long sizeHint) throws java.io.IOException;
    /** Raw LZ4 block of known decoded size. */
    public static native byte[] lz4Block(byte[] in, int off, int len, int outSize) throws java.io.IOException;

    /** 7z: [src] has int readAt(long pos, ByteBuffer dst); throws UnsupportedOperationException when this reader cannot take the archive. */
    static native long szOpen(Object src, long size, long memCap) throws java.io.IOException;
    static native void szClose(long h);
    static native int szCount(long h);
    static native String szName(long h, int i);
    /** out = { size, isDir } */
    static native void szInfo(long h, int i, long[] out);
    static native void szSelect(long h, int i) throws java.io.IOException;
    /** -1 at the end of the entry */
    static native int szRead(long h, byte[] b, int off, int len) throws java.io.IOException;

    /** RAR: same shape as the 7z natives; [src] has int readAt(long pos, ByteBuffer dst); throws UnsupportedOperationException when this reader cannot take the archive. */
    static native long rarOpen(Object src, long size, long memCap) throws java.io.IOException;
    static native void rarClose(long h);
    static native int rarCount(long h);
    static native String rarName(long h, int i);
    /** out = { size, isDir } */
    static native void rarInfo(long h, int i, long[] out);
    static native void rarSelect(long h, int i) throws java.io.IOException;
    /** -1 at the end of the entry (its CRC32 has been checked by then) */
    static native int rarRead(long h, byte[] b, int off, int len) throws java.io.IOException;
}
