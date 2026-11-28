package app.aemu.core

import android.system.Os
import app.aemu.importer.BootImage
import java.io.File
import java.io.RandomAccessFile

/**
 * Recovery mode: the ramdisk of a recovery.img (stock, TWRP, OrangeFox, CWM…) unpacked into <image>/recovery.
 * The VM runs its /sbin/recovery under translator (or natively on arm64/aarch32) sharing the framebuffer and touch.
 */
object RecoveryImage {
    fun dir(paths: VmPaths) = File(paths.dir, "recovery")
    fun installed(paths: VmPaths) = File(dir(paths), "sbin/recovery").isFile
    fun fb(paths: VmPaths) = File(dir(paths), "dev/graphics/fb0")

    fun isElf64(paths: VmPaths): Boolean = File(dir(paths), "sbin/recovery").let { f ->
        f.isFile && runCatching {
            f.inputStream().use {
                val b = ByteArray(5)
                it.read(b) == 5 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() && b[4] == 2.toByte()
            }
        }.getOrDefault(false)
    }

    /** Unpacks a recovery (or boot) image; false when it holds no /sbin/recovery. */
    fun install(paths: VmPaths, image: ByteArray, log: (String) -> Unit): Boolean {
        val rd = runCatching { BootImage.ramdisk(image) }.getOrNull()
        if (rd.isNullOrEmpty() || rd.none { it.name == "sbin/recovery" }) { log("recovery: image has no /sbin/recovery"); return false }
        val out = dir(paths)
        // never deleteRecursively: system/, data/, sdcard/ in it are links to the firmware's partitions and card
        ImageStore.wipe(out)
        out.mkdirs()
        val base = out.canonicalPath
        // every path the image itself brings, ancestors included, so symlink targets can be
        // told apart as in-tree vs outside before anything is written
        val treePaths = HashSet<String>()
        for (e in rd) {
            var p = normalizeTreePath(e.name.trimStart('/')) ?: continue
            while (true) {
                treePaths.add(p)
                val i = p.lastIndexOf('/')
                if (i < 0) break
                p = p.substring(0, i)
            }
        }
        for (e in rd) {
            val n = e.name.trimStart('/')
            if (n.isEmpty() || n == ".") continue
            val f = File(out, n)
            if (!f.canonicalPath.startsWith(base)) continue
            when (e.mode and 0xF000) {
                0x4000 -> f.mkdirs()
                0xA000 -> { f.parentFile?.mkdirs(); runCatching { Os.symlink(symlinkTarget(n, String(e.data), treePaths), f.path) } }
                0x8000 -> {
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    if (e.mode and 0x49 != 0) f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
            }
        }
        val name = runCatching { File(out, "default.prop").readText() }.getOrDefault("")
            .let { Regex("twrp", RegexOption.IGNORE_CASE).containsMatchIn(it) }.let { if (it) "TWRP" else "" }
        log("recovery: installed ${rd.size} files ${name}".trim())
        return true
    }

    /** Tree-relative path with "." and ".." resolved; null when it escapes the tree root. */
    private fun normalizeTreePath(p: String): String? {
        val parts = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> { if (parts.isEmpty()) return null; parts.removeLast() }
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * A symlink target spelled absolute ("/sbin/busybox") is resolved by the host kernel
     * against the phone's real root whenever the guest execs through it — outside this tree
     * it does not exist and the exec dies with ENOENT (the TWRP updater's #!/sbin/sh did).
     * When the target lives inside this tree, respell it relative to the link's own
     * directory so every resolver stays inside the tree; anything else is kept verbatim.
     */
    private fun symlinkTarget(linkName: String, target: String, treePaths: Set<String>): String {
        if (!target.startsWith("/")) return target
        val abs = normalizeTreePath(target) ?: return target
        if (abs !in treePaths) return target
        val dir = linkName.substringBeforeLast('/', "")
        val from = if (dir.isEmpty()) emptyList() else dir.split('/')
        val to = if (abs.isEmpty()) emptyList() else abs.split('/')
        var common = 0
        while (common < from.size && common < to.size && from[common] == to[common]) common++
        val rel = (List(from.size - common) { ".." } + to.subList(common, to.size)).joinToString("/")
        return rel.ifEmpty { "." }
    }

    /**
     * Self-healing for trees assembled before install() learned the respelling above:
     * install() runs only at import or on a manual reinstall, so an older tree keeps its
     * absolute "/sbin/sh" and the updater dies on it at every recovery boot. Walk the
     * assembled tree once — never following links, since system/, data/ and sdcard/ in it
     * point at the firmware's own partitions — and respell every absolute target that
     * lives inside this tree exactly as install() would; outside targets (the by-name
     * devices, the partition links) stay verbatim. Idempotent: a respelled target is
     * relative and never matches again. Fully guarded: a failure here must not block
     * the recovery boot.
     */
    private fun repairAbsoluteSymlinks(root: File, log: (String) -> Unit) {
        runCatching {
            if (!root.isDirectory) return
            val treePaths = HashSet<String>()
            val links = mutableListOf<Pair<File, String>>()
            val stack = ArrayDeque<Pair<File, String>>()
            stack.addLast(root to "")
            while (stack.isNotEmpty()) {
                val (d, rel) = stack.removeLast()
                for (k in d.listFiles().orEmpty()) {
                    val kr = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                    treePaths.add(kr)
                    when {
                        java.nio.file.Files.isSymbolicLink(k.toPath()) -> links.add(k to kr)
                        k.isDirectory -> stack.addLast(k to kr)
                    }
                }
            }
            var n = 0
            for ((f, kr) in links) {
                runCatching {
                    val target = Os.readlink(f.path)
                    val fixed = symlinkTarget(kr, target, treePaths)
                    if (fixed != target) {
                        f.delete()
                        Os.symlink(fixed, f.path)
                        n++
                    }
                }
            }
            if (n > 0) log("recovery: respelled $n absolute symlink(s) in the existing tree")
        }
    }

    /** Runtime pieces the ramdisk lacks: device nodes qemu emulates, mount points, the shared framebuffer. */
    fun prepare(paths: VmPaths, sdcard: File?, settings: VmSettings? = null, log: (String) -> Unit = {}) {
        val r = dir(paths)
        repairAbsoluteSymlinks(r, log)
        for (d in listOf("dev/graphics", "dev/input", "tmp", "cache", "data", "system", "sdcard", "proc", "sys", "etc")) File(r, d).mkdirs()
        // no tty0: minui gives up on graphics when it opens one but KDSETMODE fails; without it, it skips the step
        File(r, "dev/tty0").delete()

        // Sizing framebuffer: TWRP and minui expect at least 2 pages of double buffering.
        val fb = fb(paths)
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: ((settings?.width ?: 720) to (settings?.height ?: 1280))
        val minSize = (w.toLong() * h * 4 * 2).coerceAtLeast(paths.fb.length())
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(fb.toPath())) fb.delete()
            fb.parentFile?.mkdirs()
            if (!fb.exists() || fb.length() < minSize) {
                RandomAccessFile(fb, "rw").use { it.setLength(minSize) }
            }
            // a real framebuffer powers up blank: the previous session's last frame must not survive into
            // this one (the screen's page choice reads the file before the guest has drawn over all of it)
            if (fb.isFile) {
                RandomAccessFile(fb, "rw").use { raf ->
                    val zeros = ByteArray(64 * 1024)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                }
            }
        }

        // dynamic recoveries (TWRP, OrangeFox) get librecshim.so preloaded: mount points look mounted, evdev ioctls answered.
        // The shims are bundled per bitness (engines/common for 32-bit, engines/common64 for 64-bit):
        // a recovery's linker refuses a preload of the wrong word size outright, so stamp the set that matches it.
        runCatching {
            val shimDir = if (isElf64(paths)) "engines/common64" else "engines/common"
            for (lib in listOf("librecshim.so", "libaemushim.so")) {
                EngineAssets.open(paths.ctx, "$shimDir/$lib").use { i -> File(r, "sbin/$lib").outputStream().use { i.copyTo(it) } }
                File(r, "sbin/$lib").setReadable(true, false)
            }
        }
        File(r, "dev/aemu_power").let { if (!it.exists()) it.createNewFile() }
        runCatching { File(r, "dhd.fbgeom").writeText("$w $h\n") }

        // Virtual mount table for TWRP/OrangeFox: redirected from /proc/mounts and /proc/self/mounts.
        // The device column spells the by-name parent the guest's own fstab uses (parsed by
        // PartitionSeat, the same parent the by-name links are planted under), so readers of
        // this table see the same devices the tables and the links describe.
        val byName = PartitionSeat.byNameParent(r, paths)
        val mounts = """
rootfs / rootfs rw,relatime 0 0
tmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0
devpts /dev/pts devpts rw,relatime,mode=600 0 0
proc /proc proc rw,relatime 0 0
sysfs /sys sysfs rw,relatime 0 0
$byName/system /system ext4 rw,relatime 0 0
$byName/userdata /data ext4 rw,relatime 0 0
$byName/cache /cache ext4 rw,relatime 0 0
$byName/vendor /vendor ext4 rw,relatime 0 0
$byName/boot /boot emmc rw,relatime 0 0
$byName/recovery /recovery emmc rw,relatime 0 0
/data/media/0 /sdcard vfat rw,relatime 0 0
""".trimIndent()
        runCatching { File(r, "tmp/aemu_mounts").writeText(mounts + "\n") }

        // TWRP theme density & flags in default.prop
        runCatching {
            val defProp = File(r, "default.prop")
            val existing = if (defProp.isFile) defProp.readText() else ""
            val density = settings?.density?.takeIf { it > 0 } ?: 320
            val sb = java.lang.StringBuilder(existing)
            if (!existing.contains("ro.sf.lcd_density")) sb.append("\nro.sf.lcd_density=$density\n")
            if (!existing.contains("ro.twrp.boot")) sb.append("ro.twrp.boot=1\n")
            if (!existing.contains("ro.boot.recovery")) sb.append("ro.boot.recovery=1\n")
            if (!existing.contains("ro.product.model")) sb.append("ro.product.model=AEmulator Guest\n")
            defProp.writeText(sb.toString())
        }

        // the system's partitions and memory card, for recoveries that browse or flash files
        for ((name, target) in listOf("system" to File(paths.root, "system"), "data" to File(paths.root, "data"), "cache" to File(paths.root, "cache"))) {
            val link = File(r, name)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && (!link.exists() || (link.isDirectory && !hasFiles(link)))) {
                ImageStore.wipe(link); runCatching { Os.symlink(target.absolutePath, link.path) }
            }
        }
        plantSystemBuildProp(r, paths, log)
        if (sdcard != null) {
            val link = File(r, "sdcard")
            if (link.isDirectory && link.list().isNullOrEmpty()) { link.delete(); runCatching { Os.symlink(sdcard.absolutePath, link.path) } }
        }
        // partition seat (layer 1): the by-name devices and the mount table for this root too —
        // a recovery script unmounting /system died with ENOENT because the by-name node it
        // stats never existed. Only with the image's bootSeat setting on and a kept boot image.
        if (settings?.bootSeat == true && PartitionSeat.bootImage(paths).isFile) {
            PartitionSeat.plantByName(r, paths)
            PartitionSeat.arm(r)
        }
    }

