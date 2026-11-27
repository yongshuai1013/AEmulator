package app.aemu.core

import app.aemu.importer.BootImage
import java.io.File

/**
 * The guest kernel's identity when the image does not carry one of its own.
 *
 * A firmware's kernel banner is pre-filled from its boot.img at import (VmSettings.kernelBanner) and the
 * user can edit it. When it is blank the old behaviour was to say nothing at all: no /proc/version file,
 * and the native runner left uname to the phone's own kernel — the guest then reported the host phone's
 * kernel string (and read the phone's /proc/version), two answers that contradict each other and the
 * firmware. [effectiveBanner] therefore falls back to a baseline banner of the image's Android era, so
 * /proc/version, uname and (on the qemu engines) the shim's answers always come from one source.
 */
object KernelBanner {

    /** Kernel release a stock device of this Android level typically shipped with (baseline, not a model claim). */
    fun releaseFor(api: Int): String = when {
        api < 11 -> "2.6.35.7"
        api < 14 -> "3.0.0"
        api < 16 -> "3.0.31"
        api < 19 -> "3.4.0"
        api < 21 -> "3.4.0"
        api < 23 -> "3.10.40"
        api < 24 -> "3.10.73"
        api < 26 -> "3.18.31"
        api < 28 -> "4.4.88"
        api < 29 -> "4.9.112"
        api < 30 -> "4.14.117"
        api < 31 -> "4.19.110"
        api < 33 -> "5.10.43"
        api < 34 -> "5.15.41"
        api < 35 -> "5.15.110"
        api < 36 -> "6.1.75"
        else -> "6.6.30"
    }

    /** A banner line of the era's shape; [BootImage.bannerRelease] / [BootImage.bannerVersion] parse it back. */
    fun baselineBanner(api: Int): String =
        "Linux version ${releaseFor(api)} (android-build@android) (gcc) #1 SMP PREEMPT"

    /** The banner in force for [img]: its own when set, the era baseline otherwise. Never blank. */
    fun effectiveBanner(img: GuestImage): String =
        img.settings.kernelBanner.ifBlank { baselineBanner(img.api) }
}

/**
 * The synthesized /proc files of the view layer (see the detection map): /proc/cmdline and /proc/cpuinfo,
 * derived from the image itself — never from the host. TreeFixer writes them into the image (the native
 * engine serves the image's own files first); on the qemu engines the same strings travel by environment
 * to libaemushim, which serves them in place of the phone's /proc (GuestRunner).
 */
object GuestProcInfo {

    /** The image's own properties (default.prop, then the build.props), first value wins. */
    fun imageProps(root: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (n in listOf("default.prop", "system/build.prop", "vendor/build.prop", "system/vendor/build.prop")) {
            val f = File(root, n)
            if (!f.isFile) continue
            runCatching {
                f.forEachLine { l ->
                    val i = l.indexOf('=')
                    if (i > 0 && !l.startsWith("#")) out.putIfAbsent(l.substring(0, i).trim(), l.substring(i + 1).trim())
                }
            }
        }
        return out
    }

    /**
     * /proc/cmdline: the androidboot.* parameters the property layer already presents, from the same
     * sources (ro.boot.hardware's value, the derived serial, the derived bootloader), so the file and
     * the properties cannot contradict each other.
     */
    fun cmdline(img: GuestImage, root: File): String {
        val s = img.settings
        val parts = ArrayList<String>()
        val hw = s.hardware.ifBlank { imageProps(root)["ro.hardware"].orEmpty() }
        parts += "androidboot.hardware=${hw.ifBlank { "unknown" }}"
        if (s.serialNo.isNotBlank()) parts += "androidboot.serialno=${s.serialNo}"
        if (s.bootloaderName.isNotEmpty()) parts += "androidboot.bootloader=${s.bootloaderName}"
        parts += "androidboot.mode=normal"
        return parts.joinToString(" ")
    }

    /**
     * The SoC preset in force for [img] (AEmulator Plus .17 port, see [SocProfiles]): the resolved chip
     * when the image's soc setting names one (a preset id, "host" or "custom" — the importer also
     * pre-fills it from the firmware's own platform), or null when the setting is blank.
     *
     * Priority of the identity sources, per surface: with a preset in force, /proc/cpuinfo, the
     * /sys/devices/soc0 files, the cpu/cpufreq nodes and the SoC properties (ro.board.platform,
     * ro.hardware.chipname, ro.soc.*) all come from the one resolved record, exactly as upstream.
     * With the setting blank, every one of those keeps this tree's previous answer — cpuinfo derived
     * from the image's own platform (never the host's), the host-count cpu nodes, no soc0 nodes, no
     * SoC properties. Upstream's blank-means-host default is deliberately NOT ported: the preset is
     * an additional, explicitly chosen source, never a silent replacement. The other identity cells
     * (model/build from build.prop, the kernel banner, the baseband and carrier settings) are not
     * SocSpec fields and are untouched either way.
     */
    fun socResolved(img: GuestImage, root: File): SocResolved? {
        val s = img.settings
        if (s.soc.isBlank()) return null
        return SocProfiles.resolve(
            s.soc, s.socCustom, SocProfiles.isTangoTree(root, img.model, img.name),
            guest32 = !img.abi.contains("64"), api = img.api, host = SocHost, isa = SocProfiles.isaOf(img.abi),
        )
    }

