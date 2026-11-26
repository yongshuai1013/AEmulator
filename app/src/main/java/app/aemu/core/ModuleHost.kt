/* Offline Magisk-module host, 2026-11-26. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * An offline host for Magisk modules: no daemon, no magic mount. While the guest is off, the host
 * installs a module zip the way a package manager would — the module's tree lands in the standard
 * /data/adb/modules/<id>, its system/ subtree is written into the guest's system/ for real, every
 * placed file is written into a per-instance ledger with a byte backup of whatever it replaced, and
 * disabling or removing the module replays the ledger backwards to put the tree back exactly.
 * post-fs-data.sh / service.sh of enabled modules are run by the VM (GuestVm) at the same boot
 * stages the Magisk stages use; system.prop is merged into the boot property overrides there.
 *
 * Plain java.io / java.nio on purpose (no Android classes), like MagiskCore, so it can be run and
 * tested on a desktop JVM; the Android side (ModuleHostSupport) supplies exact mode io.
 *
 * What v1 deliberately does not do, and says so in the install report instead of faking it:
 * zygisk/ payloads never take effect, sepolicy.rule is stored with the module but not applied,
 * customize.sh is not run (files are placed by the standard layout), a module vendor/ subtree is
 * not placed, and a symlink in system/ never replaces an existing file. Modules that overwrite
 * the same system file stack their backups; disable such modules in reverse install order.
 */
internal object ModuleHost {
    const val MODULES_DIR = "data/adb/modules"
    private const val LEDGER = "ledger.tsv"

    /** Reading and writing the unix mode of a guest-tree file; the Android side uses Os exactly. */
    interface ModeIo {
        /** the file's permission bits (0..0xFFF), or -1 when they cannot be read */
        fun read(f: File): Int
        fun write(f: File, mode: Int)
    }

    /** Coarse fallback (desktop use): owner bits mirrored to group/other on read, java.io setters on write. */
    val DEFAULT_MODES = object : ModeIo {
        override fun read(f: File): Int {
            var m = 0
            if (f.canRead()) m = m or 0x100
            if (f.canWrite()) m = m or 0x080
            if (f.canExecute()) m = m or 0x040
            return m or (m shr 3) or (m shr 6)
        }
        override fun write(f: File, mode: Int) {
            f.setReadable(mode and 0x100 != 0, false)
            f.setWritable(mode and 0x080 != 0, false)
            f.setExecutable(mode and 0x040 != 0, false)
        }
    }

    data class Info(val id: String, val name: String, val version: String, val versionCode: Int,
                    val author: String, val description: String, val enabled: Boolean)

    /** One placed file of an enabled module. kind: new | overwrite | dir | link. */
    data class Row(val module: String, val guestPath: String, val kind: String,
                   val backup: Int, val origMode: Int, val sha256: String, val mode: Int)

    data class Report(val info: Info, val placed: List<String>, val warnings: List<String>)

    // ---- store layout: <instance dir>/modhost (host side only, the guest never sees it) -----------

    private fun moduleDir(store: File, id: String) = File(store, "modules/$id")
    private fun payloadDir(store: File, id: String) = File(moduleDir(store, id), "payload")
    private fun backupDir(store: File, id: String) = File(moduleDir(store, id), "backup")
    private fun guestModuleDir(root: File, id: String) = File(root, "$MODULES_DIR/$id")

    fun hasStore(store: File): Boolean = File(store, LEDGER).isFile

    // ---- the ledger --------------------------------------------------------------------------------

    private class Ledger(val modules: LinkedHashMap<String, Info>, val rows: ArrayList<Row>)

    private fun loadLedger(store: File): Ledger {
        val modules = LinkedHashMap<String, Info>()
        val rows = ArrayList<Row>()
        val f = File(store, LEDGER)
        if (f.isFile) for (line in f.readLines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            when (p[0]) {
                "M" -> if (p.size >= 6) modules[p[1]] = Info(p[1], p[3], p[4], p[5].toIntOrNull() ?: 0, "", "", p[2] == "1")
                "F" -> if (p.size >= 8) rows.add(Row(p[1], p[2], p[3], p[4].toIntOrNull() ?: -1,
                    p[5].toIntOrNull(8) ?: -1, p[6], p[7].toIntOrNull(8) ?: 0))
            }
        }
        return Ledger(modules, rows)
    }

