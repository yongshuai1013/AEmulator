package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Журнал гостя. Гостевой liblog пишет в /dev/log/main записи вида
 *   [приоритет:1 байт][тег]\0[сообщение]\0
 * (заголовок добавило бы ядро, но драйвера logger нет — файл обычный).
 */
object GuestLog {
    data class Rec(val prio: Char, val tag: String, val msg: String)

    fun tail(root: File, bytes: Int = 256 * 1024, buffer: String = "main"): List<Rec> {
        val f = File(root, "dev/log/$buffer")
        if (!f.isFile || f.length() == 0L) return emptyList()
        val data = runCatching {
            RandomAccessFile(f, "r").use { r ->
                val n = minOf(r.length(), bytes.toLong()).toInt()
                r.seek(r.length() - n)
                ByteArray(n).also { r.readFully(it) }
            }
        }.getOrNull() ?: return emptyList()
        return parse(data)
    }

    /** Log buffers the guest tree synthesizes (TreeFixer LOGS); "events" is a channel, not a file. */
    val BUFFERS = listOf("main", "system", "radio")

    /**
     * Export text for the log panel: every buffer in its own section, each read with the
     * panel's window (1 MB tail, last 3000 records). The panel itself shows main only —
     * service-layer lines (SystemServer, WifiServiceImpl) go to system and never reached
     * an export before, so a silent main proved nothing about the service layer.
     */
    fun exportLines(root: File): List<String> {
        val out = ArrayList<String>()
        for (b in BUFFERS) {
            val recs = tail(root, 1024 * 1024, b).takeLast(3000)
            out.add("===== log buffer: $b (${recs.size} records) =====")
            recs.mapTo(out) { "${it.prio} ${it.tag}: ${it.msg}" }
            out.add("")
        }
        return out
    }

    /**
     * Newest native-crash tombstone in the guest tree (data/tombstones/tombstone_*),
     * picked by modification time (name breaks ties). Null when the directory is
     * missing or holds no tombstone — the guest never crashed natively.
     */
    fun latestTombstone(root: File): File? {
        val files = runCatching { File(root, "data/tombstones").listFiles() }.getOrNull() ?: return null
        return files.filter { it.isFile && it.name.startsWith("tombstone_") }
            .maxWithOrNull(compareBy({ it.lastModified() }, { it.name }))
    }

    fun parse(data: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var i = 0
        while (i < data.size - 2) {
            val p = data[i].toInt() and 0xff
            val end = indexOf(data, i + 1)
            if (end < 0) break
            if (p in 2..8 && end - i - 1 in 1..48) {
                val tag = String(data, i + 1, end - i - 1, Charsets.UTF_8)
                if (tag.none { it < ' ' }) {
                    val j = end + 1
                    var end2 = indexOf(data, j)
                    if (end2 < 0) end2 = data.size
                    out.add(Rec("??VDIWEFS"[p], tag, String(data, j, end2 - j, Charsets.UTF_8)))
                    i = end2 + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    fun contains(root: File, vararg needles: String): Boolean {
        val f = File(root, "dev/log/main")
        if (!f.isFile) return false
        val t = runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
        return needles.any { t.contains(it) }
    }

    fun clear(root: File) {
        for (n in listOf("main", "system", "radio", "events")) {
            val f = File(root, "dev/log/$n")
            if (f.isFile) runCatching { RandomAccessFile(f, "rw").use { it.setLength(0) } }
        }
    }

    private fun indexOf(a: ByteArray, from: Int): Int {
        for (k in from until a.size) if (a[k] == 0.toByte()) return k
        return -1
    }
}
