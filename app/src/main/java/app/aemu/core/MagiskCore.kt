/* AEmulator Plus addition, 2026-11-21. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

/**
 * Magisk for the native (n64, arm64) engines, host side. Plain java.io / java.nio on purpose: no Android classes, so it
 * can be run and tested on a desktop JVM.
 *
 * Magisk expects a real boot: magiskinit patches init, a tmpfs ("/debug_ramdisk" or "/sbin") holds the binaries and the
 * daemon's socket, magic mount bind-mounts module files over /system. None of that exists here (the native engine
 * has no kernel of its own: mount/unshare/setns are accepted and ignored by the shim), so the host plays the part
 * of the boot stage instead:
 *
 *  - [install] unpacks the binaries and scripts of a Magisk APK into the guest's /data/adb/magisk, as Magisk's own
 *    fix_env does;
 *  - [prepareTmp] builds the tmpfs stand-in /debug_ramdisk before every boot (the daemon finds it through
 *    "/debug_ramdisk/.magisk", the first place Magisk looks);
 *  - [linkSystem] puts su / magisk / resetprop on the guest's PATH;
 *  - [Overlay] does what magic mount would, by copying module files into the tree before the system starts, and
 *    undoes it (exactly) before the next boot, so disabling or removing a module works.
 *
 * The daemon itself is started by the VM (GuestVm) with the same commands init would use:
 * "magisk --post-fs-data", "magisk --service", "magisk --boot-complete".
 */
internal object MagiskCore {
    /** Magisk looks for "<dir>/.magisk" in /debug_ramdisk, then /sbin, then /dev. */
    const val TMP = "debug_ramdisk"
    const val PACKAGE = "com.topjohnwu.magisk"
    const val BIN = "data/adb/magisk"
    const val APK_COPY = "data/adb/aemu-magisk.apk"
    private const val STATE = "data/adb/aemu-magisk.properties"
    /** Present while the Magisk app still has to be installed into the guest: queued by [install], done by the VM once a boot is over. */
    private const val APP_PENDING = "data/adb/aemu-magisk.install"
    private val SYSTEM_LINKS = listOf("su", "magisk", "resetprop", "magiskpolicy")
    private const val ORIG = ".aemu-orig"

    /** The directories of the magic-mount worker that su is injected through; see [prepareTmp]. */
    private val WORKER_DIRS = listOf("system", "system/bin", "system/xbin")

    /** The shim's switch for real mounts and device nodes (native/guest64/vmount.h), its mount table and the tmpfs directories. */
    const val VMOUNT_FLAG = "dev/.aemu-vmount"
    private val VMOUNT_FILES = listOf(VMOUNT_FLAG, "dev/.aemu-mounts", "dev/.aemu-tmpfs", "dev/.aemu-exe")   // .aemu-exe: guest program per pid, for /proc/<pid>/exe

    /** What mknod of a character/block device leaves behind (a link with this target, see vmount.h); a 0:0 character device is a whiteout. */
    private const val DEV_PREFIX = "\u0001aemu-dev:"
    private const val WHITEOUT = DEV_PREFIX + "c:0:0:"
    /** The stand-in for the preinit partition: block device 259:1 (a blkext minor), mode 0600; see [prepareTmp]. */
    private const val PREINIT_NODE = DEV_PREFIX + "b:259:1:600"
    private const val ABI = "arm64-v8a"

    data class Info(val version: String, val code: Int)

    private val R_VER = Regex("""MAGISK_VER='([^']*)'""")
    private val R_CODE = Regex("""MAGISK_VER_CODE=(\d+)""")

    // ---- state ---------------------------------------------------------------------------------------------

    fun info(root: File): Info? = runCatching {
        val p = java.util.Properties().apply { File(root, STATE).inputStream().use { load(it) } }
        if (!File(root, "$BIN/magisk").isFile) return null
        Info(p.getProperty("version") ?: return null, p.getProperty("code")?.toInt() ?: return null)
    }.getOrNull()

