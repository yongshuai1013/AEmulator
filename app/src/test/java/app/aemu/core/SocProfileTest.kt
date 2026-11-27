package app.aemu.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SocProfileTest {
    /** a phone with an AArch64 cpuinfo, no Hardware line and its own serial */
    private class Phone(val cores: Int = 2) : HostSocSource {
        override fun cpuinfo(): String = (0 until cores).joinToString("") {
            "processor\t: $it\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x1\nCPU part\t: 0xd0b\nCPU revision\t: 2\n\n"
        } + "Serial\t\t: DEADBEEF\n"
        override fun soc0(name: String) = mapOf("machine" to "SM8150", "serial_number" to "999")[name]
        override fun prop(name: String) = mapOf("ro.board.platform" to "msmnile", "ro.soc.model" to "SM8150", "ro.soc.manufacturer" to "QTI")[name]
        override fun cpuFreqKhz(n: Int): Pair<Int, Int>? = null
        override fun cpuCount() = cores
    }

    private fun resolve(setting: String, tango: Boolean = false, guest32: Boolean = true, api: Int = 34, custom: SocSpec? = null) =
        SocProfiles.resolve(setting, custom, tango, guest32, api, Phone())

    @Test fun tangoDefaultsToYellowstoneAndOthersToTheHost() {
        assertEquals(SocProfiles.YELLOWSTONE, SocProfiles.effectiveId("", tango = true))
        assertEquals(SocProfiles.HOST, SocProfiles.effectiveId("", tango = false))
        assertEquals("sm8150", SocProfiles.effectiveId("sm8150", tango = true))
    }

    @Test fun yellowstoneIsNotAPreset() {
        assertFalse(SocProfiles.presets.any { it.id == SocProfiles.YELLOWSTONE })
        assertFalse(SocProfiles.valid(SocProfiles.YELLOWSTONE))
        assertEquals("", SocProfiles.clean("yellowstone"))
    }

    @Test fun theRequestedSnapdragonPresetsExist() {
        val wanted = listOf("QSD8250", "MSM8255", "MSM8260", "MSM8225", "MSM8210", "MSM8909", "MSM8226", "MSM8916", "MSM8917", "MSM8940", "SDM439", "SDM450",
            "MSM8952", "MSM8953", "SDM636", "SM6115", "SM6225", "SM6375", "SM6475-AB", "SM7125", "SM7150-AC", "SM7325", "SM7475-AB", "SM7635-AC", "SM4350-AC",
            "SM4635", "MSM8992", "MSM8994", "MSM8996", "MSM8998", "SDM845", "SM8150", "SM8250", "SM8350", "SM8450", "SM8550", "SM8650", "SM8735")
        val have = SocProfiles.presetsFor(SocIsa.ARM).map { it.model }
        for (m in wanted) assertTrue("missing $m", m in have)
    }

    @Test fun theX86PresetsAreExactlyTheRequestedOnes() {
        assertEquals(listOf("Z2460", "Z2580", "Z2760", "Z3480", "Z3530", "Z3560", "Z3590", "Z3735F", "Z3795", "x3-C3130", "x3-C3440", "x5-Z8300", "i5-10400F"),
            SocProfiles.presetsFor(SocIsa.X86).map { it.model })
        assertEquals("NVIDIA GeForce GTX 1650/PCIe/SSE2", SocProfiles.preset("i5_10400f_gtx1650")?.gpuRenderer)
    }

    @Test fun everyRowIsSelfConsistent() {
        assertEquals(SocProfiles.presets.size, SocProfiles.presets.map { it.id }.toSet().size)
        for (p in SocProfiles.presets) {
            assertTrue("${p.id} cores", p.cores in 1..SocProfiles.MAX_CORES)
            assertTrue("${p.id} gpu", p.gpuRenderer.isNotBlank() && p.gpuVendor.isNotBlank())
            assertTrue("${p.id} id is a valid setting", SocProfiles.valid(p.id))
            if (p.isa == SocIsa.X86) assertNotNull("${p.id} x86 block", p.x86)
            else assertNull("${p.id} is ARM", p.x86)
        }
    }

    @Test fun anArmImageNeverSeesX86ChipsAndAnX86ImageNeverSeesArmOnes() {
        assertTrue(SocProfiles.presetsFor(SocIsa.ARM).none { it.isa == SocIsa.X86 })
        assertTrue(SocProfiles.presetsFor(SocIsa.X86).none { it.isa == SocIsa.ARM })
        // a saved choice of the other family is ignored, not applied
        assertEquals(SocProfiles.HOST, SocProfiles.effectiveId("z3560", tango = false, isa = SocIsa.ARM))
        assertEquals(SocProfiles.HOST, SocProfiles.effectiveId("sm8150", tango = false, isa = SocIsa.X86))
        assertEquals("z3560", SocProfiles.effectiveId("z3560", tango = false, isa = SocIsa.X86))
        assertEquals(SocProfiles.HOST, SocProfiles.resolve("z3560", null, false, true, 30, Phone(), SocIsa.ARM).id)
    }

    @Test fun x86ChipFillsAnX86Cpuinfo() {
        val r = SocProfiles.resolve("z2580", null, false, true, 19, Phone(), SocIsa.X86)
        // 2 cores with hyper-threading: 4 logical cpus, two per core
        assertEquals(4, r.cpuCount)
        assertEquals(4, Regex("(?m)^processor").findAll(r.cpuinfo).count())
        assertTrue(r.cpuinfo.contains("model name\t: Intel(R) Atom(TM) CPU Z2580 @ 2.00GHz"))
        assertTrue(r.cpuinfo.contains("vendor_id\t: GenuineIntel"))
        assertEquals(listOf(0, 0, 1, 1), Regex("core id\t\t: (\\d)").findAll(r.cpuinfo).map { it.groupValues[1].toInt() }.toList())
        assertFalse(r.cpuinfo.contains("Hardware"))
        assertFalse(r.cpuinfo.contains("asimd"))
        // no soc0 of an ARM SoC, and no ro.board.platform: an x86 firmware finds its libraries by its own
        assertTrue(r.soc0.isEmpty())
        assertFalse(r.props.containsKey("ro.board.platform"))
        assertEquals("PowerVR SGX 544MP2", r.gpuRenderer)
        assertEquals("Imagination Technologies", r.gpuVendor)
    }

    @Test fun gpuStringsComeFromTheChipAndHostLeavesThemAlone() {
        val r = resolve("sm8150")
        assertEquals("Qualcomm", r.gpuVendor); assertEquals("Adreno (TM) 640", r.gpuRenderer)
        val h = resolve(SocProfiles.HOST)
        assertEquals("", h.gpuVendor); assertEquals("", h.gpuRenderer)
    }

    @Test fun snapdragon732gIsSm7150() {
        assertEquals("SM7150-AC", SocProfiles.preset("sm7150")?.model)
        assertEquals("Adreno (TM) 618", SocProfiles.preset("sm7150")?.gpuRenderer)
    }

    @Test fun tangoImageReportsYellowstoneInCpuinfoWithTheLinesSettingsReads() {
        val r = resolve("", tango = true, api = 19)
        assertTrue(r.cpuinfo.contains("Hardware\t: Yellowstone\n"))
        assertTrue(r.cpuinfo.contains("Revision\t: 0000\n"))
        assertTrue(r.cpuinfo.contains("Serial\t\t: 0000000000000000\n"))
        assertEquals(4, r.cpuCount)
        assertTrue(r.props.isEmpty())   // the firmware's own board properties stand
        assertEquals("Tegra K1", r.soc0["machine"])
    }

    @Test fun presetFillsEveryPlaceFromOneRecord() {
        val r = resolve("sm8150", api = 34)
        assertEquals("msmnile", r.props["ro.board.platform"])
        assertEquals("SM8150", r.props["ro.soc.model"])
        assertEquals("QTI", r.props["ro.soc.manufacturer"])
        assertEquals("SM8150", r.soc0["machine"])
        assertEquals("339", r.soc0["soc_id"])
        assertTrue(r.cpuinfo.contains("Hardware\t: Qualcomm Technologies, Inc SM8150"))
        assertEquals(8, r.cpuCount)
        assertEquals(8, Regex("(?m)^processor").findAll(r.cpuinfo).count())
        assertEquals(8, r.cpuFreqs.size)
        assertEquals(2_841_000, r.cpuFreqs.last().second)   // the prime core
    }

    @Test fun socPropertiesBelongToAndroid12AndLater() {
        val old = resolve("sm8150", api = 30)
        assertEquals("msmnile", old.props["ro.board.platform"])
        assertFalse(old.props.containsKey("ro.soc.model"))
        assertFalse(old.props.containsKey("ro.soc.manufacturer"))
        val new = resolve("sm8150", api = 31)
        assertEquals("SM8150", new.props["ro.soc.model"])
    }

    @Test fun layoutFollowsTheFirmwareBitness() {
        val a32 = resolve("sm8475", guest32 = true).cpuinfo
        assertTrue(a32.contains("model name\t: ARMv8 Processor"))
        assertTrue(a32.contains("neon"))
        assertFalse(a32.contains("asimd"))
        val a64 = resolve("sm8475", guest32 = false).cpuinfo
        assertFalse(a64.contains("model name"))
        assertTrue(a64.contains("asimd"))
        assertTrue(a64.contains("sve2"))
        // an ARMv7 chip is the ARMv7 layout either way
        assertTrue(resolve("msm8974", guest32 = false).cpuinfo.contains("model name\t: ARMv7 Processor rev 0 (v7l)"))
        assertTrue(resolve("sc6820", guest32 = false).cpuinfo.contains("processor\t: 0\n"))   // one core still lists its processor
    }

    @Test fun bigLittleCoresAreListedLittleFirst() {
        val parts = Regex("CPU part\t: (0x[0-9a-f]+)").findAll(resolve("sm8475").cpuinfo).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0xd46", "0xd46", "0xd46", "0xd46", "0xd47", "0xd47", "0xd47", "0xd48"), parts)
    }

    @Test fun hostSaysWhatThePhoneSaysAndKeepsItsSerialPrivate() {
        val r = resolve(SocProfiles.HOST, guest32 = true, api = 34)
        assertEquals(2, r.cpuCount)
        assertEquals("msmnile", r.props["ro.board.platform"])
        assertEquals("SM8150", r.soc0["machine"])
        assertFalse(r.soc0.containsKey("serial_number"))
        assertTrue(r.cpuinfo.contains("Hardware\t: SM8150"))
        assertFalse(r.cpuinfo.contains("DEADBEEF"))
        assertTrue(r.cpuinfo.contains("neon"))
        // a 64-bit firmware gets the phone's own layout
        assertTrue(resolve(SocProfiles.HOST, guest32 = false).cpuinfo.contains("asimd"))
    }

    @Test fun customIsSanitisedAndUsed() {
        val c = SocSpec(SocProfiles.CUSTOM, "x", manufacturer = "ACME", model = "A1", platform = "my chip/1", hardware = "ACME A1",
            arch = 7, clusters = listOf(SocCluster(3, 0x41, 0, 0xc09, 1, 1000)), features = "swp half", bogoMips = "1.00")
        val r = resolve("custom", custom = c)
        assertEquals("mychip1", r.props["ro.board.platform"])
        assertEquals(3, r.cpuCount)
        assertTrue(r.cpuinfo.contains("Features\t: swp half\n"))
        assertTrue(r.cpuinfo.contains("Hardware\t: ACME A1\n"))
    }

    @Test fun clusterTextRoundTripsAndRejectsBadLines() {
        val c = SocProfiles.parseClusters("4 0x41 0x0 0xd05 0 1800\n2, 0x51, 1, 0x805, 14, 2400, 500")!!
        assertEquals(listOf(4, 2), c.map { it.count })
        assertEquals(0xd05, c[0].part)
        assertEquals(500, c[1].minMhz)
        assertEquals(c, SocProfiles.parseClusters(SocProfiles.encodeClusters(c)))
        assertNull(SocProfiles.parseClusters("4 0x41 0x0"))
        assertNull(SocProfiles.parseClusters("40 0x41 0 0xd05 0 1800"))
        assertNull(SocProfiles.parseClusters(""))
    }

    @Test fun frequencyTableEndsAtTheMaximum() {
        val t = SocProfiles.freqSteps(300_000, 2_419_000)
        assertEquals(300_000, t.first()); assertEquals(2_419_000, t.last()); assertEquals(t.sorted(), t)
    }

    @Test fun tangoIsRecognisedByItsBuildProperties() {
        assertTrue(SocProfiles.isTango(mapOf("ro.product.device" to "yellowstone")))
        assertTrue(SocProfiles.isTango(mapOf("ro.hardware" to "Yellowstone")))
        assertFalse(SocProfiles.isTango(mapOf("ro.product.device" to "hammerhead")))
        assertFalse(SocProfiles.isTango(emptyMap()))
    }

    @Test fun settingSurvivesJson() {
        val c = SocProfiles.presets.first { it.id == "mt6878" }.copy(id = SocProfiles.CUSTOM, label = "Mine")
        val back = VmSettings.fromJson(VmSettings(soc = "custom", socCustom = c).toJson())
        assertEquals("custom", back.soc)
        assertEquals("MT6878", back.socCustom?.model)
        assertEquals(c.clusters, back.socCustom?.clusters)
        assertEquals("", VmSettings.fromJson(JSONObject().put("soc", "nonsense")).soc)
        assertEquals("", VmSettings().soc)
    }
}