    /**
     * Scripts running in a recovery read the SYSTEM's build.prop at /system/build.prop
     * before anything else — Magisk's installer asks that file for the system's sdk and
     * abi and only falls back to getprop when the cat fails. On hardware the recovery has
     * the real system mounted there; here /system is the ramdisk's own mount-point
     * directory (it holds the ramdisk's tzdata, so the link loop above leaves it a real
     * directory) and the file simply never exists. The fallback then answers with the
     * recovery's own generation — api 23 from its default.prop, the value the seeded
     * property area carries — and the installer judges the system by it: Magisk v31.1
     * refuses anything below api 24 while this guest's system is 27. Plant just that one
     * file out of the guest's system tree, refreshed every session so a re-imported
     * firmware is picked up; nothing else under /system is touched, and when /system is
     * the partition link the path already resolves to the tree's own copy, so there is
     * nothing to plant. Fully guarded: a failure here must not block the recovery boot.
     */
    private fun plantSystemBuildProp(r: File, paths: VmPaths, log: (String) -> Unit) {
        runCatching {
            val sysDir = File(r, "system")
            if (java.nio.file.Files.isSymbolicLink(sysDir.toPath())) return
            val src = File(paths.root, "system/build.prop")
            if (!src.isFile) { log("recovery: no system build.prop to plant"); return }
            val dst = File(sysDir, "build.prop")
            if (java.nio.file.Files.isSymbolicLink(dst.toPath())) dst.delete()
            src.copyTo(dst, overwrite = true)
            dst.setReadable(true, false)
            val sdk = Regex("""(?m)^ro\.build\.version\.sdk=(.+)$""").find(dst.readText())
                ?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "?" }
            log("recovery: planted system build.prop (sdk $sdk)")
        }
    }

    /** a ramdisk mount point may hold empty folders (TWRP: system/bin); it still gets linked to the partition */
    private fun hasFiles(d: File): Boolean = d.listFiles().orEmpty().any {
        java.nio.file.Files.isSymbolicLink(it.toPath()) || !it.isDirectory || hasFiles(it)
    }
}
