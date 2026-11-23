// Desktop JVM test of MagiskCore (no Android needed):
//   kotlinc app/src/main/java/app/aemu/core/MagiskCore.kt tests/MagiskCoreTest.kt -include-runtime -d /tmp/t.jar
//   java -jar /tmp/t.jar /path/to/Magisk.apk
package app.aemu.core
import java.io.File
import java.nio.file.*
import java.security.MessageDigest

fun snap(root: File): List<String> {
    val out = ArrayList<String>()
    Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(d: Path, a: java.nio.file.attribute.BasicFileAttributes): FileVisitResult { out += "D ${root.toPath().relativize(d)}"; return FileVisitResult.CONTINUE }
        override fun visitFile(f: Path, a: java.nio.file.attribute.BasicFileAttributes): FileVisitResult {
            val r = root.toPath().relativize(f)
            out += if (a.isSymbolicLink) "L $r -> ${Files.readSymbolicLink(f)}"
                   else "F $r ${MessageDigest.getInstance("MD5").digest(Files.readAllBytes(f)).joinToString("") { "%02x".format(it) }}"
            return FileVisitResult.CONTINUE
        }
    })
    return out.sorted()
}
fun check(c: Boolean, m: String) { if (!c) { println("FAIL: $m"); System.exit(1) } else println("ok: $m") }