    private fun saveLedger(store: File, l: Ledger) {
        store.mkdirs()
        val sb = StringBuilder()
        sb.append("# aemu modhost ledger v1 - one row per module (M) and per placed file (F)\n")
        sb.append("# M: id, enabled, name, version, versionCode\n")
        sb.append("# F: module, guest path, kind (new|overwrite|dir|link), backup #, original mode (octal, -1 = unknown), placed sha256, placed mode (octal)\n")
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        for (m in l.modules.values)
            sb.append("M\t${m.id}\t${if (m.enabled) 1 else 0}\t${clean(m.name)}\t${clean(m.version)}\t${m.versionCode}\n")
        for (r in l.rows)
            sb.append("F\t${r.module}\t${r.guestPath}\t${r.kind}\t${r.backup}\t${if (r.origMode < 0) -1 else Integer.toOctalString(r.origMode)}\t${r.sha256}\t${Integer.toOctalString(r.mode)}\n")
        val tmp = File(store, "$LEDGER.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(File(store, LEDGER))
    }

    // ---- module.prop and listings ------------------------------------------------------------------

    fun parseProp(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || !l.contains('=')) continue
            out[l.substringBefore('=').trim()] = l.substringAfter('=').trim()
        }
        return out
    }

    private val ID_RE = Regex("[a-zA-Z][a-zA-Z0-9._-]*")

    private fun infoFrom(prop: Map<String, String>, enabled: Boolean): Info {
        val id = prop["id"]?.takeIf { ID_RE.matches(it) }
            ?: error("module.prop has no valid id (an id must match [a-zA-Z][a-zA-Z0-9._-]*)")
        return Info(id, prop["name"] ?: id, prop["version"] ?: "", prop["versionCode"]?.toIntOrNull() ?: 0,
            prop["author"] ?: "", prop["description"] ?: "", enabled)
    }

    /** Installed modules in install order; name/version/author come from the stored payload's module.prop. */
    fun list(store: File): List<Info> {
        if (!hasStore(store)) return emptyList()
        return loadLedger(store).modules.values.map { stored ->
            val propFile = File(payloadDir(store, stored.id), "module.prop")
            val fresh = if (propFile.isFile) runCatching { infoFrom(parseProp(propFile.readText()), stored.enabled) }.getOrNull() else null
            fresh ?: stored
        }
    }

    fun enabledIds(store: File): List<String> = list(store).filter { it.enabled }.map { it.id }

    /** key=value pairs of every enabled module's system.prop, in install order (the VM merges them at boot). */
    fun systemProps(store: File): List<Pair<String, String>> {
        if (!hasStore(store)) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (info in loadLedger(store).modules.values) {
            if (!info.enabled) continue
            val f = File(payloadDir(store, info.id), "system.prop")
            if (f.isFile) for ((k, v) in parseProp(f.readText())) out.add(k to v)
        }
        return out
    }

    // ---- zip plumbing -------------------------------------------------------------------------------

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unix modes of a zip's entries, read from the central directory's external attributes
     * (java.util.zip parses them but does not expose them). Empty when the directory cannot be
     * read; callers then fall back to the default modes.
     */
    fun zipModes(zip: File): Map<String, Int> {
        if (zip.length() > (256L shl 20)) return emptyMap()
        val b = zip.readBytes()
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        var eocd = -1
        var i = b.size - 22
        val floor = maxOf(0, b.size - 22 - 65536)
        while (i >= floor) { if (u32(i) == 0x06054b50) { eocd = i; break }; i-- }
        if (eocd < 0) return emptyMap()
        val count = u16(eocd + 10)
        var off = u32(eocd + 16)
        val out = HashMap<String, Int>()
        repeat(count) {
            if (off + 46 > b.size || u32(off) != 0x02014b50) return out
            val nameLen = u16(off + 28); val extraLen = u16(off + 30); val commentLen = u16(off + 32)
            val mode = (u32(off + 38) ushr 16) and 0xFFFF
            val name = String(b, off + 46, nameLen, Charsets.UTF_8)
            out[name] = mode
            off += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun isSymlinkMode(mode: Int) = mode and 0xF000 == 0xA000
    private fun isSymlink(f: File) = Files.isSymbolicLink(f.toPath())

    /** The mode an entry lands with: the zip's own when it carries one, else 0755 for dirs and scripts, 0644 for files. */
    private fun entryMode(modes: Map<String, Int>, name: String, isDir: Boolean): Int {
        val m = modes[name] ?: 0
        if (m and 0xFFF != 0) return m and 0xFFF
        return if (isDir || name.endsWith(".sh")) 0x1ED else 0x1A4
    }

    /** Deletes a tree without following symlinks out of it (the guest tree is full of links into itself). */
    private fun deleteTree(f: File) {
        if (!f.exists() && !isSymlink(f)) return
        Files.walkFileTree(f.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }; return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                runCatching { Files.deleteIfExists(dir) }; return FileVisitResult.CONTINUE
            }
        })
    }

    /** All entries under [dir] (itself excluded), parents before children, symlinks not followed. */
    private fun walk(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        val stream = Files.walk(dir.toPath())
        try { stream.forEach { p -> if (p != dir.toPath()) out.add(p.toFile()) } } finally { stream.close() }
        return out
    }

    /** Unpacks the module zip into its payload folder; a zip-slip entry aborts the whole install. */
    private fun extract(zip: File, modes: Map<String, Int>, dest: File) {
        deleteTree(dest)
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name.replace('\\', '/')
                if (name.any { it == '\t' || it == '\n' || it == '\r' })
                    error("the module zip has a control character in an entry name: $name")
                val out = File(dest, name)
                if (out.canonicalPath != destCanon && !out.canonicalPath.startsWith(destCanon + File.separator))
                    error("the module zip escapes its folder: $name")
                when {
                    e.isDirectory -> out.mkdirs()
                    isSymlinkMode(modes[name] ?: 0) -> {
                        val target = z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8).trim()
                        out.parentFile?.mkdirs()
                        // the payload is a storage copy: where a link cannot be made, keep its target as text
                        runCatching { Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(target)) }
                            .onFailure { out.writeText(target) }
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        z.getInputStream(e).use { i -> Files.copy(i, out.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                    }
                }
            }
        }
    }

    /** Copies the payload tree somewhere (the guest's /data/adb/modules/<id>), links and modes included. */
    private fun copyTree(src: File, dst: File, zipModes: Map<String, Int>, modeIo: ModeIo) {
        dst.mkdirs()
        for (f in walk(src)) {
            val rel = f.relativeTo(src).path.replace(File.separatorChar, '/')
            val out = File(dst, rel)
            val mode = entryMode(zipModes, rel, f.isDirectory && !isSymlink(f))
            when {
                isSymlink(f) -> {
                    out.parentFile?.mkdirs()
                    runCatching {
                        Files.createSymbolicLink(out.toPath(), Files.readSymbolicLink(f.toPath()))
                    }.onFailure { out.writeText(Files.readSymbolicLink(f.toPath()).toString()) }
                }
                f.isDirectory -> { out.mkdirs(); modeIo.write(out, mode) }
                else -> {
                    out.parentFile?.mkdirs()
                    Files.copy(f.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    modeIo.write(out, mode)
                }
            }
        }
    }

    /**
     * Places a module: its own tree into /data/adb/modules/<id>, then its system/ subtree as real
     * writes, backing every replaced file up first. Appends one [Row] per placed file to [rows] as
     * it goes, so a failure halfway can be rolled back from exactly those rows.
     */
    private fun place(root: File, store: File, info: Info, zipModes: Map<String, Int>,
                      modeIo: ModeIo, warnings: MutableList<String>, rows: ArrayList<Row>) {
        val payload = payloadDir(store, info.id)
        copyTree(payload, guestModuleDir(root, info.id), zipModes, modeIo)
        val sysSrc = File(payload, "system")
        if (!sysSrc.isDirectory) return
        var backupSeq = rows.count { it.kind == "overwrite" }
        for (f in walk(sysSrc)) {
            val rel = f.relativeTo(sysSrc).path.replace(File.separatorChar, '/')
            val guestPath = "/system/$rel"
            val dst = File(root, "system/$rel")
            val mode = entryMode(zipModes, "system/$rel", f.isDirectory && !isSymlink(f))
            when {
                isSymlink(f) -> {
                    if (dst.exists() || isSymlink(dst)) {
                        warnings.add("symlink $guestPath would replace an existing file: skipped (v1 does not replace files with symlinks)")
                        continue
                    }
                    dst.parentFile?.mkdirs()
                    val target = Files.readSymbolicLink(f.toPath()).toString()
                    Files.createSymbolicLink(dst.toPath(), java.nio.file.Paths.get(target))
                    rows.add(Row(info.id, guestPath, "link", -1, -1, sha256(target.toByteArray(Charsets.UTF_8)), 0x1FF))
                }
                f.isDirectory -> {
                    if (dst.exists() && !dst.isDirectory) error("$guestPath is a file in the guest, the module wants a directory there")
                    if (!dst.isDirectory) {
                        dst.mkdirs(); modeIo.write(dst, mode)
                        rows.add(Row(info.id, guestPath, "dir", -1, -1, "", mode))
                    }
                }
                else -> {
                    dst.parentFile?.mkdirs()
                    if (dst.exists() || isSymlink(dst)) {
                        // the original's bytes are backed up BEFORE the overwrite, or there is no way back
                        val origMode = if (isSymlink(dst)) -1 else modeIo.read(dst)
                        val b = File(backupDir(store, info.id), "$backupSeq")
                        b.parentFile?.mkdirs()
                        Files.copy(dst.toPath(), b.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        modeIo.write(dst, mode)
                        rows.add(Row(info.id, guestPath, "overwrite", backupSeq, origMode, sha256(dst), mode))
                        backupSeq++
                    } else {
                        Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        modeIo.write(dst, mode)
                        rows.add(Row(info.id, guestPath, "new", -1, -1, sha256(dst), mode))
                    }
                }
            }
        }
    }

    /** Puts the tree back from [rows]: overwritten files get their backup bytes and mode, the rest go away. */
    private fun restore(root: File, store: File, rows: List<Row>, modeIo: ModeIo) {
        for (row in rows.asReversed()) {
            val dst = File(root, row.guestPath.removePrefix("/"))
            runCatching {
                when (row.kind) {
                    "overwrite" -> {
                        val b = File(backupDir(store, row.module), "${row.backup}")
                        if (b.isFile) {
                            Files.copy(b.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            if (row.origMode >= 0) modeIo.write(dst, row.origMode)
                        }
                    }
                    else -> Files.deleteIfExists(dst.toPath())   // new | link | dir (a dir only goes when empty)
                }
            }
        }
    }

    /**
     * Installs [zipFile] into the instance whose guest tree is [root] and whose host-side module
     * store is [store]. Throws with a readable message when the zip is not a usable module; a
     * failure during placement rolls the tree back from the rows placed so far.
     */
    fun install(root: File, store: File, zipFile: File, modeIo: ModeIo, log: (String) -> Unit): Report {
        val propText = ZipFile(zipFile).use { z ->
            val e = z.getEntry("module.prop")
                ?: error("This is not a Magisk module: module.prop is missing at the zip root")
            z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        val info = infoFrom(parseProp(propText), enabled = true)
        val ledger = loadLedger(store)
        if (ledger.modules.containsKey(info.id))
            error("Module '${info.id}' is already installed; uninstall it first (v1 has no in-place upgrade)")
        val names = ZipFile(zipFile).use { z -> z.entries().asSequence().map { it.name.replace('\\', '/') }.toList() }
        val warnings = ArrayList<String>()
        if (names.any { it == "zygisk" || it.startsWith("zygisk/") })
            warnings.add("zygisk/ present: the Zygisk part is not supported and will not take effect")
        if ("sepolicy.rule" in names)
            warnings.add("sepolicy.rule present: stored with the module, not applied by v1")
        if ("customize.sh" in names)
            warnings.add("customize.sh present: the custom install script is not run; files are placed by the standard layout")
        if (names.any { it == "vendor" || it.startsWith("vendor/") })
            warnings.add("vendor/ subtree present: not placed (v1 places system/ only)")
        val zipModes = zipModes(zipFile)
        val rows = ArrayList<Row>()
        try {
            val mDir = moduleDir(store, info.id)
            deleteTree(mDir); mDir.mkdirs()
            zipFile.inputStream().use { i -> Files.copy(i, File(mDir, "module.zip").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            extract(zipFile, zipModes, payloadDir(store, info.id))
            place(root, store, info, zipModes, modeIo, warnings, rows)
        } catch (e: Exception) {
            runCatching { restore(root, store, rows, modeIo) }
            runCatching { deleteTree(guestModuleDir(root, info.id)) }
            runCatching { deleteTree(moduleDir(store, info.id)) }
            throw e
        }
        ledger.modules[info.id] = info
        ledger.rows.addAll(rows)
        saveLedger(store, ledger)
        val placed = rows.map { "${it.kind} ${it.guestPath}" }.toMutableList()
        placed.add("module tree $MODULES_DIR/${info.id}")
        if (File(payloadDir(store, info.id), "system.prop").isFile)
            placed.add("system.prop (applied at boot through the property overrides)")
        for (s in listOf("post-fs-data.sh", "service.sh"))
            if (File(payloadDir(store, info.id), s).isFile) placed.add("$s (run at boot while the module is enabled)")
        log("modhost: installed ${info.id}: ${rows.size} system file(s) placed")
        return Report(info, placed, warnings)
    }

    /** Enables (re-place from the stored payload) or disables (replay the ledger backwards) a module. */
    fun setEnabled(root: File, store: File, id: String, enabled: Boolean, modeIo: ModeIo, log: (String) -> Unit) {
        val ledger = loadLedger(store)
        val info = ledger.modules[id] ?: error("module '$id' is not installed")
        if (info.enabled == enabled) return
        if (!enabled) {
            val rows = ledger.rows.filter { it.module == id }
            restore(root, store, rows, modeIo)
            deleteTree(guestModuleDir(root, id))
            deleteTree(backupDir(store, id))
            ledger.rows.removeAll { it.module == id }
            ledger.modules[id] = info.copy(enabled = false)
            saveLedger(store, ledger)
            log("modhost: disabled $id: ${rows.size} file(s) restored")
        } else {
            val warnings = ArrayList<String>()
            val rows = ArrayList<Row>()
            try {
                place(root, store, info, zipModes(File(moduleDir(store, id), "module.zip")), modeIo, warnings, rows)
            } catch (e: Exception) {
                runCatching { restore(root, store, rows, modeIo) }
                runCatching { deleteTree(guestModuleDir(root, id)) }
                throw e
            }
            for (w in warnings) log("modhost: enable $id: $w")
            ledger.rows.addAll(rows)
            ledger.modules[id] = info.copy(enabled = true)
            saveLedger(store, ledger)
            log("modhost: enabled $id: ${rows.size} file(s) placed")
        }
    }

    /** Removes a module for good: restores its files first when it is enabled, then drops every trace. */
    fun uninstall(root: File, store: File, id: String, modeIo: ModeIo, log: (String) -> Unit) {
        val ledger = loadLedger(store)
        val info = ledger.modules[id] ?: error("module '$id' is not installed")
        if (info.enabled) {
            val rows = ledger.rows.filter { it.module == id }
            restore(root, store, rows, modeIo)
            ledger.rows.removeAll { it.module == id }
        }
        deleteTree(guestModuleDir(root, id))
        deleteTree(moduleDir(store, id))
        ledger.modules.remove(id)
        saveLedger(store, ledger)
        log("modhost: uninstalled $id")
    }
}
