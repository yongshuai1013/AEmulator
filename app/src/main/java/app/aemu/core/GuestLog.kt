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

    /** Host-side mirror copies of the tree's tombstones live next to the host log (the image's
     *  run/ directory) under this prefix plus the original name: "tombstone-tombstone_00". */
    const val MIRROR_PREFIX = "tombstone-"

    private const val MIRROR_INDEX = "tombstone-mirror.index"

    /** The file a tree tombstone named [name] is mirrored to inside [bin]. */
    fun mirrorFile(bin: File, name: String): File = File(bin, MIRROR_PREFIX + name)

    /**
     * Newest host-side mirror copy in [bin], picked like [latestTombstone]. Null when nothing
     * was ever mirrored. The copies outlive the guest tree, so this is the fallback for a
     * tombstone whose original is already gone (tree reset, guest reimported).
     */
    fun latestMirroredTombstone(bin: File): File? {
        val files = runCatching { bin.listFiles() }.getOrNull() ?: return null
        return files.filter { it.isFile && it.name.startsWith(MIRROR_PREFIX) && it.name != MIRROR_INDEX }
            .maxWithOrNull(compareBy({ it.lastModified() }, { it.name }))
    }

    /**
     * Copies every tombstone in the guest tree that [bin] does not hold yet into [bin]
     * (see [mirrorFile]); what counts as held is the index file in [bin] (name + size), so
     * repeated scans copy nothing twice, and a tombstone rewritten under the same name is
     * picked up again. One host-log line per copy; returns how many were copied. The caller
     * runs this off the boot/restart path — a slow copy must never wedge either.
     */
    fun mirrorTombstones(root: File, bin: File, log: (String) -> Unit): Int {
        val dir = File(root, "data/tombstones")
        if (!dir.isDirectory) return 0 // the guest never crashed natively: nothing to mirror
        val files = runCatching { dir.listFiles() }.getOrNull()
        if (files == null) { log("tombstone: mirror scan failed: cannot list ${dir.path}"); return 0 }
        val indexFile = File(bin, MIRROR_INDEX)
        val held = HashMap<String, Long>()
        runCatching { indexFile.readLines() }.getOrNull()?.forEach { line ->
            val tab = line.lastIndexOf('\t')
            if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { held[line.substring(0, tab)] = it }
        }
        var copied = 0
        var dirty = false
        for (f in files) {
            if (!f.isFile || !f.name.startsWith("tombstone_")) continue
            val size = f.length()
            if (held[f.name] == size) continue
            val dst = mirrorFile(bin, f.name)
            val ok = runCatching {
                bin.mkdirs()
                f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.length() == size
            }.getOrDefault(false)
            if (ok) {
                held[f.name] = size; dirty = true; copied++
                log("tombstone: mirrored ${f.name} ($size bytes) -> run/${dst.name}")
            } else {
                log("tombstone: mirror of ${f.name} failed")
            }
        }
        if (dirty) runCatching {
            bin.mkdirs()
            indexFile.writeText(held.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
        }
        return copied
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