fun main(a: Array<String>) {
    val apk = File(a[0])
    val root = Files.createTempDirectory("aemuroot").toFile()
    File(root, "system/bin").mkdirs(); File(root, "system/xbin").mkdirs(); File(root, "system/lib").mkdirs()
    File(root, "vendor/lib").mkdirs(); File(root, "vendor/lib/orig.so").writeText("stock-vendor")
    Files.createSymbolicLink(File(root, "system/vendor").toPath(), Paths.get("/vendor"))   // absolute guest link: must NOT hit host /vendor
    File(root, "system/bin/sh").writeText("sh")
    File(root, "system/xbin/su").writeText("rom-su")              // a ROM-provided su
    File(root, "system/lib/libfoo.so").writeText("stock-foo")
    File(root, "system/app/Stock").mkdirs(); File(root, "system/app/Stock/Stock.apk").writeText("stock-apk")
    File(root, "dev").mkdirs()
    val base = snap(root)

    val info = MagiskCore.install(root, apk)
    check(info.version == "31.1" && info.code == 31100, "install parsed ${info.version} (${info.code})")
    check(MagiskCore.info(root) == info, "info() reads state back")
    for (f in listOf("magisk", "busybox", "magiskpolicy", "magiskinit", "magiskboot", "util_functions.sh", "boot_patch.sh", "init-ld"))
        check(File(root, "data/adb/magisk/$f").isFile, "data/adb/magisk/$f")
    check(File(root, "data/adb/magisk/magisk").length() == 384440L, "arm64 libmagisk.so extracted intact (384440 bytes)")

    // modules
    fun mod(id: String, vararg files: Pair<String, String>) { for ((p, c) in files) File(root, "data/adb/modules/$id/$p").apply { parentFile.mkdirs(); writeText(c) } }
    mod("a_mod", "system/lib/libfoo.so" to "patched-foo", "system/bin/newtool" to "tool", "system/vendor/lib/extra.so" to "extra", "system/vendor/lib/orig.so" to "patched-vendor", "module.prop" to "id=a_mod")
    mod("b_replace", "system/app/Stock/.replace" to "", "system/app/Stock/New.apk" to "new-apk")
    mod("c_disabled", "system/lib/libfoo.so" to "SHOULD-NOT-APPEAR", "disable" to "")
    mod("d_remove", "system/lib/libfoo.so" to "SHOULD-NOT-APPEAR2", "remove" to "")
    val afterInstall = snap(root)

    MagiskCore.prepareTmp(root)
    MagiskCore.linkSystem(root) { println("  log: $it") }
    check(Files.readSymbolicLink(File(root, "system/bin/su").toPath()).toString() == "../../debug_ramdisk/su", "system/bin/su -> ../../debug_ramdisk/su (relative: the phone's kernel resolves the path)")
    check(Files.readSymbolicLink(File(root, "system/xbin/su").toPath()).toString() == "../../debug_ramdisk/su", "system/xbin/su linked")
    check(File(root, "system/xbin/su.aemu-orig").readText() == "rom-su", "ROM su kept aside")
    check(Files.readSymbolicLink(File(root, "debug_ramdisk/.magisk/modules").toPath()).toString() == "../../data/adb/modules", "modules dir alias")
    check(File(root, "debug_ramdisk/magisk").length() == 384440L && File(root, "debug_ramdisk/.magisk/busybox/busybox").isFile, "tmp binaries")
    check(File(root, "debug_ramdisk/.magisk").isDirectory && File(root, "debug_ramdisk/.magisk/device").isDirectory, "debug_ramdisk/.magisk present")
    check(File(root, MagiskCore.VMOUNT_FLAG).isFile, "the shim's mount/mknod switch is on")
    check(Files.isSymbolicLink(File(root, "debug_ramdisk/.magisk/device/preinit").toPath()) && Files.readSymbolicLink(File(root, "debug_ramdisk/.magisk/device/preinit").toPath()).toString() == "\u0001aemu-dev:b:259:1:600", "preinit block-device marker (env_check -b)")
    File(root, "dev/.aemu-mounts").writeText("stale"); File(root, "dev/.aemu-tmpfs/3").mkdirs()
    // what magiskd's magic mount leaves behind: a directory with mode 000 (it broke the next boot's rebuild of the tmpfs)
    File(root, "debug_ramdisk/.magisk/worker/system/app").mkdirs()
    File(root, "debug_ramdisk/.magisk/worker/system").setReadable(false, false); File(root, "debug_ramdisk/.magisk/worker/system").setExecutable(false, false)
    File(root, "debug_ramdisk/.magisk/worker/system").setWritable(false, false)
    MagiskCore.prepareTmp(root)    // twice: rebuilt, not an error, and a mode-000 directory does not stop it
    check(!File(root, "dev/.aemu-mounts").exists() && !File(root, "dev/.aemu-tmpfs").exists() && File(root, MagiskCore.VMOUNT_FLAG).isFile, "mounts of an earlier boot are gone, the switch stays on")
    check(true, "prepareTmp is repeatable")
    // the magic-mount worker that Magisk makes inside (mode 000 for its own directories) must be enterable
    for (d in listOf("system", "system/bin", "system/xbin"))
        check(File(root, "debug_ramdisk/.magisk/worker/$d").let { it.isDirectory && it.canExecute() && it.canWrite() }, "worker/$d exists and is enterable")
    File(root, "debug_ramdisk/.magisk/worker/system").setExecutable(false, false)      // what Magisk does
    File(root, "debug_ramdisk/.magisk/worker/system").setWritable(false, false)
    MagiskCore.unlockTmp(root)
    check(File(root, "debug_ramdisk/.magisk/worker/system").let { it.canExecute() && it.canWrite() }, "unlockTmp reopens a locked worker directory")

    val before = snap(root)
    val n = MagiskCore.Overlay.apply(root) { println("  log: $it") }
    check(n == 5, "overlay placed 5 files (got $n)")
    check(File(root, "system/lib/libfoo.so").readText() == "patched-foo", "file replaced")
    check(File(root, "system/bin/newtool").readText() == "tool" && File(root, "system/bin/newtool").canExecute(), "new executable in bin")
    check(File(root, "vendor/lib/extra.so").readText() == "extra", "vendor file landed in the ROOT's /vendor via the guest symlink")
    check(File(root, "vendor/lib/orig.so").readText() == "patched-vendor", "vendor file replaced")
    check(!File("/vendor/lib/extra.so").exists(), "host /vendor untouched")
    check(File(root, "system/app/Stock/Stock.apk").exists().not() && File(root, "system/app/Stock/New.apk").readText() == "new-apk", ".replace replaced the directory")
    check(Files.isSymbolicLink(File(root, "system/vendor").toPath()), "system/vendor is still the symlink")
    // idempotent re-apply
    check(MagiskCore.Overlay.apply(root) == 5, "apply twice = same result")
    MagiskCore.Overlay.revert(root)
    check(snap(root) == before, "revert restores the tree byte for byte")
    check(!File(root, "data/adb/aemu-overlay").exists(), "journal removed")

    // a module disabled after the fact stays out
    File(root, "data/adb/modules/a_mod/disable").writeText("")
    check(MagiskCore.Overlay.apply(root) == 1, "disabled module skipped (only b_replace left: 1 file)")
    MagiskCore.Overlay.revert(root)

    // whiteouts: what `mknod x c 0 0` leaves in the guest is a link with a marker target (native/guest64/vmount.h)
    File(root, "data/adb/modules/b_replace/disable").writeText("")
    val wh = "\u0001aemu-dev:c:0:0:0644"
    for (d in listOf("system/lib", "system/app")) Files.createDirectories(File(root, "data/adb/modules/g_wh/$d").toPath())
    Files.createSymbolicLink(File(root, "data/adb/modules/g_wh/system/lib/libfoo.so").toPath(), Paths.get(wh))   // a file
    Files.createSymbolicLink(File(root, "data/adb/modules/g_wh/system/app/Stock").toPath(), Paths.get(wh))      // a whole directory
    Files.createSymbolicLink(File(root, "data/adb/modules/g_wh/system/lib/nothere.so").toPath(), Paths.get(wh)) // nothing to remove
    Files.createSymbolicLink(File(root, "data/adb/modules/g_wh/system/lib/devnull").toPath(), Paths.get("\u0001aemu-dev:c:1:3:0666"))
    check(MagiskCore.isWhiteout(File(root, "data/adb/modules/g_wh/system/lib/libfoo.so").toPath()) && !MagiskCore.isWhiteout(File(root, "data/adb/modules/g_wh/system/lib/devnull").toPath()), "isWhiteout: 0:0 only")
    val before2 = snap(root)
    val n2 = MagiskCore.Overlay.apply(root) { println("  log: $it") }
    check(n2 == 2, "whiteouts removed 2 stock entries (got $n2)")
    check(!File(root, "system/lib/libfoo.so").exists() && !File(root, "system/app/Stock").exists(), "stock file and directory are gone")
    check(!Files.exists(File(root, "system/lib/devnull").toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.exists(File(root, "system/lib/nothere.so").toPath(), LinkOption.NOFOLLOW_LINKS), "no marker leaks into the tree")
    MagiskCore.Overlay.revert(root)
    check(snap(root) == before2, "revert brings the whiteouted files back byte for byte")
    File(root, "data/adb/modules/g_wh").deleteRecursively(); File(root, "data/adb/modules/b_replace/disable").delete()

    MagiskCore.uninstall(root)
    check(!File(root, MagiskCore.VMOUNT_FLAG).exists(), "switch off again after uninstall")
    // uninstall leaves modules/ db; compare everything outside data/adb with the original
    val after = snap(root).filter { !it.contains("data/adb") && it != "D data" }
    val b0 = base.filter { !it.contains("data/adb") && it != "D data" }; if (after != b0) { println("EXTRA: " + (after - b0.toSet())); println("MISSING: " + (b0 - after.toSet())) }
    check(after == b0, "uninstall restores the system tree exactly")
    check(!File(root, "debug_ramdisk").exists(), "tmp gone")
    // bad apk
    val bad = File.createTempFile("bad", ".apk"); java.util.zip.ZipOutputStream(bad.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("x")); it.closeEntry() }
    check(runCatching { MagiskCore.install(root, bad) }.exceptionOrNull()?.message?.contains("not a Magisk APK") == true, "non-Magisk APK rejected")
        // Zygisk: the native-bridge name, the binary placed in /system/lib64 and taken out again
    check(MagiskCore.zygiskLibName("libzygisk.so") == "libzygisk.so" && MagiskCore.zygiskLibName("0") == null &&
        MagiskCore.zygiskLibName("libzygisk.solibhoudini.so") == "libzygisk.solibhoudini.so" && MagiskCore.zygiskLibName("libzygisk.so/../x") == null, "zygiskLibName")
    run {
        val z = Files.createTempDirectory("aemuzyg").toFile()
        File(z, "system/lib64").mkdirs(); File(z, "data/adb/magisk").mkdirs(); File(z, "data/adb/magisk/magisk").writeText("MAGISK-BIN")
        val before = snap(z)
        check(MagiskCore.Overlay.apply(z, "libzygisk.so") == 1 && File(z, "system/lib64/libzygisk.so").readText() == "MAGISK-BIN", "zygisk lib placed in system/lib64")
        MagiskCore.Overlay.revert(z)
        check(!File(z, "system/lib64/libzygisk.so").exists() && !File(z, MagiskCore.Overlay.DIR).exists(), "zygisk lib reverted")
        check(snap(z) == before, "tree identical after revert")
    }
    println("ALL OK")
}
