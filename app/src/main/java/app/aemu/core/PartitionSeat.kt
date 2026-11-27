package app.aemu.core

import android.system.Os
import app.aemu.importer.BootImage
import java.io.File

/**
 * Partition seat, layer 1 (host side): present the guest's partitions the way a device
 * does, so recovery scripts and installers find the semantics they expect.
 *
 * The partitions' bodies stay what they always were — the directories of the guest tree,
 * shared by the system boot and the recovery boot. What was missing is the presentation:
 *  - the boot image as a real file (Importer.persistBootImage keeps it at <image>/boot.img)
 *    that a script can read and patch through /dev/block/bootdevice/by-name/boot;
 *  - by-name device nodes for the partitions (a recovery script unmounting /system died
 *    with ENOENT because nothing ever created them);
 *  - the supervisor's mount table switched on (<root>/dev/.aemu-vmount) and seeded with
 *    the partitions themselves (<root>/dev/.aemu-partitions, read by vm_init), so mount /
 *    umount / /proc/mounts agree about the partition roots in every boot mode.
 *
 * Layer 2 (the seat boot) also lives here: [overlayBootrd] lays the boot image's ramdisk
 * over the tree — the same merge the importer does, completed with the /init it used to
 * drop — so GuestVm can run that /init as the first guest process.
 *
 * Everything here is inert unless the image's bootSeat setting is on and the image kept
 * its boot image at import; with the setting off the host disarms what a previous boot
 * may have armed ([disarm]) and nothing changes for anyone.
 */
object PartitionSeat {

    /** Partitions presented to the guest, in fstab order. */
    val PARTITIONS = listOf("system", "data", "cache", "vendor")

    /** The by-name a partition is known by on a device (fstab and scripts spell data "userdata"). */
    private fun byName(part: String) = if (part == "data") "userdata" else part

    fun bootImage(paths: VmPaths): File = File(paths.dir, "boot.img")
    fun bootRamdiskDir(paths: VmPaths): File = File(paths.dir, "bootrd")

    /** The image kept its boot image at import and the ramdisk in it has an /init to run. */
    fun available(paths: VmPaths): Boolean =
        bootImage(paths).isFile && File(bootRamdiskDir(paths), "init").isFile

    /** The whole feature for this image: setting on, native engine, raw material present. */
    fun enabled(img: GuestImage, paths: VmPaths): Boolean =
        img.settings.bootSeat && img.engine == Engine.N64 && available(paths)

    /** Partitions whose body exists as a directory of this root. */
    private fun present(root: File): List<String> = PARTITIONS.filter { File(root, it).isDirectory }

    /** by-name references in a fstab text: (by-name directory, partition name), guest-absolute. */
    private val byNameRef = Regex("""(/dev/\S+?/by-name)/(\S+)""")

    /**
     * The fstab texts that spell this guest's by-name paths: the system's own fstab in the
     * tree and in the kept ramdisk, and the recovery's /etc/recovery.fstab. The parent
     * directory is never hardcoded — firmwares disagree (angler's TWRP looks under
     * /dev/block/platform/soc.0/<sdhci>/by-name, not bootdevice), so it is read from the
     * guest's own tables.
     */
    private fun fstabTexts(root: File, paths: VmPaths): List<String> {
        val files = mutableListOf<File>()
        fun fstabsOf(dir: File) {
            dir.listFiles()?.filter { it.isFile && it.name.startsWith("fstab.") }?.let { files += it }
        }
        fstabsOf(root)
        fstabsOf(paths.root)
        fstabsOf(bootRamdiskDir(paths))
        files += File(root, "etc/recovery.fstab")
        files += File(paths.dir, "recovery/etc/recovery.fstab")
        return files.distinctBy { it.path }.mapNotNull { runCatching { it.readText() }.getOrNull() }
    }

    /** Every by-name directory the guest's tables reference (guest-absolute), parsed ones first. */
    fun byNameDirs(root: File, paths: VmPaths): List<String> {
        val parsed = fstabTexts(root, paths)
            .flatMap { t -> byNameRef.findAll(t).map { it.groupValues[1] }.toList() }
            .distinct()
        return parsed + listOf("/dev/block/bootdevice/by-name").filter { it !in parsed }
    }

    /** The by-name parent the guest's own tables use — presentation elsewhere must agree with it. */
    fun byNameParent(root: File, paths: VmPaths): String = byNameDirs(root, paths).first()

    /** Partition names the guest's tables reference through by-name paths. */
    private fun fstabNames(root: File, paths: VmPaths): Set<String> =
        fstabTexts(root, paths)
            .flatMap { t -> byNameRef.findAll(t).map { it.groupValues[2] }.toList() }
            .toSet()

