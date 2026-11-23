package app.aemu.importer

/**
 * File names under which a firmware package carries the three partitions the importer consumes.
 * Motorola fastboot-XML packages (e.g. SPYDER / RAZR) name them `system_signed`, `boot_signed` and
 * `recovery_signed` with no extension at all; HTC S-ON ones use `boot_signed.img` / `recovery_signed.img`.
 * All of these are ordinary partition images (the signed ones only differ by a signature in front of or behind
 * the real data, which BootImage / MotoImage / the ext reader already cope with).
 * Per-partition dumps of A/B devices (Android Things on MT8516: `boot_a.bin`, `system_a.bin`, `oem_a.bin`,
 * `vendor_a.bin`) are accepted for the active slot "_a" only; the "_b" copies are the inactive slot and are ignored.
 *
 * Dumps that carry the partition name alone - `system`, `boot`, `vendor`, `product`, `oem` ... with no extension - are
 * accepted too, but only where the caller opts in with `bareOk` (see [bareOk]): a bare "vendor" or "recovery" is just as
 * often a folder, a symlink or a program (sbin/recovery), so such a name counts only for a sizeable file outside the
 * system-tree directories.
 */
internal object PartitionNames {
    private val SYSTEM = Regex("(?i)system(\\.ext4)?\\.img(\\.ext4)?|system\\.ext[234]|system_image\\.img|system\\.raw\\.img|system\\.rfs|factoryfs(\\.img|\\.rfs)?|system_signed(\\.img)?|system_a\\.(img|bin)")
    private val BOOT = Regex("(?i)boot(\\.img|_signed(\\.img)?)|boot_a\\.(img|bin)")
    private val OEM = Regex("(?i)oem(\\.ext4)?\\.img(\\.ext4)?|oem\\.ext[234]|oem_signed(\\.img)?|oem\\.rfs|oem_a\\.(img|bin)")
    private val VENDOR = Regex("(?i)vendor(\\.ext4)?\\.img|vendor\\.ext[234]|vendor_a\\.(img|bin)")
    /** More partitions that keep their name as the mount point (carrier / region packs, GKI module partitions). The guest asks for them by that very path. */
    private const val EXTRA_NAMES = "cust|preload|carrier|hw_product|hw_odm|hw_country|special_preload|system_dlkm|vendor_dlkm|odm_dlkm"
    /** Partitions besides system / vendor / oem: Treble and dynamic-partition ones, and the skins' own (Xiaomi mi_ext, OPPO my_*, vivo vgc, Samsung prism / optics, Alcatel/TCL custpack: system/build.prop is a symlink into it). */
    private val EXTRA = Regex("(?i)(product|system_ext|odm|custpack|mi_ext|vgc|prism|optics|my_[a-z0-9]+|$EXTRA_NAMES)(_[ab])?((\\.ext4|\\.erofs|\\.f2fs)?\\.img(\\.ext4)?|\\.ext[234])(\\.lz4)?")
    private val RECOVERY = Regex("(?i)recovery(\\.img|_signed(\\.img)?)(\\.lz4)?|recovery\\.(emmc|mmc)\\.win")

    /** The same partitions under their bare names (no extension at all); slot "_a" only, as above. */
    private val SYSTEM_BARE = Regex("(?i)system(_a)?")
    private val BOOT_BARE = Regex("(?i)boot(_a)?")
    private val OEM_BARE = Regex("(?i)oem(_a)?")
    private val VENDOR_BARE = Regex("(?i)vendor(_a)?")
    private val RECOVERY_BARE = Regex("(?i)recovery(_a)?")
    private val EXTRA_BARE = Regex("(?i)(product|system_ext|odm|custpack|mi_ext|vgc|prism|optics|my_[a-z0-9]+|$EXTRA_NAMES)(_[ab])?")

    /** A bare-named entry smaller than this is not a partition image (a stub, a symlink's text, a script). */
    private const val MIN_BARE_SIZE = 64L * 1024
    /** Directories of an installed system tree: a program called "recovery" or "vendor" in there is no image. */
    private val SYSTEM_TREE_DIRS = setOf("bin", "sbin", "xbin", "lib", "lib64", "etc", "app", "priv-app", "framework", "usr")

    /**
     * Whether an archive entry may be taken for a partition image on its bare name alone. [path] is the entry's path
     * inside the archive, [size] its size (pass [Long.MAX_VALUE], or any negative value, when the container does not tell it).
     */
    fun bareOk(path: String, size: Long): Boolean {
        if (size in 0 until MIN_BARE_SIZE) return false // a negative size is "unknown" (streamed zip entry): the path decides
        val dirs = path.replace('\\', '/').split('/').dropLast(1)
        return dirs.none { it.lowercase() in SYSTEM_TREE_DIRS }
    }

    /**
     * Raw partition dumps of Spreadtrum / Unisoc tools number the images in partition-table order
     * ("26.system.img", "28.vendor.img", "25.boot.img", "2.recovery.img"); the number is no part of the name.
     * (Without this the vendor image matched nothing, was never read, and the guest had none of the firmware's own
     * /vendor HAL services and passthrough impls.)
     */
    private val NUMBERED = Regex("^[0-9]{1,3}[._-](?=[A-Za-z])")
    private fun n(base: String) = NUMBERED.replaceFirst(base, "")
    /** [n] without a whole-file compression suffix: "system.img.xz", "vendor.img.zst", "product.img.br" name the same partitions as the plain image. */
    private fun d(base: String) = ImageCodecs.stripSuffix(n(base))