    /** /proc/cpuinfo in force: the chosen preset's text, else the image's own ([cpuinfo]). */
    fun effectiveCpuinfo(img: GuestImage, root: File): String =
        socResolved(img, root)?.cpuinfo ?: cpuinfo(img, root)

    /** The SoC platform the image names for itself (ro.board.platform of its build.prop). */
    fun platform(img: GuestImage, root: File): String {
        val p = imageProps(root)
        return (p["ro.board.platform"] ?: p["ro.product.board"] ?: p["ro.hardware"] ?: img.settings.hardware).trim()
    }

    /**
     * /proc/cpuinfo for the image's platform. Exact section shapes are table entries for the platforms
     * this project has images of (msm8994 first: the Nexus 6P test image); anything else gets the era's
     * generic section for its word size, with the Hardware line still naming the image's own platform.
     * A template reconstruction — the inputs (platform, word size) are the image's, the MIDR nibbles of
     * the generic entries are representative, not measured.
     */
    fun cpuinfo(img: GuestImage, root: File): String {
        val platform = platform(img, root)
        val key = platform.lowercase()
        val is64 = img.abi == "arm64-v8a"
        return if (is64) cpuinfo64(key, platform) else cpuinfo32(key, platform)
    }

    private data class Core64(val impl: String, val part: String, val variant: String, val revision: String)

    private fun section64(i: Int, c: Core64): String =
        "processor\t: $i\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: ${c.impl}\n" +
            "CPU architecture: 8\n" +
            "CPU variant\t: ${c.variant}\n" +
            "CPU part\t: ${c.part}\n" +
            "CPU revision\t: ${c.revision}\n"

    private fun cpuinfo64(key: String, platform: String): String {
        val a57 = Core64("0x41", "0xd02", "0x1", "0")
        val a53 = Core64("0x41", "0xd03", "0x0", "4")
        val cores: List<Core64>
        val hw: String
        when (key) {
            "msm8994" -> { cores = listOf(a57, a57, a57, a57, a53, a53, a53, a53); hw = "Qualcomm Technologies, Inc MSM8994" }
            "msm8992" -> { cores = listOf(a57, a57, a53, a53, a53, a53); hw = "Qualcomm Technologies, Inc MSM8992" }
            "msm8996" -> { val kryo = Core64("0x51", "0x211", "0x0", "0"); cores = listOf(kryo, kryo, kryo, kryo); hw = "Qualcomm Technologies, Inc MSM8996" }
            "msm8953" -> { cores = List(8) { a53 }; hw = "Qualcomm Technologies, Inc MSM8953" }
            else -> { cores = listOf(a53, a53, a53, a53); hw = platform.ifBlank { "AArch64" } }
        }
        val sb = StringBuilder()
        sb.append("Processor\t: AArch64 Processor rev 0 (aarch64)\n")
        cores.forEachIndexed { i, c -> sb.append(section64(i, c)).append('\n') }
        sb.append("Hardware\t: $hw\n")
        return sb.toString()
    }

    private fun cpuinfo32(key: String, platform: String): String {
        val hw = when (key) {
            "msm8974" -> "Qualcomm MSM 8974 (Flattened Device Tree)"
            else -> platform.ifBlank { "ARMv7" }
        }
        val impl = if (key == "msm8974") "0x51" else "0x41"
        val part = if (key == "msm8974") "0x06f" else "0xc0f"
        val sb = StringBuilder()
        sb.append("Processor\t: ARMv7 Processor rev 0 (v7l)\n")
        sb.append("BogoMIPS\t: 38.40\n")
        sb.append("Features\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt\n")
        sb.append("CPU implementer\t: $impl\n")
        sb.append("CPU architecture: 7\n")
        sb.append("CPU variant\t: 0x2\n")
        sb.append("CPU part\t: $part\n")
        sb.append("CPU revision\t: 0\n")
        sb.append("\nHardware\t: $hw\n")
        sb.append("Revision\t: 0000\n")
        sb.append("Serial\t\t: 0000000000000000\n")
        return sb.toString()
    }

    /**
     * The /dev/block/platform/.../by-name prefix the firmware itself uses, read out of its fstab / init
     * scripts — /proc/mounts then names the partitions the way the device does, not with our name in it.
     * Null when the firmware carries no such path (the caller falls back to a plain platform shape).
     */
    fun byNamePrefix(root: File): String? {
        val rx = Regex("""/dev/block/platform/\S+?/by-name/""")
        val counts = HashMap<String, Int>()
        val files = ArrayList<File>()
        root.listFiles()?.forEach { f -> if (f.isFile && (f.name.startsWith("fstab") || f.name.endsWith(".rc"))) files += f }
        for (d in listOf("system/etc", "vendor/etc")) File(root, d).listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("fstab") || f.name == "vold.fstab")) files += f
        }
        for (f in files) runCatching {
            rx.findAll(f.readText()).forEach { m -> counts[m.value] = (counts[m.value] ?: 0) + 1 }
        }
        return counts.maxByOrNull { it.value }?.key?.trimEnd('/')
    }
}