    /**
     * The by-name device nodes inside [root]: boot (and recovery, when a raw image
     * was kept) point at the real image files — a script dd-ing the boot partition patches
     * the very file the next seat boot unpacks — the rest at their partition directories.
     * The same links are planted under every by-name parent the guest's own fstab uses
     * and under the historical bootdevice parent, because consumers look under the one
     * their table spells. Names the tables reference that have a body in this root
     * (misc, metadata, …) are planted too; names without a body are not invented.
     */
    fun plantByName(root: File, paths: VmPaths, log: (String) -> Unit = {}) {
        runCatching {
            val bodies = linkedMapOf<String, File>()
            if (bootImage(paths).isFile) bodies["boot"] = bootImage(paths)
            File(paths.dir, "recovery.img").takeIf { it.isFile }?.let { bodies["recovery"] = it }
            for (p in present(root)) bodies[byName(p)] = File(root, p)
            for (n in fstabNames(root, paths)) {
                if (n !in bodies) File(root, n).takeIf { it.isDirectory }?.let { bodies[n] = it }
            }
            val dirs = byNameDirs(root, paths)
            for (d in dirs) {
                val dir = File(root, d.trimStart('/'))
                dir.mkdirs()
                for ((name, target) in bodies) {
                    val f = File(dir, name)
                    f.delete()
                    runCatching { Os.symlink(target.absolutePath, f.path) }
                }
            }
            log("seat: by-name devices planted in ${root.name} (${bodies.keys.joinToString()}) under ${dirs.joinToString()}")
        }.onFailure { log("seat: planting by-name devices failed: ${it.message}") }
    }

    /**
     * Switch the supervisor's mount table on for this root and seed it with the partitions
     * themselves. The stale table file is deleted first: mounts never outlive a boot.
     */
    fun arm(root: File, log: (String) -> Unit = {}) {
        runCatching {
            val dev = File(root, "dev").apply { mkdirs() }
            File(dev, ".aemu-mounts").delete()
            File(dev, ".aemu-partitions").writeText(present(root).joinToString("\n") { "/$it" } + "\n")
            File(dev, ".aemu-vmount").writeText("")
            log("seat: mount table armed (${present(root).joinToString()})")
        }.onFailure { log("seat: arming the mount table failed: ${it.message}") }
    }

    /** Setting off (or image without the raw material): leave nothing armed behind. */
    fun disarm(root: File) {
        runCatching {
            File(root, "dev/.aemu-vmount").delete()
            File(root, "dev/.aemu-partitions").delete()
        }
    }

    /**
     * Layer 2 dressing: re-unpack the CURRENT boot image's ramdisk (a recovery script may
     * have patched boot.img since the import) and lay it over the guest tree, completing
     * the merge the importer does with the entries it drops there — /init above all.
     * Partition directories of the ramdisk are its empty mount points; laying them over
     * the tree's full ones changes nothing (mkdirs on an existing directory).
     * Returns true when the tree's /init is in place afterwards.
     */
    fun overlayBootrd(paths: VmPaths, log: (String) -> Unit): Boolean {
        val img = bootImage(paths)
        if (!img.isFile) { log("seat: no boot.img kept for this image"); return false }
        val rd = runCatching { BootImage.ramdisk(img.readBytes()) }.getOrNull()
        if (rd.isNullOrEmpty()) { log("seat: the boot image's ramdisk is not recognized"); return false }
        // refresh bootrd/ from the current image first, so what is overlaid is what is in the file
        val out = bootRamdiskDir(paths)
        runCatching {
            ImageStore.wipe(out)
            out.mkdirs()
            writeEntries(out, rd)
        }.onFailure { log("seat: refreshing bootrd failed: ${it.message}"); return false }
        var laid = 0
        runCatching {
            writeEntries(paths.root, rd)
            laid = rd.size
        }.onFailure { log("seat: overlaying the ramdisk failed: ${it.message}"); return false }
        val ok = File(paths.root, "init").isFile
        log("seat: ramdisk overlaid ($laid entries), /init ${if (ok) "in place" else "MISSING"}")
        return ok
    }

    /** The cpio unpack shared by the importer's copy and the overlay: dirs, links, files. */
    private fun writeEntries(out: File, rd: List<BootImage.CpioEntry>) {
        val base = out.canonicalPath
        for (e in rd) {
            val n = e.name.trimStart('/')
            if (n.isEmpty() || n == ".") continue
            val f = File(out, n)
            if (!f.canonicalPath.startsWith(base)) continue
            when (e.mode and 0xF000) {
                0x4000 -> f.mkdirs()
                0xA000 -> {
                    f.parentFile?.mkdirs()
                    f.delete()
                    runCatching { Os.symlink(String(e.data), f.path) }
                }
                0x8000 -> {
                    f.parentFile?.mkdirs()
                    f.writeBytes(e.data)
                    if (e.mode and 0x49 != 0) f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
            }
        }
    }
}