    /** Image names that always mean the system partition (RFS dumps call it factoryfs). */
    fun isSystem(base: String, bareOk: Boolean = false) = d(base).let { SYSTEM.matches(it) || bareOk && SYSTEM_BARE.matches(it) }
    /** The optional /oem partition (never boot-critical). A bare "oem" is a folder or a fastboot command, not an image. */
    fun isOem(base: String, bareOk: Boolean = false) = d(base).let { OEM.matches(it) || bareOk && OEM_BARE.matches(it) }
    /** The optional /vendor partition (A/B and Treble devices). Only a real image name counts, never a bare "vendor" folder. */
    fun isVendor(base: String, bareOk: Boolean = false) = d(base).let { VENDOR.matches(it) || bareOk && VENDOR_BARE.matches(it) }
    /**
     * A dynamic-partitions container ("super.img", or a dump called just "super" / "super_a"). Its logical partitions are cut out by
     * [Importer] once the file is read, by content (LP metadata), so only the name decides whether an archive entry is picked up at all.
     * A bare name counts only where the caller opts in ([bareOk]), like the other partitions: a folder or a stub called "super" is no image.
     */
    fun isSuper(base: String, bareOk: Boolean = false) = n(base).let { SUPER.matches(it) || bareOk && SUPER_BARE.matches(it) }
    private val SUPER = Regex("(?i)super(_a)?\\.(img|bin)")
    private val SUPER_BARE = Regex("(?i)super(_a)?")
    fun isBoot(base: String, bareOk: Boolean = false) = n(base).let { BOOT.matches(it) || bareOk && BOOT_BARE.matches(it) }

    /**
     * Where an image of an optional partition is read to, or null if [base] is not one: product and system_ext live
     * under system/ (the layout the guest and Analyzer expect), the others in a root directory of their own name.
     * Only the active slot counts: "_b" copies are ignored.
     */
    fun extraMount(base: String, bareOk: Boolean = false): String? {
        val name = d(base)
        val m = EXTRA.matchEntire(name) ?: (if (bareOk) EXTRA_BARE.matchEntire(name) else null) ?: return null
        if (m.groupValues[2].equals("_b", true)) return null
        val p = m.groupValues[1].lowercase()
        return if (p == "product" || p == "system_ext") "system/$p" else p
    }
    private val LOGICAL = Regex("[a-z][a-z0-9_]{0,47}")
    /** Names that are no partition of the guest's root: kernel / runtime directories, the boot-side partitions, the ones read elsewhere. */
    private val RESERVED = setOf(
        "system", "vendor", "boot", "data", "userdata", "cache", "dev", "proc", "sys", "mnt", "storage", "sdcard", "apex", "tmp", "etc", "bin", "sbin",
        "lib", "lib64", "init", "root", "acct", "config", "d", "metadata", "misc", "recovery", "super", "vendor_boot", "init_boot", "vbmeta", "dtbo",
    )

    /**
     * Mount point of a logical partition of a super image (a filesystem by definition), by its name without the slot suffix: the name itself
     * (cust, my_stock, system_dlkm, ...), product / system_ext under system/. Null for a name the guest root cannot hold.
     * Every logical partition the image has is taken as it is given; only the names above are refused.
     */
    fun logicalMount(base: String): String? {
        val n = base.lowercase()
        if (!LOGICAL.matches(n) || n in RESERVED) return null
        return if (n == "product" || n == "system_ext") "system/$n" else n
    }

    /** recovery image (optionally lz4-framed, optionally signed) or a TWRP raw recovery.*.win backup. */
    fun isRecovery(base: String, bareOk: Boolean = false) = n(base).let { RECOVERY.matches(it) || bareOk && RECOVERY_BARE.matches(it) }

    /**
     * Older Samsung Odin packages (e.g. GT-S5303 .tar.md5) name every tar member "<image>.md5" - "system.img.md5",
     * "boot.img.md5", "recovery.img.md5" - with the payload being the plain image. Strip that suffix so the
     * partition matchers above see the real name. Nested "*.tar.md5" archives keep their suffix.
     */
    fun stripOdinMd5(base: String): String =
        if (base.endsWith(".md5", true) && !base.endsWith(".tar.md5", true) && base.length > 4) base.dropLast(4) else base

    /**
     * A zip of a raw /system partition (the contents of system.img, not a "system/" folder inside it):
     * build.prop and a bin/ directory sit at the zip root. Everything in it is then placed under system/.
     */
    fun isRawSystemDump(names: Collection<String>): Boolean {
        var buildProp = false
        var bin = false
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            if (n.startsWith("__MACOSX/")) continue
            if (n == "build.prop") buildProp = true
            else if (n == "bin" || n.startsWith("bin/")) bin = true
            if (buildProp && bin) return true
        }
        return false
    }

    /**
     * Names a whole-disk image (GPT at LBA 1) can have inside an archive. A Chrome OS style recovery zip ships the disk as
     * "<board>_recovery_<channel>.bin", not "*.img". Only the name is checked here: the caller still looks for the "EFI PART" signature.
     */
    fun isDiskImageName(base: String): Boolean {
        val b = base.replace('\\', '/').substringAfterLast('/').lowercase()
        if (!b.contains('.')) return true
        return b.substringAfterLast('.') in setOf("img", "bin", "raw", "disk", "dd")
    }
}
