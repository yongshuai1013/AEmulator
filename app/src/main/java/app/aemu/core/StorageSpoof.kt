/* Added for AEmulator Plus, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/** The kind of flash chip the guest says its storage is. [id] is what VmSettings.storageType holds ("" = the guest's default). */
enum class StorageType(val id: String, val label: String, val ufs: Boolean, val bootDevice: String, val emmcRev: Int, val spec: String) {
    EMMC40("emmc40", "eMMC 4.0", false, "7824900.sdhci", 0, "4.0"),
    EMMC50("emmc50", "eMMC 5.0", false, "7824900.sdhci", 7, "5.0"),
    EMMC51("emmc51", "eMMC 5.1", false, "7824900.sdhci", 8, "5.1"),
    UFS("ufs", "UFS", true, "1d84000.ufshc", 0, "");

    companion object {
        fun parse(id: String): StorageType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * "Storage" setting (VmSettings.storageGb / storageType). 0 GB = off: the guest sees the phone's own disk, as before.
 *
 * The size the guest reports is the chosen one, but the free space is always the phone's real free space (capped at the
 * chosen size) and "used" is size - free. A 128 GB guest on a phone with 7 GB left reports 7 GB free / 121 GB used, so
 * it can never be told it may write more than the phone can hold. The numbers are taken at every statfs, not once.
 * The native side (run64.c / libaemushim.so) does that; this object only decides what is passed to it.
 */
object StorageSpoof {
    val PRESETS_GB = listOf(2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048)

    /** The file the native arm64 engine reads (<root>/dev/.aemu-storage): the size in bytes. */
    const val NODE = "dev/.aemu-storage"

    fun sanitizeGb(gb: Int): Int = if (gb in PRESETS_GB) gb else 0

    fun bytes(gb: Int): Long = sanitizeGb(gb).toLong() * 1024L * 1024L * 1024L

    fun text(gb: Int): String = if (gb >= 1024 && gb % 1024 == 0) "${gb / 1024} TB" else "$gb GB"

    /** Writes (or removes) the size file of the native engine. */
    fun writeNode(root: File, gb: Int) = runCatching {
        val f = File(root, NODE)
        if (sanitizeGb(gb) > 0) { f.parentFile?.mkdirs(); f.writeText(bytes(gb).toString()); f.setReadable(true, false) } else f.delete()
    }

    /** Properties that tell the guest which storage it has (the bootdevice name is what most "UFS or eMMC?" checks read). */
    fun props(type: StorageType?): Map<String, String> = if (type == null) emptyMap() else linkedMapOf(
        "ro.boot.bootdevice" to type.bootDevice,
        "ro.boot.storage_type" to if (type.ufs) "ufs" else "emmc",
    )

    /** The sysfs files of the disk the guest sees: path under the tree root -> text. */
    fun sysfs(type: StorageType?, gb: Int): Map<String, String> {
        if (type == null) return emptyMap()
        val bytes = if (gb > 0) bytes(gb) else 0L
        val sectors = (bytes / 512L).toString()
        val m = linkedMapOf<String, String>()
        if (type.ufs) {
            if (bytes > 0) m["sys/block/sda/size"] = sectors
            m["sys/block/sda/device/vendor"] = "SAMSUNG"
            m["sys/block/sda/device/model"] = "KLUDG4U1EA-B0C1"
            m["sys/block/sda/device/rev"] = "0100"
            m["sys/block/sda/device/type"] = "0"
        } else {
            val d = "sys/class/mmc_host/mmc0/mmc0:0001"
            if (bytes > 0) m["sys/block/mmcblk0/size"] = sectors
            m["sys/block/mmcblk0/device/type"] = "MMC"
            m["sys/block/mmcblk0/device/name"] = "BJNB4R"
            m["sys/block/mmcblk0/device/fwrev"] = "0x%016x".format(type.emmcRev.toLong() + 1)
            m["$d/type"] = "MMC"
            m["$d/name"] = "BJNB4R"
            m["$d/manfid"] = "0x000015"
            m["$d/oemid"] = "0x0100"
            m["$d/hwrev"] = "0x0"
            m["$d/fwrev"] = "0x%016x".format(type.emmcRev.toLong() + 1)
            m["$d/date"] = "01/2024"
            m["$d/ext_csd_rev"] = type.emmcRev.toString()
            m["$d/spec_version"] = type.spec
        }
        return m
    }

    /** Directories whose contents [sysfs] owns; a changed choice clears them first. */
    val OWNED_DIRS = listOf("sys/block/sda", "sys/block/mmcblk0", "sys/class/mmc_host/mmc0")
}
