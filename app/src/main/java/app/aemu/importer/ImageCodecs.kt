package app.aemu.importer

import java.io.ByteArrayInputStream
import app.aemu.archive.ArcStreams
import java.io.IOException
import java.io.InputStream

/**
 * Whole-file compression of partition images: system.img.xz / .lzma / .lz4 (frame or legacy `lz4 -l`) / .zst / .br / .bz2 / .gz, and the same
 * wrappers around a file that kept its plain name (a "system.img" or "vendor.img" that is really LZMA / LZ4 / ...).
 * Detection is by content (magic bytes) first; the name only decides for formats without a magic (brotli, bare LZMA).
 */
internal object ImageCodecs {
    enum class Codec { GZIP, XZ, LZMA, BZIP2, LZ4, LZ4_LEGACY, ZSTD, BROTLI }

    /** Trailing compression suffix of a file name. */
    val SUFFIX = Regex("(?i)\\.(lzma|xz|lz4|zst|zstd|bz2|gz|br)$")

    fun stripSuffix(name: String): String = SUFFIX.replaceFirst(name, "")

    fun detect(h: ByteArray, name: String?): Codec? {
        fun b(i: Int) = if (i < h.size) h[i].toInt() and 255 else -1
        return when {
            b(0) == 0x1f && b(1) == 0x8b -> Codec.GZIP
            b(0) == 0xfd && b(1) == 0x37 && b(2) == 0x7a && b(3) == 0x58 && b(4) == 0x5a && b(5) == 0 -> Codec.XZ
            b(0) == 0x42 && b(1) == 0x5a && b(2) == 0x68 && b(3) in 0x31..0x39 -> Codec.BZIP2
            b(0) == 0x04 && b(1) == 0x22 && b(2) == 0x4d && b(3) == 0x18 -> Codec.LZ4
            b(0) == 0x02 && b(1) == 0x21 && b(2) == 0x4c && b(3) == 0x18 -> Codec.LZ4_LEGACY // `lz4 -l`
            b(0) == 0x28 && b(1) == 0xb5 && b(2) == 0x2f && b(3) == 0xfd -> Codec.ZSTD
            isLzma(h, name) -> Codec.LZMA
            isBrotli(h, name) -> Codec.BROTLI
            else -> null
        }
    }

    fun open(codec: Codec, raw: InputStream): InputStream {
        // libaemuarc (native) when loaded, the Java libraries otherwise; the native decoder reads the source in big blocks itself
        return when (codec) {
            Codec.GZIP -> ArcStreams.gzip(raw)
            Codec.XZ -> ArcStreams.xz(raw)
            Codec.LZMA -> ArcStreams.lzma(raw) // no memory limit (system images are packed with big dictionaries)
            Codec.BZIP2 -> ArcStreams.bzip2(raw)
            Codec.LZ4 -> ArcStreams.lz4(raw)
            Codec.LZ4_LEGACY -> ArcStreams.lz4Legacy(raw)
            Codec.ZSTD -> ArcStreams.zstd(raw)
            Codec.BROTLI -> ArcStreams.brotli(raw)
        }
    }

    /** "lzma alone" header: properties byte, 32-bit dictionary size, 64-bit uncompressed size (or all ones = unknown). */
    private fun isLzma(h: ByteArray, name: String?): Boolean {
        if (h.size < 13) return false
        val props = h[0].toInt() and 255
        if (props >= 225) return false
        var dict = 0L
        for (i in 4 downTo 1) dict = (dict shl 8) or (h[i].toLong() and 255)
        if (dict < 4096 || dict > 0x6000_0000L) return false
        var allFf = true
        for (i in 5..12) if (h[i] != 0xff.toByte()) allFf = false
        var highZero = true
        for (i in 9..12) if (h[i] != 0.toByte()) highZero = false
        if (!allFf && !highZero) return false
        if (name != null && name.endsWith(".lzma", true)) return true
        // without the suffix demand the usual `lzma` tool header: lc3/lp0/pb2 and a dictionary that is a multiple of 4 KiB
        return props == 0x5d && dict % 4096 == 0L
    }

    /** Brotli has no magic: a ".br" name decides; otherwise the stream must decode to the start of a known filesystem. */
    private fun isBrotli(h: ByteArray, name: String?): Boolean {
        if (name != null && name.endsWith(".br", true) && !name.endsWith(".new.dat.br", true)) return true
        if (h.size < 256 || fsMagic(h, h.size)) return false
        return try {
            val s = ArcStreams.brotli(ByteArrayInputStream(h))
            val out = ByteArray(8192)
            var got = 0
            try {
                while (got < out.size) {
                    val r = s.read(out, got, out.size - got)
                    if (r < 0) break
                    got += r
                }
            } catch (_: IOException) { /* the probe is a truncated stream: what decoded so far is what counts */ }
            got >= 1088 && fsMagic(out, got)
        } catch (_: Throwable) { false }
    }

    private fun fsMagic(d: ByteArray, n: Int): Boolean {
        fun at(o: Int, vararg v: Int) = o + v.size <= n && v.indices.all { (d[o + it].toInt() and 255) == v[it] }
        return at(0, 0x3a, 0xff, 0x26, 0xed) || at(0, 0xed, 0x26, 0xff, 0x3a) || // Android sparse
            at(0, 'h'.code, 's'.code, 'q'.code, 't'.code) || at(0, 0x73, 0x71, 0x73, 0x68) || // squashfs
            at(1080, 0x53, 0xef) || // ext2/3/4
            at(1024, 0xe2, 0xe1, 0xf5, 0xe0) || // EROFS
            at(1024, 0x10, 0x20, 0xf5, 0xf2) // F2FS
    }
}