    /** An import (or a replacement) of the Magisk APK is waiting to be installed as an app at the next boot. */
    fun appInstallPending(root: File): Boolean = File(root, APP_PENDING).isFile

    /** The queued install has been called (or is no longer possible): delete the flag so that it runs only once. */
    fun clearAppInstall(root: File) { File(root, APP_PENDING).delete() }

    // ---- install -------------------------------------------------------------------------------------------

    /**
     * Unpacks [apk] (the Magisk app; the stub is not enough: it carries no binaries) into the tree.
     * Throws with a readable message when the file is not a usable Magisk APK.
     */
    fun install(root: File, apk: File): Info {
        ZipFile(apk).use { z ->
            fun entry(vararg names: String) = names.firstNotNullOfOrNull { z.getEntry(it) }
            fun lib(vararg names: String) = entry(*names.map { "lib/$ABI/$it" }.toTypedArray())
            val utilEntry = z.getEntry("assets/util_functions.sh")
                ?: error("This is not a Magisk APK (assets/util_functions.sh is missing)")
            val util = z.getInputStream(utilEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
            val version = R_VER.find(util)?.groupValues?.get(1) ?: error("Cannot read the Magisk version")
            val code = R_CODE.find(util)?.groupValues?.get(1)?.toIntOrNull() ?: error("Cannot read the Magisk version code")
            val main = lib("libmagisk.so", "libmagisk64.so")
                ?: error("This Magisk APK has no arm64 binaries (it may be the stub app: use the full APK)")
            val dest = File(root, "data/adb")
            val staging = File(dest, "magisk.new")
            deleteTree(staging.toPath())
            staging.mkdirs()
            fun put(name: String, e: java.util.zip.ZipEntry?, exec: Boolean) {
                if (e == null) return
                val f = File(staging, name)
                z.getInputStream(e).use { Files.copy(it, f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                f.setReadable(true, false); f.setExecutable(exec, false)
            }
            put("magisk", main, true)
            put("magisk64", main, true)   // the name the 24-27 daemon looks for
            lib("libmagisk32.so")?.let { put("magisk32", it, true) }
                ?: z.getEntry("lib/armeabi-v7a/libmagisk.so")?.let { put("magisk32", it, true) }
            put("magiskinit", lib("libmagiskinit.so"), true)
            put("magiskboot", lib("libmagiskboot.so"), true)
            put("magiskpolicy", lib("libmagiskpolicy.so"), true)
            put("busybox", lib("libbusybox.so"), true)
            put("init-ld", lib("libinit-ld.so"), true)
            put("stub.apk", entry("assets/stub.apk"), false)
            for (script in listOf("util_functions.sh", "boot_patch.sh", "addon.d.sh", "app_functions.sh", "module_installer.sh", "uninstaller.sh"))
                put(script, z.getEntry("assets/$script"), false)
            if (!File(staging, "busybox").isFile) error("This Magisk APK has no busybox")

            val bin = File(root, BIN)
            deleteTree(bin.toPath())
            Files.move(staging.toPath(), bin.toPath())
            File(root, "data/adb").apply { setReadable(true, false); setExecutable(true, false) }
            File(root, APK_COPY).let { copy ->
                Files.copy(apk.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                copy.setReadable(true, false)
            }
            for (d in listOf("modules", "modules_update", "post-fs-data.d", "service.d", "boot-completed.d"))
                File(root, "data/adb/$d").mkdirs()
            File(root, STATE).writeText("version=$version\ncode=$code\n")
            File(root, APP_PENDING).writeText("$version\n")   // queue: the Magisk app is (re)installed at the next boot
            return Info(version, code)
        }
    }

    /** The firmware has a 64-bit side (a linker64 in /system/bin): Magisk's arm64 build runs on its bionic. */
    fun hasArm64Userland(root: File): Boolean {
        val bin = resolveInRoot(root, "/system/bin") ?: return false
        return Files.exists(File(bin, "linker64").toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    /** Is there anything of a previous boot's Magisk set-up left in the tree (stand-in tmpfs, overlay, system links)? */
    fun leftovers(root: File): Boolean =
        File(root, TMP).exists() || File(root, Overlay.DIR).exists() || File(root, VMOUNT_FLAG).exists() ||
            listOf("/system/bin", "/system/xbin").any { d ->
                val dir = resolveInRoot(root, d) ?: return@any false
                SYSTEM_LINKS.any { n -> isOurLink(root, dir, n) }
            }

    /** Undoes everything a boot with Magisk put into the tree (overlay, links, stand-in tmpfs); the installed files stay. */
    fun deactivate(root: File, log: (String) -> Unit = {}) {
        Overlay.revert(root, log)
        unlinkSystem(root)
        deleteTree(File(root, TMP).toPath())
        resetMounts(root, enable = false)
    }

    /**
     * Mounts never outlive a boot: the shim's table and the directories behind its tmpfs mounts are deleted, then (with [enable])
     * the flag that makes the shim honour mount/umount and device nodes is put back. Must run before the first guest process
     * that should see them starts (each process looks for the flag once, when it starts).
     */
    fun resetMounts(root: File, enable: Boolean) {
        for (f in VMOUNT_FILES) deleteTree(File(root, f).toPath())
        if (enable) {
            File(root, "dev").mkdirs()
            File(root, VMOUNT_FLAG).writeText("1\n")
        }
    }

    /** Is [p] what `mknod x c 0 0` leaves behind in the guest (an overlay whiteout)? */
    fun isWhiteout(p: Path): Boolean =
        Files.isSymbolicLink(p) && runCatching { Files.readSymbolicLink(p).toString().startsWith(WHITEOUT) }.getOrDefault(false)

    private fun isDeviceMarker(p: Path): Boolean =
        Files.isSymbolicLink(p) && runCatching { Files.readSymbolicLink(p).toString().startsWith(DEV_PREFIX) }.getOrDefault(false)

    /** Takes Magisk out: [deactivate], then binaries deleted. Modules and magisk.db stay. */
    fun uninstall(root: File, log: (String) -> Unit = {}) {
        deactivate(root, log)
        deleteTree(File(root, BIN).toPath())
        for (f in listOf(STATE, APK_COPY, APP_PENDING)) File(root, f).delete()
    }

    // ---- Zygisk ----------------------------------------------------------------------------------------------

    /** Zygisk is a native bridge: the daemon (post-fs-data, setting "zygisk" in magisk.db) sets this property to [ZYGISK_LDR] (+ the old value). */
    const val NATIVE_BRIDGE_PROP = "ro.dalvik.vm.native.bridge"
    const val ZYGISK_LDR = "libzygisk.so"

    /**
     * The file name the daemon put into [NATIVE_BRIDGE_PROP] when Zygisk is on, else null. [prop] is the property's value read
     * after "magisk --post-fs-data". The daemon's own magic mount (which would mount the magisk binary under that name in
     * /system/lib64) is ignored by the shim, so [Overlay.apply] copies it there.
     */
    fun zygiskLibName(prop: String): String? {
        val n = prop.trim()
        return n.takeIf { it.startsWith(ZYGISK_LDR) && it.length <= 90 && n.none { c -> c.isWhitespace() || c == '/' || c == '\u0000' } }
    }

    // ---- per boot ------------------------------------------------------------------------------------------

    /**
     * Magisk leaves /data/adb/magisk.db at mode 000, which only a real root can still open. The guest's root is a plain host
     * user here, so a 000 file locks the daemon (and every su request that needs a policy) out. Gives the owner access back.
     * Returns true when the mode had to be changed.
     */
    fun fixDbMode(root: File): Boolean {
        val db = resolveInRoot(root, "/data/adb/magisk.db")?.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) } ?: return false
        if (db.canRead() && db.canWrite()) return false
        db.setReadable(true, true); db.setWritable(true, true)
        return true
    }

    /** The tmpfs stand-in. Rebuilt from scratch before each boot, like a real tmpfs would be. */
    fun prepareTmp(root: File) {
        val bin = File(root, BIN)
        require(File(bin, "magisk").isFile) { "Magisk is not installed in this VM" }
        fixDbMode(root)
        resetMounts(root, enable = true)
        val tmp = File(root, TMP)
        deleteTree(tmp.toPath())
        for (d in listOf(".magisk", ".magisk/device", ".magisk/worker", ".magisk/mirror", ".magisk/rootdir", ".magisk/busybox"))
            File(tmp, d).mkdirs()
        // Magisk's magic mount (it still runs in post-fs-data, to put its own su into /system/bin) makes ".magisk/worker/system" with
        // mode 000 and then "mkdir worker/system/bin" inside it. A real root walks through; the guest's root is a plain host user,
        // so it died with "mkdir '/debug_ramdisk/.magisk/worker/system/bin': Permission denied". Magisk ignores EEXIST, so the
        // directories are made here, traversable; Magisk copies the real directory's mode onto them afterwards.
        for (d in WORKER_DIRS) File(tmp, ".magisk/worker/$d").mkdirs()
        fun copyExec(from: File, to: File) {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            to.setReadable(true, false); to.setExecutable(true, false)
        }
        copyExec(File(bin, "magisk"), File(tmp, "magisk"))
        File(bin, "magisk32").takeIf { it.isFile }?.let { copyExec(it, File(tmp, "magisk32")) }   // zygiskd32 / the 32-bit loader look for it here
        for (n in listOf("su", "resetprop", "magiskpolicy", "supolicy"))
            Files.createSymbolicLink(File(tmp, n).toPath(), java.nio.file.Paths.get("magisk"))   // applets, picked by argv[0]
        File(bin, "busybox").takeIf { it.isFile }?.let { copyExec(it, File(tmp, ".magisk/busybox/busybox")) }
        // The Magisk app (HomeViewModel.ensureEnv -> app_functions.sh env_check) asks for a block device at .magisk/device/preinit
        // (magiskinit makes it from the partition that holds the preinit files) and, without one, shows "Your device needs reflash
        // Magisk" (exit code 2) however well everything else works. There is no partition here; a block-device marker of the shim
        // (vmount.h: the guest sees S_IFBLK, `[ -b ]` is true) is all the check looks at. The daemon only compares its device number
        // with the mountinfo (no match: "preinit dir not found", as before), it never opens it.
        Files.createSymbolicLink(File(tmp, ".magisk/device/preinit").toPath(), java.nio.file.Paths.get(PREINIT_NODE))
        // what the daemon would find in the ramdisk's config (it only logs when the file is missing)
        File(tmp, ".magisk/config").writeText("RECOVERYMODE=false\nSPLITCOMPILE=false\n")
        // magiskd bind-mounts /data/adb/modules here; a mount is a no-op in the guest, so make it the same directory
        Files.createSymbolicLink(File(tmp, ".magisk/modules").toPath(), java.nio.file.Paths.get("../../data/adb/modules"))
        for (d in listOf("modules", "modules_update", "post-fs-data.d", "service.d", "boot-completed.d")) File(root, "data/adb/$d").mkdirs()
        // world-traversable like the real thing (the guest's ids are faked: the files read as root's)
        tmp.walkTopDown().filter { it.isDirectory }.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
    }

    /** su, magisk and resetprop in /system/bin (and xbin where there is one), pointing into the tmpfs stand-in. */
    fun linkSystem(root: File, log: (String) -> Unit = {}) {
        for (guestDir in listOf("/system/bin", "/system/xbin")) {
            val dir = resolveInRoot(root, guestDir)?.takeIf { Files.isDirectory(it.toPath()) } ?: continue
            for (n in SYSTEM_LINKS) {
                val link = File(dir, n).toPath()
                // relative: the shim hands paths to the phone's kernel, which would resolve an absolute link target on the PHONE
                // (execve of /system/bin/su -> /debug_ramdisk/su was "su: inaccessible or not found")
                val target = linkTarget(root, dir, n)
                if (isOurLink(root, dir, n) && Files.readSymbolicLink(link).toString() == target) continue
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    val keep = File(dir, n + ORIG).toPath()
                    if (!Files.exists(keep, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(link, keep)
                        log("magisk: $guestDir/$n kept as $n$ORIG")
                    } else Files.delete(link)
                }
                Files.createSymbolicLink(link, java.nio.file.Paths.get(target))
            }
        }
    }

    /** Where su & co. point from [dir]: relative, up to the root of the tree and into the stand-in tmpfs. */
    private fun linkTarget(root: File, dir: File, n: String): String =
        dir.toPath().toAbsolutePath().normalize().relativize(root.toPath().toAbsolutePath().normalize().resolve("$TMP/$n")).toString()

    /** One of ours: the relative link, or the absolute one an earlier version made. */
    private fun isOurLink(root: File, dir: File, n: String): Boolean {
        val link = File(dir, n).toPath()
        if (!Files.isSymbolicLink(link)) return false
        val t = Files.readSymbolicLink(link).toString()
        return t == "/$TMP/$n" || t == linkTarget(root, dir, n)
    }

    private fun unlinkSystem(root: File) {
        for (guestDir in listOf("/system/bin", "/system/xbin")) {
            val dir = resolveInRoot(root, guestDir)?.takeIf { it.isDirectory } ?: continue
            for (n in SYSTEM_LINKS) {
                val link = File(dir, n).toPath()
                if (isOurLink(root, dir, n)) Files.delete(link)
                val keep = File(dir, n + ORIG).toPath()
                if (Files.exists(keep, LinkOption.NOFOLLOW_LINKS) && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.move(keep, link)
            }
        }
    }

    // ---- module files (what magic mount does) ---------------------------------------------------------------

    /**
     * Copies every enabled module's system/ over the tree and remembers exactly what it did in a journal, so [revert]
     * puts the tree back byte for byte. [apply] always reverts first. Module state files are honoured: "disable",
     * "remove" and "skip_mount" keep a module out.
     *
     * Whiteouts (a 0:0 character device, which the shim's mknod makes) delete the stock file or directory of that name; the
     * original is moved into the journal's backup like any replaced file, so [revert] brings it back.
     * A directory with a ".replace" file replaces the stock directory as a whole, as in magic mount.
     */
    object Overlay {
        const val DIR = "data/adb/aemu-overlay"

        private class Journal(val root: File) {
            val dir = File(root, DIR)
            val file = File(dir, "journal")
            val orig = File(dir, "orig")
            private var out: java.io.Writer? = null
            private var next = 0
            fun open() {
                orig.mkdirs()
                out = java.io.FileWriter(file, true)
                next = (orig.list()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: -1) + 1
            }
            fun add(kind: Char, rel: String, bk: String? = null) {
                out!!.write("$kind\t$rel${if (bk != null) "\t$bk" else ""}\n"); out!!.flush()
            }
            fun newBackup(): String = (next++).toString()
            fun close() { out?.close(); out = null }
        }

        fun revert(root: File, log: (String) -> Unit = {}): Int {
            val dir = File(root, DIR)
            val journal = File(dir, "journal")
            if (!journal.isFile) { deleteTree(dir.toPath()); return 0 }
            var undone = 0
            for (line in journal.readLines().asReversed()) {
                val f = line.split('\t')
                if (f.size < 2) continue
                val p = File(root, f[1]).toPath()
                try {
                    when (f[0]) {
                        "F" -> { if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.delete(p) }
                        "D" -> { runCatching { Files.delete(p) } }    // only if empty
                        "R", "P" -> {
                            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) deleteTree(p)
                            Files.move(File(dir, "orig/${f[2]}").toPath(), p)
                        }
                    }
                    undone++
                } catch (e: Exception) { log("magisk overlay: cannot undo ${f[0]} ${f[1]}: ${e.message}") }
            }
            deleteTree(dir.toPath())
            return undone
        }

        /** @return how many files and links were put in place */
        fun apply(root: File, zygiskLib: String? = null, log: (String) -> Unit = {}): Int {
            revert(root, log)
            val modules = File(root, "data/adb/modules").listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
            val j = Journal(root)
            var count = 0
            try {
                j.open()
                for (m in modules) {
                    if (listOf("disable", "remove", "skip_mount").any { File(m, it).exists() }) continue
                    val sys = File(m, "system")
                    if (!sys.isDirectory) continue
                    val n = overlayDir(root, sys.toPath(), "/system", j, log)
                    if (n > 0) log("magisk: module ${m.name}: $n file(s)")
                    count += n
                }
                if (zygiskLib != null) count += placeZygisk(root, zygiskLib, j, log)
            } finally { j.close() }
            if (count == 0) deleteTree(File(root, DIR).toPath())
            return count
        }

        /**
         * What Magisk's magic mount does for Zygisk: the magisk binary under the native-bridge name in /system/lib64 (journaled,
         * so [revert] removes it). Only the 64-bit side: the 32-bit zygote runs under qemu-arm, where Zygisk's PLT hooks are not
         * known to work; it then fails to load the bridge, logs it and starts without Zygisk.
         */
        private fun placeZygisk(root: File, name: String, j: Journal, log: (String) -> Unit): Int {
            val src = File(root, "$BIN/magisk").takeIf { it.isFile } ?: return 0
            val dir = resolveInRoot(root, "/system/lib64")?.takeIf { Files.isDirectory(it.toPath()) } ?: run { log("magisk zygisk: no /system/lib64, not injected"); return 0 }
            val dest = File(dir, name).toPath()
            val ok = place(root, dest, j, log) {
                Files.copy(src.toPath(), dest)
                dest.toFile().apply { setReadable(true, false); setExecutable(true, false) }
            }
            if (ok) log("magisk zygisk: $name placed in /system/lib64")
            return if (ok) 1 else 0
        }

        private fun rel(root: File, p: Path): String = root.toPath().toAbsolutePath().relativize(p.toAbsolutePath()).toString()

        private fun overlayDir(root: File, src: Path, guestDir: String, j: Journal, log: (String) -> Unit): Int {
            var phys = resolveInRoot(root, guestDir)
            if (phys == null) { log("magisk overlay: cannot resolve $guestDir"); return 0 }
            if (!Files.exists(phys.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                phys.mkdirs(); j.add('D', rel(root, phys.toPath())); phys.setReadable(true, false); phys.setExecutable(true, false)
            } else if (!phys.isDirectory) { log("magisk overlay: $guestDir is not a directory in the tree, skipped"); return 0 }
            if (File(src.toFile(), ".replace").exists() && guestDir != "/system") {
                val bk = j.newBackup()
                Files.move(phys.toPath(), File(j.orig, bk).toPath())
                j.add('P', rel(root, phys.toPath()), bk)
                phys.mkdirs(); phys.setReadable(true, false); phys.setExecutable(true, false)
            }
            var n = 0
            val entries = src.toFile().listFiles()?.sortedBy { it.name } ?: return 0
            for (e in entries) {
                val name = e.name
                if (name == ".replace" || name.any { it == '\t' || it == '\n' || it == '/' }) continue
                val sp = e.toPath()
                val childGuest = "$guestDir/$name"
                val dest = File(phys, name).toPath()
                when {
                    isWhiteout(sp) -> { if (remove(root, dest, j, log)) n++ }
                    isDeviceMarker(sp) -> log("magisk overlay: $childGuest is a device node other than a whiteout, skipped")
                    Files.isSymbolicLink(sp) -> { place(root, dest, j, log) { Files.createSymbolicLink(dest, Files.readSymbolicLink(sp)) }; n++ }
                    Files.isDirectory(sp, LinkOption.NOFOLLOW_LINKS) -> n += overlayDir(root, sp, childGuest, j, log)
                    Files.isRegularFile(sp, LinkOption.NOFOLLOW_LINKS) -> {
                        val ok = place(root, dest, j, log) {
                            Files.copy(sp, dest)
                            val f = dest.toFile()
                            f.setReadable(true, false)
                            f.setExecutable(e.canExecute() || guestDir.endsWith("/bin") || guestDir.endsWith("/xbin"), false)
                        }
                        if (ok) n++
                    }
                    else -> log("magisk overlay: $childGuest is a special file, not supported")
                }
            }
            return n
        }

        /** A whiteout: moves what is at [dest] (a file, a link or a whole directory) into the backup. false when there was nothing. */
        private fun remove(root: File, dest: Path, j: Journal, log: (String) -> Unit): Boolean {
            if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) return false
            val bk = j.newBackup()
            Files.move(dest, File(j.orig, bk).toPath())
            j.add('R', rel(root, dest), bk)
            log("magisk overlay: ${rel(root, dest)} removed (whiteout)")
            return true
        }

        /** Moves whatever is at [dest] into the backup (journaled), then runs [create]. false when it cannot be replaced. */
        private fun place(root: File, dest: Path, j: Journal, log: (String) -> Unit, create: () -> Unit): Boolean {
            val exists = Files.exists(dest, LinkOption.NOFOLLOW_LINKS)
            if (exists && Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                log("magisk overlay: ${rel(root, dest)} is a directory in the tree, a module file cannot replace it"); return false
            }
            if (exists) {
                val bk = j.newBackup()
                Files.move(dest, File(j.orig, bk).toPath())
                j.add('R', rel(root, dest), bk)
            } else j.add('F', rel(root, dest))
            create()
            return true
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * Resolves a guest path inside [root] without ever looking at the host's own filesystem: an absolute symlink
     * (/system/vendor -> /vendor) is taken relative to the root, ".." never leaves it. Every component is followed,
     * so call it on the directory, not on a file you want to replace.
     */
    fun resolveInRoot(root: File, guestPath: String): File? {
        val rootP = root.toPath().toAbsolutePath().normalize()
        var cur = rootP
        val todo = ArrayDeque(guestPath.split('/').filter { it.isNotEmpty() })
        var hops = 0
        while (todo.isNotEmpty()) {
            val c = todo.removeFirst()
            if (c == ".") continue
            if (c == "..") { if (cur != rootP) cur = cur.parent; continue }
            val next = cur.resolve(c)
            if (Files.isSymbolicLink(next)) {
                if (++hops > 40) return null
                val t = Files.readSymbolicLink(next).toString()
                if (t.startsWith("/")) cur = rootP
                t.split('/').filter { it.isNotEmpty() }.asReversed().forEach { todo.addFirst(it) }
            } else cur = next
        }
        return cur.toFile()
    }

    /**
     * Gives the owner back access to every directory below [f] (never following a link). Magisk's magic mount makes its worker
     * directories with mode 000 (".magisk/worker/system" is one); the guest's root is a plain host user here, so such a directory
     * cannot even be listed, and the delete of the stand-in tmpfs before the next boot died on it: no su, no daemon socket.
     */
    private fun unlock(f: File) {
        if (Files.isSymbolicLink(f.toPath())) return
        if (f.isDirectory || (f.exists() && !f.isFile)) {
            f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
            f.listFiles()?.forEach { unlock(it) }
        }
    }

    /**
     * Gives the owner access to every directory of the stand-in tmpfs again. Magisk makes some of its directories with mode 000;
     * run after each stage so that nothing in the daemon's next stage (or the delete before the next boot) trips over one.
     */
    fun unlockTmp(root: File) = unlock(File(root, TMP))

    /** Recursive delete that never follows a symlink. */
    fun deleteTree(p: Path) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        unlock(p.toFile())
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}
