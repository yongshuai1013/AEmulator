// Desktop JVM test of ModuleHost (no Android needed):
//   kotlinc app/src/main/java/app/aemu/core/ModuleHost.kt tests/ModuleHostTest.kt -include-runtime -d /tmp/mt.jar
//   java -jar /tmp/mt.jar
// (Not run in CI; like MagiskCoreTest it is a manual desktop check of the placement/ledger semantics.)
package app.aemu.core
import java.io.File
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

fun moduleZip(entries: Map<String, String>): File {
    val f = Files.createTempFile("mod", ".zip").toFile()
    ZipOutputStream(f.outputStream()).use { z ->
        for ((name, text) in entries) {
            z.putNextEntry(ZipEntry(name))
            z.write(text.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }
    return f
}

fun main() {
    val root = Files.createTempDirectory("aemuroot").toFile()
    val store = Files.createTempDirectory("aemustore").toFile()
    File(root, "system/etc").mkdirs()
    File(root, "system/etc/hosts").writeText("127.0.0.1 localhost\n")
    File(root, "data/adb/modules").mkdirs()
    val base = snap(root)
    val modes = ModuleHost.DEFAULT_MODES
    val log: (String) -> Unit = { println("log: $it") }

    // a zip without module.prop is not a module
    val notModule = moduleZip(mapOf("system/etc/x.txt" to "x"))
    check(runCatching { ModuleHost.install(root, store, notModule, modes, log) }.isFailure, "zip without module.prop refused")
    check(snap(root) == base, "tree untouched after the refusal")

    val zip = moduleZip(mapOf(
        "module.prop" to "id=testmod\nname=Test Mod\nversion=1.0\nversionCode=1\nauthor=t\ndescription=d\n",
        "system/etc/hosts" to "127.0.0.1 localhost\n# testmod\n",
        "system/etc/testmod.txt" to "new file\n",
        "system.prop" to "ro.testmod=1\n",
        "service.sh" to "#!/system/bin/sh\necho ran > /data/local/tmp/testmod.ran\n",
        "sepolicy.rule" to "allow testmod self file read;\n",
        "zygisk/arm64-v8a.so" to "dummy",
    ))
    val report = ModuleHost.install(root, store, zip, modes, log)
    check(report.info.id == "testmod" && report.info.enabled, "installed testmod")
    check(report.warnings.any { it.contains("Zygisk") }, "zygisk warning reported: ${report.warnings}")
    check(report.warnings.any { it.contains("sepolicy.rule") }, "sepolicy warning reported")
    check(File(root, "system/etc/hosts").readText().contains("# testmod"), "hosts overwritten with the module's content")
    check(File(root, "system/etc/testmod.txt").isFile, "new file placed")
    check(File(root, "data/adb/modules/testmod/module.prop").isFile, "module tree in /data/adb/modules/testmod")
    check(File(store, "ledger.tsv").isFile, "ledger written")
    check(ModuleHost.list(store).single().enabled, "ledger lists testmod enabled")
    check(ModuleHost.systemProps(store) == listOf("ro.testmod" to "1"), "system.prop collected")

    ModuleHost.setEnabled(root, store, "testmod", false, modes, log)
    check(File(root, "system/etc/hosts").readText() == "127.0.0.1 localhost\n", "disable restored the original hosts")
    check(!File(root, "system/etc/testmod.txt").exists(), "disable removed the new file")
    check(!File(root, "data/adb/modules/testmod").exists(), "disable removed the module tree")
    check(snap(root) == base, "disable returned the tree to the base snapshot")
    check(ModuleHost.systemProps(store).isEmpty(), "disabled module contributes no system.prop")

    ModuleHost.setEnabled(root, store, "testmod", true, modes, log)
    check(File(root, "system/etc/hosts").readText().contains("# testmod"), "re-enable placed the files again")

    ModuleHost.uninstall(root, store, "testmod", modes, log)
    check(snap(root) == base, "uninstall returned the tree to the base snapshot")
    check(ModuleHost.list(store).isEmpty(), "ledger empty after uninstall")
    check(!File(store, "modules/testmod").exists(), "module store dropped")

    // a placement that fails halfway (module file where the guest has a non-empty directory) rolls back
    File(root, "system/etc/blocker/inner.txt").apply { parentFile.mkdirs() }.writeText("inner")
    val bad = moduleZip(mapOf(
        "module.prop" to "id=badmod\nname=Bad\nversion=1\n",
        "system/etc/aaa-new.txt" to "placed before the failure\n",
        "system/etc/blocker" to "wants to be a file\n",
    ))
    val beforeBad = snap(root)
    check(runCatching { ModuleHost.install(root, store, bad, modes, log) }.isFailure, "conflicting module refused")
    check(snap(root) == beforeBad, "failed install rolled the tree back")
    check(ModuleHost.list(store).isEmpty(), "failed install left no ledger entry")
    check(!File(store, "modules/badmod").exists(), "failed install left no module store")

    println("ALL OK")
}
