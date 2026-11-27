/* Added for AEmulator Plus, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md. Samsung Exynos rows added 2026-11-21; Spreadtrum / Unisoc and NVIDIA Tegra rows added 2026-11-22; Google Tensor, more HiSilicon / Samsung and Xiaomi Surge rows added 2026-11-25. */
package app.aemu.core

/*
 * THE PRESET TABLE. Nothing but data: every chip the "Preset" menu offers is one row here, and nothing else needs to
 * change to add, remove or correct one. SocProfile.kt turns a row into /proc/cpuinfo, /sys/devices/soc0, the properties
 * and (with the phone's GPU on) the GL_VENDOR / GL_RENDERER the guest reads.
 *
 * ARM rows are made with qc(...) / mt(...) / ex(...) / tg(...) / sp(...), x86 rows with atom(...) / desktop(...). Column order is written out in
 * each helper's signature at the bottom of the file.
 *
 * Where the numbers come from
 *  - soc_id: the kernel's drivers/soc/qcom/socinfo.c table. A chip that table does not list (or that predates it) has "" and
 *    the guest gets no soc_id node, rather than a made-up one.
 *  - cores, clocks, GPU, process node: the vendor's product pages / briefs.
 *  - ro.board.platform: the board platform name devices with that chip report. "" = the firmware's own value stays.
 *  - Snapdragon 4s Gen 2 and 7s Gen 4: Qualcomm does not name the GPU; the Adreno numbers are the ones the phone vendors' spec
 *    sheets give (611, 810).
 *  - revision / serial_number are not specifications (every phone has its own); they are fixed, plausible values.
 *  - Snapdragon 732G is SM7150-AC (not SM7250-AC, which is the 768G).
 *
 * Added 2026-11-20 (second pass)
 *  - Checked against real phones' /proc/cpuinfo, soc0 and build.prop (github.com/pytorch/cpuinfo test/mock dumps of a Pixel 2 XL,
 *    Galaxy S7 / S8 / S9, Nexus 5X / 6, Moto G, Redmi Note 3 / 4, Oppo A37 / R9 / R15, Meizu Pro 6 / 7+ ...). Corrected by them:
 *    MSM8998 big cores are part 0x800 (variant 0xa, little 0x801 rev 4); MSM8996's little cores are 0x211 and its big ones 0x205 (they were
 *    swapped); SDM845 is variant 7 rev 12 / variant 6 rev 13; MSM8992 revisions are 3 (A53) and 2 (A57); MSM8916 has no crypto extensions
 *    (Features "fp asimd evtstrm crc32"); SM7150 (730 / 732G) is "sm6150" on a Pixel 4a, not "sdmmagpie".
 *  - SM8735's platform was "sun" (the 8 Elite's); no source gives its own name, so it is "" (the firmware's own stays).
 *  - Platform names: SM4450 / SM6450 / SM6475 "parrot", SM7550 "crow", SM8635 "cliffs", SM7635 / SM6650 "volcano", SM8850 "canoe" come from kernel
 *    patches, OnePlus kernel build configs and phones' own reports; SM7675 "pineapple" is the OnePlus kernel build's name for it (least certain).
 *    Rows with "" platform or "" soc_id are chips for which no source was found; they report nothing rather than a guess.
 *  - Values that are not in any source (Kryo variant / revision of chips without a real dump, kgsl clock steps) follow the verified chips
 *    of the same core generation; the kgsl steps keep the shape of the Adreno 630's. GPU top clocks and foundries come from the Wikipedia list.

 * Added 2026-11-20 (third pass: MediaTek)
 *  - The chip NAMES come from en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips and nothing else does. Every other column comes from
 *    other places:
 *      cores / clocks / GPU / node: MediaTek's own product pages and launch material, checked this pass for Helio G85 (mediatek.com), Helio X30
 *      (mediatek.jp page: A73 2.6 GHz; the launch press release said 2.5), Helio X20 (TechInfoDepot), Helio P23 / P30 (AnandTech, GSMArena,
 *      Android Authority: 2.3 / 1.65 GHz, Mali-G71 MP2 770 / 950 MHz, 16 nm), Helio P20 / P10 (Android Authority, Fudzilla), Dimensity 9400
 *      (3.62 / 3.3 / 2.4 GHz), Dimensity 8400 (A725 x8, 3.25 / 3.0 / 2.1 GHz, Mali-G720 MC7), Dimensity 9300 / 9300+ and 9200 (Android Authority,
 *      91mobiles). The remaining Dimensity / Helio G rows are MediaTek's launch figures from memory and were NOT re-checked against a page
 *      this pass (Dimensity 8300's middle cluster is the one conflict: most sources say 3.2 GHz, one says 3.0).
 *      MT6582 / MT6592 / MT6735 / MT6761 / MT6765 clocks are likewise unchecked.
 *    Left out on purpose: Dimensity 9500 / 7500 (Arm C1 cores: the MIDR part numbers are not in the kernel's cputype.h yet), Dimensity 9600 and the
 *    other 2026 launches (no non-Wikipedia source yet), Genio / Pentonic / automotive / Wi-Fi / modem / feature-phone parts (not phone SoCs).
 *  - CPU part numbers: Linux arch/arm64/include/asm/cputype.h (A720 0xd81, X4 0xd82, X925 0xd85, A725 0xd87, A520 0xd80, A715 0xd4d, X3 0xd4e).
 *  - Features / revision: real dumps (pytorch/cpuinfo test/mock): Helio P10 (Oppo R9, Alcatel Revvl: A53 rev 2, crypto extensions present,
 *    Hardware "MT6755"), Helio X30 (Meizu Pro 7 Plus: A73 part 0xd09 rev 2), Helio P60 (Oppo R15: A73 rev 2, crypto present). The A53-only
 *    entry chips (MT6735, A22, P35) follow the existing MT6739 / MT6762 rows (no crypto extensions): unverified. Revisions and variants of every
 *    other core follow the verified chips of the same core generation, as in the Qualcomm rows.
 *  - ro.board.platform is "mt" + the model number, which is what MediaTek device trees use (TARGET_BOARD_PLATFORM := mt6765 ...). Exception: Helio
 *    G85 (MT6769Z) is "mt6768" (the Redmi 9 tree is named mt6768, /e/OS lists the Redmi Note 9 as "MT6768 Helio G85"); the least certain name here.
 *  - Fourth pass: Helio G35 / G36 / G37 (2.3 / 2.2 / 2.3 GHz + 1.8 GHz, PowerVR GE8320 680 MHz, 12 nm), G70 / G80 / G88 (2x A75 2.0 GHz + 6x A55 1.7 /
 *    1.8 / 1.8 GHz, Mali-G52 MC2) and P25 (2.6 / 1.6 GHz, Mali-T880 MP2) checked against mediatek.com pages and Notebookcheck / 91mobiles. The
 *    Dimensity 6020 / 6080 / 7020 / 7050 rows are renames of earlier rows (same silicon). Dimensity 1050, 1100, 8000 and Helio G100 are from memory.
 *    Dimensity 1100's platform name "mt6891" is the "mt" + model rule, not seen in a device tree.
 *  - soc_id is the model digits, as in the existing MediaTek rows (MediaTek has no socinfo table). Serials come from fakeSerial(id).
 *  - GL strings: Mali / Immortalis rows use ARM's names. Helio X30 (PowerVR 7XT GT7400 Plus) and Dimensity 930 / 7020 (IMG BXM-8-256) are the GPU
 *    names as given by the maintainer, written in the "PowerVR ..." form the existing GE8xxx rows use; the exact driver strings are unverified.
 *
 * Added 2026-11-21 (fifth pass: Samsung Exynos; the menu's first six rows of that family, to be extended)
 *  - Verified against real dumps (pytorch/cpuinfo test/mock): Exynos 3110 = Nexus S (cpuinfo incl. BogoMIPS 994.65 / Revision 0022 / Hardware
 *    "herring", ro.board.platform s5pc110, GLES 2.0, cpufreq 100-1000 MHz); Exynos 7870 = Galaxy J7 Prime (32-bit view of a 3.18 kernel: no
 *    BogoMIPS, no model name, no Hardware; ro.board.platform "exynos5", ro.hardware samsungexynos7870, universal7870, ro.chipname / ro.arch,
 *    GLES 3.0, cpufreq 546-1586 MHz, A53 r0p4). Layouts also checked against kernel sources and the S7 / S8 / S9 / A8 2018 dumps.
 *  - Exynos 4412 = Galaxy S III GT-I9300: CPU variant 3 / part 0xc09 / rev 0 (Geekbench), Features per supercurio's report, Hardware "SMDK4x12"
 *    (several sources), ro.board.platform "exynos4" / ro.product.board "smdk4x12" / GLES 2.0 (GT-N8010 build.prop, also an Exynos 4412).
 *    NOT from a dump: BogoMIPS, Revision "000c", ro.hardware "smdk4x12".
 *  - Exynos 850 (SM-A127F, kernel 4.19.111): platform "exynos850", 8x A55 r0p1, 546-2002 MHz, Mali-G52 MP1 (deviceinfohw.ru). Inferred, not
 *    seen: ro.hardware, ro.soc.model "exynos850", GLES 3.2, BogoMIPS 52.00 (every measured Exynos 4.4+ dump has 52.00).
 *  - Exynos 2200 (SM-S901B, kernel 5.10): platform and ro.soc.model "s5e9925", ro.soc.manufacturer Samsung (pytorch/cpuinfo issue 114), X2 / A710
 *    r2p0 + A510 r0p2, 400-2803 MHz, GL via ANGLE on Vulkan (deviceinfohw.ru). Exynos 2400 (SM-S921B, kernel 6.1): platform "s5e9945", 10 cores
 *    1xX4 3207 + 2xA720 2900 + 3xA720 2590 + 4xA520 1960 MHz, all r0p1. For both, the Features line is the REAL printout of the closest ARMv9 phone
 *    dump (Pixel 8 for the 2200, Pixel 10 for the 2400), not an Exynos dump; ro.hardware / ro.boot.hardware / GLES level are inferred.
 *  - No soc0 node is reported for these rows: the Exynos 3110 / 4412 / 7870 dumps have none and no source was found for the others. A row can
 *    set family / machine / socId / revision (Samsung soc0 layout: "Samsung Exynos", "Samsung ... board based on EXYNOS8895", "EXYNOS8895", "17").
 *  - Sixth pass (3470, 4212, 5260, 5420, 7420, 7570, 8890, 9611, 9810). Dumps: Exynos 7420 = Galaxy S6 (3.10 layout incl. "Hardware SAMSUNG Exynos7420",
 *    modalias, 400-1500 / 800-2100 MHz, props), 8890 = Galaxy S7 (3.18: M1 part 0x001 r1, A53 r4, 442-1586 / 728-2600 MHz, soc0 with machine "Samsung
 *    UNIVERSAL8890 board based on EXYNOS8890", revision 32), 9810 = Galaxy S9 (4.9: A55 r1 + M3 var 1 part 0x002, 455-1794 / 650-2704 MHz, MIDR nodes,
 *    Features up to asimdhp, ro.hardware.egl mali). Exynos 9611 = SM-A507FN (kernel 4.14.113, A73 r0p2 + A53 r0p4, 403-2314 MHz, platform exynos9611,
 *    deviceinfohw.ru). 4412 / 4212 platform "exynos4", board smdk4x12, GLES 2.0 = GT-N8010 build.prop; 5260 / 5420 board universal5260 / universal5420,
 *    platform exynos5 = chainfire CF-Auto-Root list and LineageOS trees. NOT from a dump: the 3470's platform "exynos3" and everything of the 7570 (platform
 *    "exynos5", min clock, Features with crypto, T720 level, GL string; only cores / clock / node are from spec sheets), the A15 / A7 revisions and
 *    Revision "000a" of 5260 / 5420 (copied from the Exynos 5422 dump), the A57 r1p1 of the 7420, the 4212's and 3470's revisions and BogoMIPS
 *    (A7 about 2 per MHz as the 5422 dump, A9 about 1 per MHz as the Galaxy Nexus), ro.hardware of 3470 / 5260 / 5420 (pattern of the 5422 dump),
 *    the 9611's cpuid flag and ro.hardware, and the GL_RENDERER strings of the 7420 / 9810 / 9611.
 *  - Samsung kernels never print a Hardware line on arm64; the layout differences are the CpuinfoStyle of the row (see SocProfile.kt).
 * Added 2026-11-21 (seventh pass: early Snapdragon S1 / S2 / S3 / S4 Plus)
 *  - MSM7227, MSM7627, QSD8650, MSM7230, MSM7630, APQ8055, MSM8655, MSM8255T, MSM8655T, APQ8060, MSM7225A, MSM7625A, MSM7227A, MSM7627A, MSM7225AB,
 *    MSM8227, MSM8627. (MSM8660 and MSM8625 were already rows.) Cores, top clock, GPU and node: the Wikipedia list (S1 / S2 / S3 / S4 Plus tables).
 *    The list gives MSM7225AB no clock of its own (it sits in the MSM7227A group, 1 GHz); phones with it often run 800 MHz.
 *  - soc_id: only APQ8060 (86), MSM8627 (120) and MSM8227 (121) are in Linux dt-bindings/arm/qcom,ids.h. The 7x27 / 7x27A / 7x30 / 8x55 / QSD parts
 *    are not, so they have "" (no soc_id node), as QSD8250 / MSM8255 already do.
 *  - MSM7227 / MSM7627 are ARM11 (ARMv6): arch = 6, which SocProfile.kt prints as "ARMv6-compatible processor rev 5 (v6l)", "CPU architecture: 6TEJ".
 *    Values are those of a real MSM7227 dump (HTC myTouch 3G Slide, postmarketOS issue 826): implementer 0x41, variant 1, part 0xb36, rev 5,
 *    Features "swp half thumb fastmult vfp edsp java", BogoMIPS 245.61 (boot at 245 MHz).
 *  - All the new rows use the ARM_K30 cpuinfo layout (their kernels are 2.6.35 / 3.0 / 3.4: "Processor" header, one shared Features block). The earlier
 *    S1 / S2 / S3 / S4 rows still use the generic layout.
 *  - NOT from a dump: the Cortex-A5 rows copy the MSM8225 row (variant 0, rev 1, A5_V7), the Krait rows the MSM8930 row (part 0x04d, variant 1, rev 0),
 *    the Scorpion rows the MSM8255 / MSM8260 rows (0x00f, variant 1, rev 2). BogoMIPS 245.61 of every 245 MHz-minimum chip is copied from the MSM7227
 *    dump, 13.53 of the Krait rows from the MSM8960 row and of APQ8060 by analogy. Hardware "QCT MSM8227 MTP" / "QCT MSM8627 MTP" follow the MSM8930 rows.
 *  - ro.board.platform: msm7x27 (7227 / 7627), msm7x27a (the A family, as the existing MSM8225 row; device trees often say msm7627a), msm7x30 (7230 / 7630 / 8x55
 *    / APQ8055), qsd8k, msm8660 (APQ8060), msm8960 (8227 / 8627, as the MSM8930 rows).
 * Added 2026-11-22 (eighth pass: Spreadtrum / Unisoc and NVIDIA Tegra)
 *  - Tegra rows come from real dumps. Tegra 2 (T20): Galaxy Tab 10.1 /proc/cpuinfo (gist tetsu-koba/992391: Cortex-A9 r1p0, BogoMIPS 1998.84,
 *    "swp half thumb fastmult vfp edsp vfpv3 vfpv3d16", Hardware "p3"); the board name here is the NVIDIA tablet reference "ventana" (not seen in a dump).
 *    Tegra 3 (T30L): Nexus 7 2012 on 3.1.10 (Linux Magazin listing2: A9 r2p9, BogoMIPS 1993.93, "swp ... neon vfpv3 tls", Hardware "grouper").
 *    Tegra 4 (T114): Tegra Note 7 (openbenchmarking: A15 r2p2, "... vfpv4 idiva idivt", Hardware "tegratab"). Tegra K1 Denver (T132): pytorch/cpuinfo
 *    nexus9 (impl 0x4e part 0x000, BogoMIPS 24.00, no Hardware line, "MTS version", ro.board.platform tegra132, cpuidle denver_idle, scaling_driver tegra,
 *    2499 / 204 MHz). Tegra X1 (T210): pytorch/cpuinfo pixel-c (A57 r1p1, no BogoMIPS and no Hardware line, cpufreq-tegra210, 1912.5 / 51 MHz).
 *    Tegra 2 / 3 / 4 kernels (2.6.36 - 3.4) print the ARM_K30 SMP layout and register no /sys/devices/soc0; from 3.10 (K1, X1) soc0 has family "Tegra",
 *    revision "A0x" and soc_id = the chip id in decimal (mainline fuse-tegra.c; T132 = 19, T210 = 33). The A0x revision names are plausible, not dumped.
 *    GL strings: "NVIDIA Tegra 3" and "NVIDIA Tegra" are from Chromium's gpu_info_collector tests; "NVIDIA Tegra 4" and the Tegra 2 string follow them.
 *    ro.board.platform: tegra132 is from the Nexus 9; "tegra3" (Nexus 7) and plain "tegra" for the others are from memory of the AOSP device trees.
 *    The Tegra K1 32-bit chip (T124) has no row: the "yellowstone" entry already stands for it (note: its soc_id "53" is Tegra 4's chip id, K1's is 64).
 *  - Spreadtrum / Unisoc: SC7730S / SC7727SE dumps (Galaxy Tab 3 Lite, Grand Prime VE, J1 2016 in pytorch/cpuinfo) give the 3.10 Cortex-A7 r0p5 layout
 *    (Hardware "sc8830", Features without lpae / evtstrm, 1300 / 768 MHz, 50000-100000 ns cpufreq latency); an Alldocube T701 (SC9832E, Android 9, kernel 4.4.147,
 *    newandroidbook.com/ddb/Alldocube) gives the arm64 layout (no "Processor" header, no Serial, BogoMIPS 52.00, "Hardware : Unisoc SC9832e") and the
 *    property pattern (ro.board.platform sp9832e, ro.hardware = ro.boot.hardware = sp9832e_1h10). The "Hardware" strings of the newer chips, the device-tree
 *    models / compatibles and the cpufreq tables come from the downstream device trees of the Galaxy A03 kernel (arch/arm64/boot/dts/sprd):
 *    cpuinfo_hardware "UNISOC SC9863A", "Unisoc ums9230" (T606 / T612 / T616), "Unisoc ums9620". SC9863A: 4 x A55 768 - 1200 MHz + 4 x A55 768 - 1600 MHz;
 *    T606: 6 x A55 614 - 1612 MHz (cpu0-5) + 2 x A75 768 - 1612 MHz (cpu6-7). Clock / core data of the other rows: Unisoc's pages and launch material.
 *  - Tiger T7520: Unisoc announced it in Feb 2020 and never published clocks; it shipped as the UMS9620 family (T760 / T770 / T820). The row is UMS9620:
 *    device tree has 4 x A55 (cpu0-3), 3 x A76 (cpu4-6), 1 x A76 (cpu7); clocks 2.2 GHz A76 / 2.0 GHz A55 and Mali-G57 MC4 are from the UMS9620 datasheet
 *    quoted in the OpenWrt forum thread on the ZTE F50. The minimum clocks of that row are guesses.
 *  - T606 is also sold as "T7200", SC6820 / SC7731E rows follow the vendor pages (Cortex-A5 1 GHz Mali-400; Cortex-A7 1.3 GHz Mali-T820).
 *  - NOT from a dump (marked "unverified" in the rows): SC6820's Hardware line / Features / BogoMIPS (an A5 on a 2.6.3x kernel, by analogy with Tegra 2's
 *    kernel), SC7731E's Hardware line, every Unisoc ro.hardware / ro.board.platform of a chip other than SC9832E (by the SC9832E pattern), and the MIDR
 *    variant / revision of the A55 / A75 / A76 cores (the same r0p0 the Qualcomm rows use for these cores).
 * Added 2026-11-23 (ninth pass: HiSilicon Kirin 980, Xiaomi XRING O1, JLQ JR510, TI OMAP4430 / OMAP3630). Each row is marked VERIFIED / UNVERIFIED below.
 *  - Kirin 980 (VERIFIED, a complete real dump): Huawei Mate 20 HMA-L29, EMUI 9 / Android 9, kernel 4.9 (pytorch/cpuinfo test/mock huawei-mate-20, in
 *    android.googlesource.com/platform/external/cpuinfo). cpu0-3 A55 (MIDR 0x411fd050: impl 0x41, variant 1, part 0xd05, rev 0) 830-1805 MHz; cpu4-5 and
 *    cpu6-7 are A76 with HiSilicon's implementer 0x48 and part 0xd40 (MIDR 0x481fd400: variant 1, rev 0), 826-1901 and 1460-2600 MHz. Features stop at asimdhp
 *    (hwcap 0x7FF), BogoMIPS 3.84 on every cpu, no Hardware line, no Processor header. sysfs: cpuidle current_driver "hisi_middle_cluster_idle", cpufreq-dt,
 *    governors "interactive conservative ondemand userspace powersave performance schedutil", latency 2000000 ns, physical_package_id 0 / 1 / 2 per cluster.
 *    Properties: ro.board.platform = ro.hardware = ro.boot.hardware kirin980, ro.board.chiptype kirin980_cs, ro.opengles.version 196610; no ro.hardware.chipname.
 *    GL_RENDERER "Mali-G76" is the OpenCL device name Geekbench reads on a Mate 20 Pro (no "MP10" suffix). revidr_el1 of the real phone is 0xf (A55) / 0x1 (A76);
 *    the generic MIDR code writes 0, which is NOT the dump's value. No soc0 node is known for HiSilicon kernels, so none is reported.
 *  - XRING O1 (PARTLY VERIFIED, no full cpuinfo dump exists in public): Geekbench 6 on a Xiaomi 25042PN24C (15S Pro): clusters 2 @ 1.80, 2 @ 1.89, 4 @ 3.40,
 *    2 @ 3.90 GHz, Android 15, Motherboard (Build.BOARD) "O1_asic", governor "xres", identifier "implementer 65 variant 0 part 3461 (0xd85 = X925) revision 1".
 *    The Features line is the REAL printout of a Cortex-X925 + A725 kernel 6.x system (NVIDIA GB10 / DGX Spark, identical to the Pixel 10 line already used here).
 *    X925 r0p1 is verified; A725 r0p1 is from the same GB10 dump (not XRING); A520 r0p1 (part 0xd80), every minimum clock, BogoMIPS 38.40 (19.2 MHz timer
 *    assumed) and ro.board.platform ("" = the firmware's own stays) are NOT from any source. Xiaomi's dijun-v-oss kernel / device tree was not readable.
 *  - JLQ JR510 (VERIFIED against a real dump, corrected 2026-11-23): late-PVT Poco C40 (POCO/frost_global/frost:11, test-keys camera_mtbf build), its
 *    /proc/cpuinfo, getprop and SurfaceFlinger. 8 x Cortex-A55 r2p0 (impl 0x41, variant 2, part 0xd05, rev 0), BogoMIPS 38.40, Features "fp asimd evtstrm crc32 atomics
 *    fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp" (NO aes / pmull / sha1 / sha2), no "Processor" header, "Hardware : JLQ JR510" as the last line.
 *    ro.board.platform = jr510, ro.hardware = ro.boot.hardware = jlq, ro.hardware.egl mali, ro.hardware.vulkan pastel, ro.opengles.version 196610;
 *    no ro.soc.* and no ro.hardware.chipname (Android 11). GL: GL_VENDOR "ARM", GL_RENDERER "Mali-G52" (GLES 3.2 v1.r32p0). Left out on purpose: ro.product.board
 *    ("frost" is the phone's, not the chip's), ro.boot.cpuid and every build / device property. Clocks 1.50 / 2.00 GHz are Geekbench's (not in the dump); the
 *    500 MHz minimum clocks, the kernel version (K49 layout assumed) and sysfs (cpuidle / cpufreq / soc0) are NOT from the dump.
 *  - TI OMAP4430 (VERIFIED on the Pandaboard, a real OMAP4430): Cortex-A9 r1p2 (variant 1, part 0xc09, rev 2), two cpus, "swp half thumb fastmult vfp edsp thumbee
 *    neon vfpv3 tls", Hardware "OMAP4 Panda board", Revision 0020, Serial all zeros, BogoMIPS 599.22 (Funtoo wiki dump, 300 MHz boot clock). Phones with this
 *    chip (Droid RAZR, Kindle Fire) name their own board; production silicon may be r2p10 (the OMAP4460 Galaxy Nexus dump is variant 2 rev 10). ro.board.platform
 *    "omap4" and GLES 2.0 are the AOSP Panda / Tuna trees from memory, not fetched.
 *  - TI OMAP3630 (UNVERIFIED: no OMAP3630 dump could be retrieved): Cortex-A8 r1p3 (variant 1, part 0xc08, rev 3) as TI documents for the 3630 / DM3730, Features copied from
 *    the Nexus S A8 line without its trailing words, BogoMIPS from the Nexus S A8 @ 1 GHz dump (994.65), Hardware "OMAP3 Beagle Board" (BeagleBoard-xM naming, from memory).
 * Added 2026-11-24 (tenth pass: 8 MediaTek + 4 Samsung Exynos rows). Every row is marked in its own comment: what came out of a real dump, and what did NOT.
 *  - Dumps used: github.com/pytorch/cpuinfo test/mock: nexus10 (Exynos 5250), galaxy-j7-uae (Exynos 7580), galaxy-a8-2018 (Exynos 7885), galaxy-s8-global (Exynos 8895),
 *    meizu-pro-6 / meizu-pro-6s (Helio X25): cores, MIDR fields, Features, BogoMIPS, Hardware, Revision, cpufreq min / max, soc0, ro.board.platform, ro.product.board,
 *    ro.chipname, ro.arch, ro.opengles.version, ro.hardware.egl, ro.mediatek.platform.
 *  - Exynos 5422 (Galaxy S5 dump), MT6752 (Xperia C4), MT6595 (Vibe X2) and MT6735P (Lenovo A6600 Plus) were read too and left out: their real layouts / properties (S5: a
 *    different BogoMIPS per cluster and "Revision 000a" in a no-header layout; MT6752: 3.10 arm64 with BogoMIPS lines but one shared Features block; MT6595: ro.mediatek.version.sdk 2
 *    and wlan chip MT6630; MT6735P: platform "mt6735m") cannot be printed by SocProfile.kt as it is.
 *  - MediaTek rows without a dump (P65, P95, G25, 800U, 820, 1000L, 1000+): cores / clocks / GPU / node from MediaTek's launch figures; platform by the "mt" + model rule
 *    (NOT seen in a device tree); MIDR variant / revision from the same-generation rows of this table. P95 and G25 are the P90's and P22's silicon, 800U the 720's with faster A76s.
 *  - Known gaps against the real devices (in SocProfile.kt, not in these rows): ro.opengles.version of every MediaTek Mali row is 196610 (the Pro 6s reports 196608); the
 *    4.4 MediaTek kernel prints a "model name" line per core that the generic renderer leaves out on aarch64.
 * Added 2026-11-25 (eleventh pass: Google Tensor G1 - G5, HiSilicon Kirin 910T / 620 / 650 / 659 / 710 / 810 / 950 / 960 / 970 / 990 / 9000, Xiaomi Surge S1,
 * Samsung Exynos 9820 / 1280 / 2100). Marks: VERIFIED = printed by a real device or a real device's build.prop; DERIVED = a real dump of a sibling chip with
 * the numbers that differ replaced (clocks, core counts, names); UNVERIFIED = no dump, from datasheets or by analogy. Nothing here is a made-up number
 * without a mark. Sources: pytorch/cpuinfo test/mock dumps (huawei-mate-8 / -9 / -10, p20-pro, p9-lite, p8-lite, ascend-p7, xiaomi-mi-5c, pixel-8), newandroidbook.com
 * dumps (Galaxy S10 beyond1 = Exynos 9820, Galaxy A53 = Exynos 1280), Geekbench 6 browser "Identifier" lines of real Pixel 6 / 9 / 10 Pro Fold, the real Pixel
 * build.props of Oct 2026 (Android 17: oriole, panther, shiba, tokay, frankel) and a rooted Pixel 10 Pro Fold's FDT / cpuinfo / sysfs capture (omamobile #1044).
 *  - Google Tensor, ro.soc.* (VERIFIED, real build.props): manufacturer "Google" on all five; ro.soc.model is "Tensor" (Pixel 6), "GS201" (Pixel 7), "Tensor G3",
 *    "Tensor G4", "Tensor G5". The inconsistent naming is Google's own. No ro.hardware.chipname and no soc0 are printed (Google is in NO_CHIPNAME / NO_SOC0): the
 *    build.props carry no chipname and the downstream soc0 of a Pixel is not documented anywhere reachable (mainline prints family "Samsung Exynos" / soc_id GS101).
 *  - Tensor G3 (VERIFIED, pytorch/cpuinfo pixel-8): 4 x A510 r1p1 (cpu0-3, 324-1704 MHz), 4 x A715 r1p0 (cpu4-7, 402-2367), 1 x X3 r1p0 (cpu8, the
 *    last core, 500-2914); BogoMIPS 49.15; scaling_driver exynos_cpufreq, governors "sched_pixel conservative powersave performance schedutil", latency 5000000,
 *    cpuidle psci_idle. Features = V9_PIXEL8 (that dump).
 *  - Tensor G4: X4 r0p1 (VERIFIED, Geekbench identifier "variant 0 part 3458 revision 1" of a Pixel 9), clusters 4 @ 1.95 / 3 @ 2.60 / 1 @ 3.10 GHz (VERIFIED, same
 *    Geekbench pages; Geekbench also lists "sve i8mm"). The A520 / A720 revisions are the Exynos 2400's (r0p1, same Samsung process), the minimum clocks, BogoMIPS
 *    49.15 (Samsung's 24.576 MHz timer, as the G3 dump) and the Features (V9_PIXEL8; kernel 6.1 may add ecv / afp) are UNVERIFIED.
 *  - Tensor G5 (VERIFIED, rooted Pixel 10 Pro Fold, kernel 6.6): 2 x A520 (0xd80), 5 x A725 (0xd87), 1 x X4 (0xd82), BogoMIPS 76.80, ro.boot.hardware.platform laguna,
 *    "mte" absent from Features (arm64.nomte), GPU "PowerVR D-Series DXT-48-1536", GLES 3.2. X4 variant 0 rev 1 from Geekbench; clocks 2.25 / 3.05 / 3.78 GHz from
 *    Geekbench and Google. UNVERIFIED: A520 / A725 revision (r0p1 as the XRING O1 / Pixel 10 dump), minimum clocks, the cpufreq driver (left blank), the DT model
 *    "FRANKEL MP based on LGA" (the Fold prints "RANGO MP based on LGA"; compatible google,lga-frankel / google,lga is from the mainline binding).
 *  - Tensor G1 / G2 (kernel 5.10): X1 r1p0 on a Pixel 6 (VERIFIED, Geekbench identifier), clocks 1.80 / 2.25 / 2.80 GHz (VERIFIED, Geekbench 6 of a Pixel 6, Android 16,
 *    governor sched_pixel). UNVERIFIED: A55 r2p0 / A76 r4p0 / G2's A78 r1p1 (the revisions the Exynos 1280 and 9820 dumps print for the same cores), every minimum
 *    clock, BogoMIPS 49.15, the cpufreq driver (blank), G2's 1.80 / 2.35 / 2.85 GHz (Google's figures). Features = A64_V82 (what the A55 + A76 / X1 intersection
 *    prints on every SD 888 / Exynos 2100-class dump). ro.board.platform gs101 / gs201 / zuma / zumapro / laguna are the platform names of AOSP's hardware/google/graphics.
 *  - Kirin 910T (VERIFIED, Ascend P7 dump): 4 x A9 r4p1, BogoMIPS 3577.00, hi6620oem, k3v2cpufreq, governor pwrctrl_hotplug only, physical_package_id -1, 208-1795 MHz.
 *  - Kirin 620 / 650 (VERIFIED, P8 Lite / P9 Lite dumps): the HiSilicon 3.10 layout (A64_K310H), "CPU architecture: AArch64", Hardware hi6210sft / hi6250.
 *    620: 8 x A53 r0p3 208-1200 MHz, hi6xxx-smp, governors "optdemand performance"; 650: A53 r0p4, 480-1709 and 1402-2016 MHz, arm-big-little.
 *  - Kirin 950 / 960 (VERIFIED, Mate 8 / Mate 9): GENERIC layout with the "Processor" header, BogoMIPS 3.84, no Hardware. The header's revision is that of the cpu that
 *    reads the file on a real phone (A72 r0p0 / A73 r0p1 there); the engine prints the first cluster's, an unavoidable difference. ro.board.platform hi3650 / hi3660 /
 *    hi3670 are from memory (UNVERIFIED: the dump build.props are generic).
 *  - Kirin 970 (VERIFIED, Mate 10 / P20 Pro): K44 layout, 4 x A53 r0p4 509-1844 + 4 x A73 r0p2 682-2362 MHz, cpufreq-dt, latency 2000000, hisi_little_cluster_idle.
 *  - Xiaomi Surge S1 (VERIFIED, Mi 5C): 4 x A53 r0p4 at 461-1404 and 1843-2158 MHz, BogoMIPS 52.16, K44, song-cpufreq, platform song, GLES 3.2.
 *  - DERIVED rows: Kirin 659 (= the 650 dump's hi6250 platform with 2.36 / 1.70 GHz, K44 because it shipped on EMUI 8), Kirin 710 (970's A73 r0p2 + A53), Kirin 810 /
 *    990 / 9000 (980's HiSilicon A76 impl 0x48 part 0xd40; the A77 of the 9000 is 0xd41), Exynos 2100 (Pixel 6's X1 + Exynos 1280's A78 / A55). Features of the
 *    810 / 990 / 9000 are A64_V82 (their kernel 4.14 prints cpuid / asimdrdm / lrcpc / dcpop / asimddp, the 980's 4.9 did not).
 *  - Exynos 9820 (VERIFIED, S10 dump): 4 x A55 (variant 1 rev 0), 2 x A75 (variant 2 rev 1), 2 x M4 (impl 0x53, part 0x003, variant 1), BogoMIPS 52.00, kernel 4.14 Features.
 *    Exynos 1280 (VERIFIED, Galaxy A53, kernel 5.10): 6 x A55 variant 2 rev 0 + 2 x A78 variant 1 rev 1.
 *  - NOT added (a real dump exists but this renderer cannot print it right): Kirin 920 (one shared block with "0x0 & 0x3" variants and two BogoMIPS), Leadcore LC1860
 *    and MT8167B (a "Processor" line inside every core block, 3.10 / 3.18 MediaTek / Leadcore), Spreadtrum SC9853I (x86).
 * Added 2026-11-27 (twelfth pass: 20 rows, 11 of them VERIFIED against real dumps, 9 DERIVED / PARTLY VERIFIED; every row carries its own mark). New renderer pieces in SocProfile.kt:
 *  per-cluster BogoMIPS (SocCluster.bogoMips) and the Revision line (hwRevision) in the generic ARMv7 layout; CpuinfoStyle A64_K310M / A64_K318M / A64_K318P (the MediaTek layouts the tenth pass
 *  had to leave out); MediaTek WLAN chip / platform / SDK level / GLES / EGL properties that a row may override (MT6595, MT6750, MT8167B differ from the generic pattern); Marvell in NO_SOC0 / NO_CHIPNAME.
 *  - VERIFIED (pytorch/cpuinfo test/mock): Exynos 5422 (galaxy-s5-global), Exynos 7578 (galaxy-a3-2016-eu), SC7730SE / SC7727SE / SC7730S (galaxy-grand-prime-value-edition / galaxy-j1-2016 /
 *    galaxy-tab-3-lite), MT6595 (lenovo-vibe-x2), MT6752 (xperia-c4-dual), MT6750 (alcatel-revvl), MT8167B (iconia-one-10), Marvell PXA986 (galaxy-tab-3-7.0), Snapdragon 600 APQ8064T (galaxy-s4-us).
 *    Several dumps had only the boot cpu online (hotplug): the other cpus' fields come from the chip's spec and are marked in the row.
 *  - Exynos 990: CPU-Z validation page of a Galaxy S20 (kernel 4.19.87, Features, BogoMIPS, A76 CPUID r3p1), Geekbench identifier of the M5 cores, Build.HARDWARE "exynos990" from a public scan of an SM-G980F.
 *  - Exynos 9825: the 9820 dump + Samsung's clocks; ro.board.platform / board / androidboot.hardware from the Note 10 TWRP tree (ianmacd/twrp_d2s).
 *  - Exynos 7880 / 1380 / 1330, MT6753, MT6732, Snapdragon 870 / 888+: DERIVED from the nearest dumped sibling, clocks and GPUs from the vendors' figures; the platform / soc_id values that no source showed are
 *    either blank (soc_id of SM8250-AC / SM8350-AC: the kernel's id list has no entry) or marked UNVERIFIED in the row.
 *  - Known limits: Exynos 5422's package ids (A7 cluster 1, A15 cluster 0) and the dumps' cpu0-only BogoMIPS differences cannot be printed; GL_RENDERER of the Spreadtrum / Marvell rows is left blank on purpose.
 *  - Not added although a dump exists: Leadcore LC1860 (five cpus, per-block "Processor" line in a layout seen once), Actions ATM7029B (no CPU part / revision lines), Allwinner "sun8i" (platform
 *    property contradicts the board), Kirin 920, Atom Z2520 / Z2560 (the x86 renderer lacks the 32-bit kernel's fdiv_bug / f00f_bug / coma_bug lines and prints a different flag set).
 */
object SocPresetTable {
    private const val QTI = "QTI"

    // ---- ARM -------------------------------------------------------------------------------------------------------
    // the order here is the order of the menu: by brand (Snapdragon, MediaTek, Exynos, then NVIDIA Tegra and Spreadtrum / Unisoc from vendorRows()), oldest first
    private fun armRows(): List<SocSpec> = listOf(
        // ==== Snapdragon (Qualcomm), oldest first
        //  id          label                                    model       platform    soc_id arch  clusters (count, impl, variant, part, rev, maxMHz, minMHz)  gpu  node
        qc("msm7227", "Snapdragon S1 MSM7227", "MSM7227", "msm7x27", "", 6, ARM11_V6, listOf(c(1, 0x41, 1, 0xb36, 5, 800, 245)), "Adreno (TM) 200", "65 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7627", "Snapdragon S1 MSM7627", "MSM7627", "msm7x27", "", 6, ARM11_V6, listOf(c(1, 0x41, 1, 0xb36, 5, 800, 245)), "Adreno (TM) 200", "65 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("qsd8250", "Snapdragon S1 QSD8250",          "QSD8250", "qsd8k",   "",    7, SCORPION,   listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)),                                  "Adreno (TM) 200", "65 nm"),
        qc("qsd8650", "Snapdragon S1 QSD8650", "QSD8650", "qsd8k", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)), "Adreno (TM) 200", "65 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7230", "Snapdragon S2 MSM7230", "MSM7230", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 800, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7630", "Snapdragon S2 MSM7630", "MSM7630", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 800, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8255", "Snapdragon S2 MSM8255",          "MSM8255", "msm7x30", "",    7, SCORPION,   listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)),                                  "Adreno (TM) 205", "45 nm"),
        qc("msm8655", "Snapdragon S2 MSM8655", "MSM8655", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 1000, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8260", "Snapdragon S3 MSM8260",          "MSM8260", "msm8660", "70",    7, SCORPION,   listOf(c(2, 0x51, 1, 0x00f, 2, 1200, 384)),                                  "Adreno (TM) 220", "45 nm"),
        qc("msm8660", "Snapdragon S3 MSM8660", "MSM8660", "msm8660", "71", 7, SCORPION, listOf(c(2, 81, 1, 0x00f, 2, 1500, 384)), "Adreno (TM) 220", "45 nm"),
        qc("apq8055", "Snapdragon S2 APQ8055", "APQ8055", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 1400, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8255t", "Snapdragon S2 MSM8255T", "MSM8255T", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 1500, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8655t", "Snapdragon S2 MSM8655T", "MSM8655T", "msm7x30", "", 7, SCORPION, listOf(c(1, 0x51, 1, 0x00f, 2, 1500, 245)), "Adreno (TM) 205", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("apq8060", "Snapdragon S3 APQ8060", "APQ8060", "msm8660", "86", 7, SCORPION, listOf(c(2, 0x51, 1, 0x00f, 2, 1700, 384)), "Adreno (TM) 220", "45 nm", bogo = "13.53", style = CpuinfoStyle.ARM_K30),
        qc("msm7225a", "Snapdragon S1 MSM7225A", "MSM7225A", "msm7x27a", "", 7, A5_V7, listOf(c(1, 0x41, 0, 0xc05, 1, 800, 245)), "Adreno (TM) 200", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7625a", "Snapdragon S1 MSM7625A", "MSM7625A", "msm7x27a", "", 7, A5_V7, listOf(c(1, 0x41, 0, 0xc05, 1, 800, 245)), "Adreno (TM) 200", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7227a", "Snapdragon S1 MSM7227A", "MSM7227A", "msm7x27a", "", 7, A5_V7, listOf(c(1, 0x41, 0, 0xc05, 1, 1000, 245)), "Adreno (TM) 200", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm7627a", "Snapdragon S1 MSM7627A", "MSM7627A", "msm7x27a", "", 7, A5_V7, listOf(c(1, 0x41, 0, 0xc05, 1, 1000, 245)), "Adreno (TM) 200", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8960", "Snapdragon S4 Plus MSM8960",     "MSM8960", "msm8960", "87",  7, KRAIT,      listOf(c(2, 0x51, 1, 0x04d, 0, 1512, 384)),                                  "Adreno (TM) 225", "28 nm", hw = "QCT MSM8960 MTP", rev = "1.0", serial = "3156203849", bogo = "13.53"),
        qc("msm8225", "Snapdragon S4 Play MSM8225",     "MSM8225", "msm7x27a","127",    7, A5_V7,      listOf(c(2, 0x41, 0, 0xc05, 1, 1200, 245)),                                  "Adreno (TM) 203", "45 nm"),
        qc("msm8625", "Snapdragon S4 Play MSM8625", "MSM8625", "msm7x27a", "129", 7, A5_V7, listOf(c(2, 65, 0, 0xc05, 1, 1200, 245)), "Adreno (TM) 203", "45 nm"),
        qc("msm8930", "Snapdragon S4 Plus MSM8930", "MSM8930", "msm8960", "116", 7, KRAIT, listOf(c(2, 81, 1, 0x04d, 0, 1200, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("msm8960ab", "Snapdragon S4 Pro MSM8960AB",     "MSM8960AB", "msm8960", "138",  7, KRAIT,      listOf(c(2, 0x51, 1, 0x06f, 0, 1700, 384)),                                  "Adreno (TM) 320", "28 nm"),
        qc("msm7225ab", "Snapdragon S1 MSM7225AB", "MSM7225AB", "msm7x27a", "", 7, A5_V7, listOf(c(1, 0x41, 0, 0xc05, 1, 1000, 245)), "Adreno (TM) 200", "45 nm", bogo = "245.61", style = CpuinfoStyle.ARM_K30),
        qc("msm8227", "Snapdragon S4 Plus MSM8227", "MSM8227", "msm8960", "121", 7, KRAIT, listOf(c(2, 0x51, 1, 0x04d, 0, 1000, 384)), "Adreno (TM) 305", "28 nm", bogo = "13.53", hw = "QCT MSM8227 MTP", style = CpuinfoStyle.ARM_K30),
        qc("msm8627", "Snapdragon S4 Plus MSM8627", "MSM8627", "msm8960", "120", 7, KRAIT, listOf(c(2, 0x51, 1, 0x04d, 0, 1000, 384)), "Adreno (TM) 305", "28 nm", bogo = "13.53", hw = "QCT MSM8627 MTP", style = CpuinfoStyle.ARM_K30),
        qc("apq8064", "Snapdragon 600 APQ8064", "APQ8064", "msm8960", "109", 7, KRAIT, listOf(c(4, 81, 1, 0x06f, 2, 1500, 384)), "Adreno (TM) 320", "28 nm", hw = "QCT APQ8064 MTP", bogo = "13.53"),
        qc("msm8225q", "Snapdragon 200 MSM8225Q",     "MSM8225Q", "msm7x27a","168",    7, A5_V7,      listOf(c(4, 0x41, 0, 0xc05, 1, 1400, 245)),                                  "Adreno (TM) 203", "45 nm"),
        qc("msm8625q", "Snapdragon 200 MSM8625Q", "MSM8625Q", "msm7x27a", "169", 7, A5_V7, listOf(c(4, 65, 0, 0xc05, 1, 1400, 245)), "Adreno (TM) 203", "45 nm"),
        qc("apq8026", "Snapdragon 400 APQ8026", "APQ8026", "msm8226", "199", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm APQ 8026 (Flattened Device Tree)"),
        qc("msm8210", "Snapdragon 200 MSM8210",         "MSM8210", "msm8610", "162", 7, A7_V7,      listOf(c(2, 0x41, 0, 0xc07, 5, 1200, 300)),                                  "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8210 (Flattened Device Tree)"),
        qc("msm8226", "Snapdragon 400 MSM8226",         "MSM8226", "msm8226", "158", 7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1200, 300)),                                  "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8226 (Flattened Device Tree)"),
        qc("msm8610", "Snapdragon 200 MSM8610", "MSM8610", "msm8610", "147", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8610 (Flattened Device Tree)"),
        qc("msm8626", "Snapdragon 400 MSM8626", "MSM8626", "msm8226", "145", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8626 (Flattened Device Tree)"),
        qc("msm8212", "Snapdragon 200 MSM8212", "MSM8212", "msm8610", "164", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8212 (Flattened Device Tree)"),
        qc("msm8612", "Snapdragon 200 MSM8612", "MSM8612", "msm8610", "165", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 302", "28 nm", hw = "Qualcomm MSM 8612 (Flattened Device Tree)"),
        qc("msm8974", "Snapdragon 800 MSM8974",         "MSM8974", "msm8974", "126", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2265, 300)),                                  "Adreno (TM) 330", "28 nm", hw = "Qualcomm MSM 8974 (Flattened Device Tree)", rev = "2.1", serial = "2917450318", bogo = "38.40"),
        qc("msm8930aa", "Snapdragon 400 MSM8930AA", "MSM8930AA", "msm8960", "142", 7, KRAIT, listOf(c(2, 81, 1, 0x06f, 2, 1400, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("msm8926", "Snapdragon 400 MSM8926", "MSM8926", "msm8226", "200", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1200, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8926 (Flattened Device Tree)"),
        qc("apq8064ab", "Snapdragon 600 APQ8064AB", "APQ8064AB", "msm8960", "153", 7, KRAIT, listOf(c(4, 81, 1, 0x06f, 2, 1900, 384)), "Adreno (TM) 320", "28 nm", hw = "QCT APQ8064 MTP", bogo = "13.53"),
        // Snapdragon 600 APQ8064T = Galaxy S4 SGH-I337 (VERIFIED, real dump pytorch/cpuinfo galaxy-s4-us, kernel 3.4): shared-Features layout, Krait 300 variant 1 part 0x06f rev 0,
        // implementer 0x51, BogoMIPS 13.53 on 4 cpus, Hardware "SAMSUNG JF", Revision 000a, 384-1890 MHz (msm cpufreq, msm_idle), ro.board.platform msm8960, ro.chipname apq8064,
        // GLES 3.0. NOT from the dump: soc_id (APQ8064T is not in the kernel's id list: left blank), GL_RENDERER, ro.product.board is "MSM8960" there (the engine writes the platform).
        qc("apq8064t", "Snapdragon 600 APQ8064T", "APQ8064T", "msm8960", "", 7, A5_V7, listOf(c(4, 0x51, 1, 0x06f, 0, 1890, 384)), "Adreno (TM) 320", "28 nm",
           hw = "SAMSUNG JF", bogo = "13.53", style = CpuinfoStyle.ARM_K30, hwRev = "000a", props = mapOf("ro.chipname" to "apq8064")),
        qc("msm8930ab", "Snapdragon 400 MSM8930AB", "MSM8930AB", "msm8960", "154", 7, KRAIT, listOf(c(2, 81, 1, 0x06f, 2, 1700, 384)), "Adreno (TM) 305", "28 nm", hw = "QCT MSM8930 MTP"),
        qc("msm8974pro_ab", "Snapdragon 801 MSM8974PRO-AB",         "MSM8974PRO-AB", "msm8974", "218", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2360, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("msm8974pro_aa", "Snapdragon 801 MSM8974PRO-AA",         "MSM8974PRO-AA", "msm8974", "217", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2265, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("apq8028", "Snapdragon 400 APQ8028", "APQ8028", "msm8226", "219", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm APQ 8028 (Flattened Device Tree)"),
        qc("msm8228", "Snapdragon 400 MSM8228", "MSM8228", "msm8226", "221", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8228 (Flattened Device Tree)"),
        qc("msm8628", "Snapdragon 400 MSM8628", "MSM8628", "msm8226", "223", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8628 (Flattened Device Tree)"),
        qc("msm8928", "Snapdragon 400 MSM8928", "MSM8928", "msm8226", "224", 7, A7_V7, listOf(c(4, 65, 0, 0xc07, 5, 1600, 300)), "Adreno (TM) 305", "28 nm", hw = "Qualcomm MSM 8928 (Flattened Device Tree)"),
        qc("msm8974pro_ac", "Snapdragon 801 MSM8974PRO-AC",         "MSM8974PRO-AC", "msm8974", "194", 7, KRAIT,      listOf(c(4, 0x51, 2, 0x06f, 0, 2450, 300)),                                  "Adreno (TM) 330", "28 nm"),
        qc("apq8084", "Snapdragon 805 APQ8084", "APQ8084", "apq8084", "178", 7, KRAIT, listOf(c(4, 81, 3, 0x06f, 1, 2700, 300)), "Adreno (TM) 420", "28 nm", hw = "Qualcomm APQ 8084 (Flattened Device Tree)", rev = "1.1"),
        qc("msm8916", "Snapdragon 410 MSM8916",         "MSM8916", "msm8916", "206", 8, A64_LITE,   listOf(c(4, 0x41, 0, 0xd03, 0, 1200, 200)),                                  "Adreno (TM) 306", "28 nm"),
        qc("apq8016", "Snapdragon 410 APQ8016", "APQ8016", "msm8916", "247", 8, A64_LITE, listOf(c(4, 65, 0, 0xd03, 0, 1200, 200)), "Adreno (TM) 306", "28 nm"),
        qc("msm8936", "Snapdragon 610 MSM8936", "MSM8936", "msm8916", "233", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1700, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8939", "Snapdragon 615 MSM8939", "MSM8939", "msm8916", "239", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1100, 200), c(4, 65, 0, 0xd03, 4, 1700, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8992", "Snapdragon 808 MSM8992",         "MSM8992", "msm8992", "251", 8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 3, 1440, 384), c(2, 0x41, 1, 0xd07, 2, 1824, 384)),  "Adreno (TM) 418", "20 nm"),
        qc("msm8994", "Snapdragon 810 MSM8994",         "MSM8994", "msm8994", "207", 8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 2, 1555, 384), c(4, 0x41, 1, 0xd07, 1, 1958, 384)),  "Adreno (TM) 430", "20 nm"),
        qc("msm8208", "Snapdragon 208 MSM8208", "MSM8208", "msm8909", "259", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1100, 200)), "Adreno (TM) 304", "28 nm"),
        qc("msm8909", "Snapdragon 210 MSM8909",         "MSM8909", "msm8909", "245",    7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1100, 200)),                                  "Adreno (TM) 304", "28 nm"),
        qc("msm8929", "Snapdragon 415 MSM8929", "MSM8929", "msm8916", "268", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1000, 200), c(4, 65, 0, 0xd03, 4, 1400, 200)), "Adreno (TM) 405", "28 nm"),
        qc("msm8952", "Snapdragon 617 MSM8952",         "MSM8952", "msm8952", "264",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1200, 400), c(4, 0x41, 0, 0xd03, 4, 1500, 400)), "Adreno (TM) 405", "28 nm"),
        qc("msm8996", "Snapdragon 820 MSM8996",         "MSM8996", "msm8996", "246", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1593, 307), c(2, 0x51, 1, 0x205, 2, 2150, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8956", "Snapdragon 650 MSM8956", "MSM8956", "msm8952", "266", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 400), c(2, 65, 0, 0xd08, 2, 1800, 400)), "Adreno (TM) 510", "28 nm"),
        qc("msm8976", "Snapdragon 652 MSM8976", "MSM8976", "", "278", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 400), c(4, 65, 0, 0xd08, 2, 1800, 400)), "Adreno (TM) 510", "28 nm"),
        qc("msm8909w", "Snapdragon Wear 2100 MSM8909W",         "MSM8909W", "msm8909", "300",    7, A7_V7,      listOf(c(4, 0x41, 0, 0xc07, 5, 1200, 200)),                                  "Adreno (TM) 304", "28 nm"),
        qc("msm8996l", "Snapdragon 820 Lite MSM8996L",         "MSM8996L", "msm8996", "302", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1363, 307), c(2, 0x51, 1, 0x205, 2, 1800, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8996au", "Snapdragon 820Am MSM8996AU",         "MSM8996AU", "msm8996", "310", 8, A64_BASE,   listOf(c(2, 0x51, 1, 0x211, 2, 1593, 307), c(2, 0x51, 1, 0x205, 2, 2150, 307)),  "Adreno (TM) 530", "14 nm"),
        qc("msm8937", "Snapdragon 430 MSM8937", "MSM8937", "msm8937", "294", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1100, 960), c(4, 65, 0, 0xd03, 4, 1400, 960)), "Adreno (TM) 505", "28 nm"),
        qc("msm8953", "Snapdragon 625 MSM8953",         "MSM8953", "msm8953", "293", 8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 2000, 652)),                                  "Adreno (TM) 506", "14 nm"),
        qc("msm8917", "Snapdragon 425 MSM8917",         "MSM8917", "msm8937", "303",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1400, 960)),                                  "Adreno (TM) 308", "28 nm"),
        qc("msm8940", "Snapdragon 435 MSM8940",         "MSM8940", "msm8937", "313",    8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 1400, 960)),                                  "Adreno (TM) 505", "28 nm"),
        qc("msm8920", "Snapdragon 427 MSM8920", "MSM8920", "msm8937", "320", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1400, 960)), "Adreno (TM) 308", "28 nm"),
        qc("msm8998", "Snapdragon 835 MSM8998",         "MSM8998", "msm8998", "292", 8, A64_BASE,   listOf(c(4, 0x51, 10, 0x801, 4, 1900, 300), c(4, 0x51, 10, 0x800, 1, 2450, 300)),  "Adreno (TM) 540", "10 nm"),
        qc("msm8905", "Qualcomm 205 MSM8905", "MSM8905", "msm8909", "331", 7, A7_V7, listOf(c(2, 65, 0, 0xc07, 5, 1100, 200)), "Adreno (TM) 304", "28 nm"),
        qc("sdm630", "Snapdragon 630 SDM630", "SDM630", "sdm660", "318", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1800, 633), c(4, 65, 0, 0xd03, 4, 2200, 633)), "Adreno (TM) 508", "14 nm"),
        qc("sdm660", "Snapdragon 660 SDM660", "SDM660", "sdm660", "317", 8, A64_BASE, listOf(c(4, 81, 10, 0x801, 4, 1840, 633), c(4, 81, 10, 0x800, 1, 2200, 633)), "Adreno (TM) 512", "14 nm"),
        qc("sdm450",  "Snapdragon 450 SDM450",          "SDM450",  "msm8953", "338", 8, A64_BASE,   listOf(c(8, 0x41, 0, 0xd03, 4, 1800, 652)),                                  "Adreno (TM) 506", "14 nm"),
        qc("sdm636",  "Snapdragon 636 SDM636",          "SDM636",  "sdm660",  "345", 8, A64_BASE,   listOf(c(4, 0x51, 0, 0x801, 0, 1600, 633), c(4, 0x51, 0, 0x800, 0, 1800, 633)),  "Adreno (TM) 509", "14 nm"),
        qc("sdm845",  "Snapdragon 845 SDM845",          "SDM845",  "sdm845",  "321", 8, A64_V82,    listOf(c(4, 0x51, 7, 0x803, 12, 1766, 300), c(4, 0x51, 6, 0x802, 13, 2803, 825)),  "Adreno (TM) 630", "10 nm"),
        qc("sdm710", "Snapdragon 710 SDM710", "SDM710", "sdm710", "", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2200, 825)), "Adreno (TM) 616", "10 nm"),
        qc("sdm439",  "Snapdragon 439 SDM439",          "SDM439",  "msm8937", "353",    8, A64_BASE,   listOf(c(4, 0x41, 0, 0xd03, 4, 1450, 960), c(4, 0x41, 0, 0xd03, 4, 1950, 960)), "Adreno (TM) 505", "12 nm"),
        qc("sdm429", "Snapdragon 429 SDM429", "SDM429", "msm8937", "354", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 2000, 960)), "Adreno (TM) 504", "12 nm"),
        qc("sdm632", "Snapdragon 632 SDM632", "SDM632", "msm8953", "349", 8, A64_BASE, listOf(c(4, 81, 10, 0x801, 4, 1800, 652), c(4, 81, 10, 0x800, 1, 1800, 652)), "Adreno (TM) 506", "14 nm"),
        qc("sdm670", "Snapdragon 670 SDM670", "SDM670", "sdm710", "336", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2000, 825)), "Adreno (TM) 615", "10 nm"),
        qc("sdm850", "Snapdragon 850 SDM850", "SDM850", "sdm845", "348", 8, A64_V82, listOf(c(4, 81, 7, 0x803, 12, 1770, 300), c(4, 81, 6, 0x802, 13, 2960, 825)), "Adreno (TM) 630", "10 nm"),
        qc("sm8150",  "Snapdragon 855 SM8150",          "SM8150",  "msmnile", "339", 8, A64_V82,    listOf(c(4, 0x51, 0, 0x805, 14, 1785, 300), c(3, 0x51, 0, 0x804, 14, 2419, 710), c(1, 0x51, 0, 0x804, 14, 2841, 825)), "Adreno (TM) 640", "7 nm", hw = "Qualcomm Technologies, Inc SM8150", rev = "2.1", serial = "1749263058", bogo = "38.40"),
        qc("sdm712", "Snapdragon 712 SDM712", "SDM712", "sdm710", "", 8, A64_V82, listOf(c(6, 81, 7, 0x803, 12, 1700, 300), c(2, 81, 6, 0x802, 13, 2300, 825)), "Adreno (TM) 616", "10 nm"),
        qc("sm6150", "Snapdragon 675 SM6150", "SM6150", "sm6150", "", 8, A64_V82, listOf(c(6, 81, 0, 0x805, 0, 1700, 300), c(2, 81, 0, 0x804, 0, 2000, 710)), "Adreno (TM) 612", "11 nm"),
        qc("sm6125", "Snapdragon 665 SM6125", "SM6125", "trinket", "394", 8, A64_V82, listOf(c(4, 81, 10, 0x801, 4, 1800, 300), c(4, 81, 10, 0x800, 1, 2000, 300)), "Adreno (TM) 610", "11 nm"),
        qc("qm215", "Qualcomm 215 QM215", "QM215", "qm215", "386", 8, A64_BASE, listOf(c(4, 65, 0, 0xd03, 4, 1300, 960)), "Adreno (TM) 308", "28 nm"),
        qc("sm6115",  "Snapdragon 662 SM6115",          "SM6115",  "bengal",  "444",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1800, 300), c(4, 0x51, 0, 0x800, 0, 2000, 300)),  "Adreno (TM) 610", "11 nm"),
        qc("sm4250",  "Snapdragon 460 SM4250-AA",          "SM4250-AA",  "bengal",  "417",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1800, 300), c(4, 0x51, 0, 0x800, 0, 1600, 300)),  "Adreno (TM) 610", "11 nm"),
        qc("sm7125",  "Snapdragon 720G SM7125",         "SM7125",  "atoll",   "443",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm8250",  "Snapdragon 865 SM8250",          "SM8250",  "kona",    "356", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd0d, 0, 2420, 710), c(1, 0x41, 0, 0xd0d, 0, 2840, 844)), "Adreno (TM) 650", "7 nm"),
        // Snapdragon 870 = SM8250-AC (kona; VERIFIED naming: LineageOS / TWRP alioth + munch trees "SM8250-AC ... kona"). Prime Cortex-A77 at 3.2 GHz, 3 x A77 2.42, 4 x A55 1.8 GHz (Qualcomm's figures, not re-checked this pass);
        // core revisions copied from the SM8250 row. UNVERIFIED: soc_id (the kernel's list has only SM8250 = 356: blank here), GPU clock (the SM8250 row's 587), minimum clocks.
        qc("sm8250ac", "Snapdragon 870 SM8250-AC", "SM8250-AC", "kona", "", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd0d, 0, 2420, 710), c(1, 0x41, 0, 0xd0d, 0, 3200, 844)), "Adreno (TM) 650", "7 nm"),
        qc("sm7250", "Snapdragon 765G SM7250-AB", "SM7250-AB", "lito", "400", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(1, 65, 0, 0xd0b, 0, 2200, 710), c(1, 65, 0, 0xd0b, 0, 2400, 806)), "Adreno (TM) 620", "7 nm"),
        qc("sm6350", "Snapdragon 690 SM6350", "SM6350", "", "434", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1700, 300), c(2, 65, 0, 0xd0d, 0, 2000, 710)), "Adreno (TM) 619L", "8 nm"),
        qc("sm7150",  "Snapdragon 732G SM7150-AC",      "SM7150-AC","sm6150","365",  8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm7150p",  "Snapdragon 732G SM7150P",      "SM7150P","sm6150","366",  8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2300, 710)),  "Adreno (TM) 618", "8 nm"),
        qc("sm7225", "Snapdragon 750G SM7225", "SM7225", "", "459", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(2, 65, 0, 0xd0d, 0, 2200, 710)), "Adreno (TM) 619", "8 nm"),
        qc("sm4350_480",  "Snapdragon 480 SM4350",      "SM4350","holi",   "454",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1800, 300), c(2, 0x51, 0, 0x804, 0, 2000, 710)),  "Adreno (TM) 619", "8 nm"),
        qc("sm8350",  "Snapdragon 888 SM8350",          "SM8350",  "lahaina", "439", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd41, 0, 2420, 710), c(1, 0x41, 1, 0xd44, 0, 2840, 844)), "Adreno (TM) 660", "5 nm"),
        // Snapdragon 888+ = SM8350-AC (lahaina, prime X1 at 2.995 GHz per Qualcomm), DERIVED from the SM8350 row. CONFIRMED (AnandTech, Qualcomm, GSMArena): prime X1 2.995 GHz, 3 x A78 2.42, 4 x A55 1.8, Adreno 660 at 840 MHz, 5 nm, model SM8350-AC, a speed bin of the SM8350 (whose platform is lahaina, TWRP QRD lahaina tree). UNVERIFIED: soc_id (blank), minimum clocks.
        qc("sm8350ac", "Snapdragon 888+ SM8350-AC", "SM8350-AC", "lahaina", "", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(3, 0x41, 0, 0xd41, 0, 2420, 710), c(1, 0x41, 1, 0xd44, 0, 2995, 844)), "Adreno (TM) 660", "5 nm"),
        qc("sm7325",  "Snapdragon 778G SM7325",         "SM7325",  "yupik",   "475",    8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1900, 300), c(3, 0x41, 0, 0xd41, 0, 2200, 691), c(1, 0x41, 0, 0xd41, 0, 2400, 806)), "Adreno (TM) 642L", "6 nm"),
        qc("sm6225",  "Snapdragon 680 SM6225",          "SM6225",  "bengal",  "518",    8, A64_V82,    listOf(c(4, 0x51, 0, 0x801, 0, 1900, 300), c(4, 0x51, 0, 0x800, 0, 2400, 300)),  "Adreno (TM) 610", "6 nm"),
        qc("sm6375",  "Snapdragon 695 SM6375",          "SM6375",  "holi",    "507", 8, A64_V82,    listOf(c(6, 0x41, 0, 0xd05, 0, 1700, 300), c(2, 0x41, 0, 0xd41, 0, 2200, 691)),  "Adreno (TM) 619", "6 nm"),
        qc("sm4350",  "Snapdragon 480+ SM4350-AC",      "SM4350-AC","holi",   "472",    8, A64_V82,    listOf(c(6, 0x51, 0, 0x805, 0, 1900, 300), c(2, 0x51, 0, 0x804, 0, 2200, 710)),  "Adreno (TM) 619", "8 nm"),
        qc("sm7325p",  "Snapdragon 778G Plus SM7325-AE",         "SM7325-AE",  "yupik",   "499",    8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1900, 300), c(3, 0x41, 0, 0xd41, 0, 2200, 691), c(1, 0x41, 0, 0xd41, 0, 2500, 806)), "Adreno (TM) 642L", "6 nm"),
        qc("sm8450",  "Snapdragon 8 Gen 1 SM8450",      "SM8450",  "taro",    "457", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 300), c(3, 0x41, 0, 0xd47, 0, 2500, 633), c(1, 0x41, 1, 0xd48, 0, 3000, 806)), "Adreno (TM) 730", "4 nm"),
        qc("qcm6490", "Qualcomm QCM6490", "QCM6490", "yupik", "497", 8, A64_V82, listOf(c(4, 65, 0, 0xd05, 0, 1900, 300), c(3, 65, 0, 0xd41, 0, 2400, 691), c(1, 65, 0, 0xd41, 0, 2700, 806)), "Adreno (TM) 643", "6 nm"),
        qc("sm7450", "Snapdragon 7 Gen 1 SM7450-AB", "SM7450-AB", "", "506", 8, A64_V9, listOf(c(4, 65, 0, 0xd46, 0, 1800, 300), c(3, 65, 0, 0xd47, 0, 2360, 633), c(1, 65, 0, 0xd47, 0, 2400, 806)), "Adreno (TM) 644", "4 nm"),
        qc("sm8475",  "Snapdragon 8+ Gen 1 SM8475",     "SM8475",  "cape",    "530", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 2016, 300), c(3, 0x41, 0, 0xd47, 0, 2800, 633), c(1, 0x41, 1, 0xd48, 0, 3200, 806)), "Adreno (TM) 730", "4 nm", hw = "Qualcomm Technologies, Inc SM8475", rev = "1.1", serial = "2480961735", bogo = "38.40"),
        qc("sm8475p",  "Snapdragon 8+ Gen 1 SM8475P",     "SM8475P",  "cape",    "531", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 2016, 300), c(3, 0x41, 0, 0xd47, 0, 2800, 633), c(1, 0x41, 1, 0xd48, 0, 3200, 806)), "Adreno (TM) 730", "4 nm", hw = "Qualcomm Technologies, Inc SM8475", rev = "1.1", bogo = "38.40"),
        qc("sm4375", "Snapdragon 4 Gen 1 SM4375", "SM4375", "", "", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1800, 300), c(2, 65, 0, 0xd41, 0, 2000, 691)), "Adreno (TM) 619", "6 nm"),
        qc("sm8550",  "Snapdragon 8 Gen 2 SM8550",      "SM8550",  "kalama",  "519", 8, A64_V9,     listOf(c(3, 0x41, 0, 0xd46, 0, 2000, 300), c(2, 0x41, 0, 0xd47, 0, 2800, 633), c(2, 0x41, 0, 0xd4d, 0, 2800, 633), c(1, 0x41, 1, 0xd4e, 0, 3200, 806)), "Adreno (TM) 740", "4 nm"),
        qc("sm6450", "Snapdragon 6 Gen 1 SM6450", "SM6450", "parrot", "537", 8, A64_V82, listOf(c(4, 65, 0, 0xd05, 0, 1800, 300), c(4, 65, 0, 0xd41, 0, 2200, 691)), "Adreno (TM) 710", "4 nm"),
        qc("sm7475",  "Snapdragon 7+ Gen 2 SM7475-AB",  "SM7475-AB","ukee",   "591", 8, A64_V9,     listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 300), c(3, 0x41, 0, 0xd47, 0, 2490, 633), c(1, 0x41, 1, 0xd48, 0, 2910, 806)), "Adreno (TM) 725", "4 nm"),
        qc("sm8550p",  "Snapdragon 8 Gen 2 for Galaxy SM8550-AC",      "SM8550-AC",  "kalama",  "536", 8, A64_V9,     listOf(c(3, 0x41, 0, 0xd46, 0, 2000, 300), c(2, 0x41, 0, 0xd47, 0, 2800, 633), c(2, 0x41, 0, 0xd4d, 0, 2800, 633), c(1, 0x41, 1, 0xd4e, 0, 3360, 806)), "Adreno (TM) 740", "4 nm"),
        qc("sm4450", "Snapdragon 4 Gen 2 SM4450", "SM4450", "parrot", "568", 8, A64_V82, listOf(c(6, 65, 0, 0xd05, 0, 1950, 300), c(2, 65, 0, 0xd41, 0, 2200, 691)), "Adreno (TM) 613", "4 nm"),
        qc("sm8650",  "Snapdragon 8 Gen 3 SM8650",      "SM8650",  "pineapple","557",8, A64_V9,     listOf(c(2, 0x41, 0, 0xd80, 0, 2270, 300), c(2, 0x41, 0, 0xd81, 0, 2960, 633), c(3, 0x41, 0, 0xd81, 0, 3150, 633), c(1, 0x41, 0, 0xd82, 0, 3300, 806)), "Adreno (TM) 750", "4 nm"),
        qc("sm7550", "Snapdragon 7 Gen 3 SM7550-AB", "SM7550-AB", "crow", "", 8, A64_V9, listOf(c(4, 65, 0, 0xd46, 0, 1800, 300), c(3, 65, 0, 0xd4d, 0, 2400, 633), c(1, 65, 0, 0xd4d, 0, 2630, 806)), "Adreno (TM) 720", "4 nm"),
        qc("sm8650p",  "Snapdragon 8 Gen 3 for Galaxy SM8650-AC",      "SM8650-AC",  "pineapple","577",8, A64_V9,     listOf(c(2, 0x41, 0, 0xd80, 0, 2270, 300), c(2, 0x41, 0, 0xd81, 0, 2960, 633), c(3, 0x41, 0, 0xd81, 0, 3150, 633), c(1, 0x41, 0, 0xd82, 0, 3400, 806)), "Adreno (TM) 750", "4 nm"),
        qc("sm7675", "Snapdragon 7+ Gen 3 SM7675-AB", "SM7675-AB", "pineapple", "", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 1900, 300), c(4, 65, 0, 0xd81, 0, 2600, 633), c(1, 65, 0, 0xd82, 0, 2800, 806)), "Adreno (TM) 732", "4 nm"),
        qc("sm8635", "Snapdragon 8s Gen 3 SM8635", "SM8635", "cliffs", "", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 2000, 300), c(4, 65, 0, 0xd81, 0, 2800, 633), c(1, 65, 0, 0xd82, 0, 3000, 806)), "Adreno (TM) 735", "4 nm"),
        qc("sm4635",  "Snapdragon 4s Gen 2 SM4635",     "SM4635",  "pitti",        "623",    8, A64_V82,    listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 300), c(2, 0x41, 0, 0xd41, 0, 2000, 691)),  "Adreno (TM) 611", "4 nm"),
        qc("sm6475",  "Snapdragon 6 Gen 3 SM6475-AB",   "SM6475-AB","parrot", "663", 8, A64_V82,    listOf(c(4, 0x41, 0, 0xd05, 0, 1800, 300), c(4, 0x41, 0, 0xd41, 0, 2400, 691)),  "Adreno (TM) 710", "4 nm"),
        qc("sm7635_gen3",  "Snapdragon 7s Gen 3 SM7635",  "SM7635","volcano",       "636",    8, A64_V9,     listOf(c(4, 0x41, 0, 0xd80, 0, 1800, 300), c(3, 0x41, 0, 0xd81, 0, 2400, 633), c(1, 0x41, 0, 0xd81, 0, 2500, 806)), "Adreno (TM) 810", "4 nm"),
        qc("sm8750",  "Snapdragon 8 Elite SM8750",      "SM8750",  "sun",     "618", 8, A64_ORYON,  listOf(c(6, 0x51, 1, 0x001, 0, 3530, 384), c(2, 0x51, 1, 0x001, 0, 4320, 384)),         "Adreno (TM) 830", "3 nm", hw = "Qualcomm Technologies, Inc SM8750", rev = "2.0", serial = "3714052861", bogo = "38.40"),
        qc("sm6650", "Snapdragon 6 Gen 4 SM6650", "SM6650", "volcano", "640", 8, A64_V9, listOf(c(4, 65, 0, 0xd80, 0, 1800, 300), c(3, 65, 0, 0xd81, 0, 2200, 633), c(1, 65, 0, 0xd81, 0, 2300, 806)), "Adreno (TM) 810", "4 nm"),
        qc("sm8735",  "Snapdragon 8s Gen 4 SM8735",     "SM8735",  "",        "681", 8, A64_V9,     listOf(c(2, 0x41, 0, 0xd81, 0, 2020, 300), c(2, 0x41, 0, 0xd81, 0, 2800, 633), c(3, 0x41, 0, 0xd81, 0, 3000, 633), c(1, 0x41, 0, 0xd82, 0, 3210, 806)), "Adreno (TM) 825", "4 nm"),
        qc("sm7750", "Snapdragon 7 Gen 4 SM7750-AB", "SM7750-AB", "", "659", 8, A64_V9, listOf(c(3, 65, 0, 0xd80, 0, 1840, 300), c(4, 65, 0, 0xd81, 0, 2400, 633), c(1, 65, 0, 0xd81, 0, 2800, 806)), "Adreno (TM) 722", "4 nm"),
        qc("sm7635",  "Snapdragon 7s Gen 4 SM7635-AC",  "SM7635-AC","volcano",       "712",    8, A64_V9,     listOf(c(4, 0x41, 0, 0xd80, 0, 1800, 300), c(3, 0x41, 0, 0xd81, 0, 2400, 633), c(1, 0x41, 0, 0xd81, 0, 2700, 806)), "Adreno (TM) 810", "4 nm"),
        qc("sm8850", "Snapdragon 8 Elite Gen 5 SM8850", "SM8850", "canoe", "660", 8, A64_ORYON, listOf(c(6, 81, 1, 0x001, 0, 3630, 384), c(2, 81, 1, 0x001, 0, 4610, 384)), "Adreno (TM) 840", "3 nm", hw = "Qualcomm Technologies, Inc SM8850"),
        qc("sm8950", "Snapdragon 8 Elite Gen 6 SM8950", "SM8950", "", "735", 8, A64_ORYON, listOf(c(3, 81, 1, 0x001, 0, 3740, 384), c(3, 81, 1, 0x001, 0, 4030, 384), c(2, 81, 1, 0x001, 0, 5010, 384)), "Adreno (TM) 845", "2 nm"),
        qc("sm8975", "Snapdragon 8 Elite Extreme Gen 6 SM8975", "SM8975", "", "707", 8, A64_ORYON, listOf(c(3, 81, 1, 0x001, 0, 3740, 384), c(3, 81, 1, 0x001, 0, 4030, 384), c(2, 81, 1, 0x001, 0, 5010, 384)), "Adreno (TM) 850", "2 nm"),
        // ==== MediaTek, oldest first
        mt("mt6582", "MediaTek MT6582", "MT6582", "mt6582", "6582", 7, V7, listOf(c(4, 0x41, 0, 0xc07, 3, 1300, 598)), "Mali-400 MP", ARM_GPU, "28 nm"),
        mt("mt6592", "MediaTek MT6592", "MT6592", "mt6592", "6592", 7, V7, listOf(c(8, 0x41, 0, 0xc07, 3, 1700, 598)), "Mali-450 MP", ARM_GPU, "28 nm"),
        mt("mt6580", "MediaTek MT6580",                 "MT6580", "mt6580", "6580", 7, V7,       listOf(c(4, 0x41, 0, 0xc07, 5, 1300, 598)),                                  "Mali-400 MP",     "ARM",    "28 nm", serial = "1385207349", bogo = "26.00"),
        mt("mt6735", "MediaTek MT6735", "MT6735", "mt6735", "6735", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 2, 1300, 598)), "Mali-T720", ARM_GPU, "28 nm"),
        mt("mt6755", "MediaTek Helio P10 MT6755", "MT6755", "mt6755", "6755", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 2, 1000, 598), c(4, 0x41, 0, 0xd03, 2, 2000, 598)), "Mali-T860", ARM_GPU, "28 nm"),
        // ---- MediaTek rows added 2026-11-27 (twelfth pass). MT6595 / MT6752 / MT6750 / MT8167B are VERIFIED against real dumps (github.com/pytorch/cpuinfo test/mock); MT6753 / MT6732 are DERIVED.
        // MT6595 = Lenovo Vibe X2 (kernel 3.4, Android 4.4.2): ARM_K30 layout, Processor header "ARMv7 Processor rev 5 (v7l)", BogoMIPS 38.40, Features with thumbee, Hardware "MT6595", Revision 0000,
        // Serial zeros; cpu0-3 = A7 r0p5 403-1690 MHz (hotplug governor: only cpu0 was online). WLAN chip is a bare "MT6630" (postfix _mt6630), ro.mediatek.version.sdk 2, GLES 3.0 (196608).
        // Confirmed (GSMArena / Tom's Hardware / WikiChip): 4 x A17 up to 2.2 GHz (MT6595 Turbo 2.5), 4 x A7 up to 1.7 GHz, PowerVR G6200, 28 nm; A17's MIDR part 0xc0e is right. UNVERIFIED: the A17 variant / revision (1 / 2: no dump or page shows them; they never print in this layout), ro.board.platform (the dump's build.prop leaves it empty: mt6595 by the "mt" + model rule).
        mt("mt6595", "MediaTek MT6595", "MT6595", "mt6595", "6595", 7, "swp half thumb fastmult vfp edsp thumbee neon vfpv3 tls vfpv4 idiva idivt",
           listOf(c(4, 0x41, 0, 0xc07, 5, 1690, 403), c(4, 0x41, 1, 0xc0e, 2, 2200, 403)), "PowerVR Rogue G6200", IMG, "28 nm", serial = "0000000000000000", bogo = "38.40")
            .copy(cpuinfoStyle = CpuinfoStyle.ARM_K30, hwRevision = "0000",
                  extraProps = mapOf("mediatek.wlan.chip" to "MT6630", "mediatek.wlan.module.postfix" to "_mt6630", "ro.mediatek.version.sdk" to "2", "ro.opengles.version" to "196608")),
        // MT6752 = Sony Xperia C4 Dual E5333 (kernel 3.10, Android 5.1): A64_K310M layout (see CpuinfoStyle), 8 x A53 r0p2, BogoMIPS 26.00, 468-1690 MHz (mt-cpufreq, governors without hotplug),
        // Features "fp asimd aes pmull sha1 sha2 crc32" (no evtstrm), Hardware "MT6752", platform mt6752, GLES 3.0, WLAN CONSYS_MT6752. Confirmed by Notebookcheck / GSMArena: up to 1.7 GHz, Mali-T760 MP2, 28 nm. UNVERIFIED: the second cluster's clocks (same as cpu0's).
        mt("mt6752", "MediaTek MT6752", "MT6752", "mt6752", "6752", 8, "fp asimd aes pmull sha1 sha2 crc32", listOf(c(4, 0x41, 0, 0xd03, 2, 1690, 468), c(4, 0x41, 0, 0xd03, 2, 1690, 468)),
           "Mali-T760", ARM_GPU, "28 nm").copy(cpuinfoStyle = CpuinfoStyle.A64_K310M, extraProps = mapOf("ro.opengles.version" to "196608")),
        // MT6750 = Alcatel Revvl 5049W (kernel 3.18, Android 7.0): A64_K318M layout, A53 r0p2, BogoMIPS 26.00 printed twice, Hardware "MT6738", ro.board.platform mt6750, ro.mediatek.platform MT6755,
        // WLAN CONSYS_MT6755, GLES 3.2, cpu4-7 408... 286-1508 MHz (cpu0-3 were offline). Confirmed (Notebookcheck): 4 x A53 up to 1.5 GHz + 4 x A53 up to 1.0 GHz, Mali-T860 MP2, 28 nm.
        mt("mt6750", "MediaTek MT6750", "MT6750", "mt6750", "6750", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 2, 1000, 286), c(4, 0x41, 0, 0xd03, 2, 1508, 286)),
           "Mali-T860", ARM_GPU, "28 nm").copy(hardware = "MT6738", cpuinfoStyle = CpuinfoStyle.A64_K318M, extraProps = mapOf("ro.mediatek.platform" to "MT6755")),
        // MT8167B = Acer Iconia One 10 B3-A40 (kernel 3.18, Android 7.0): A64_K318P layout, 4 x A35 r0p1 (part 0xd04) 598-1300 MHz (mtk-cpufreq, mt81xx_v2_cpuidle), Hardware "MT8167B", platform mt8167,
        // ro.mediatek.platform MT8167, WLAN CONSYS_MT6630, GLES 3.2. Confirmed (CNX Software / Acer's spec sheet): quad A35 at 1.3 GHz with a PowerVR GE8300.
        mt("mt8167b", "MediaTek MT8167B", "MT8167B", "mt8167", "8167", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd04, 1, 1300, 598)), "PowerVR Rogue GE8300", IMG, "28 nm")
            .copy(cpuinfoStyle = CpuinfoStyle.A64_K318P, extraProps = mapOf("ro.mediatek.platform" to "MT8167", "mediatek.wlan.chip" to "CONSYS_MT6630", "mediatek.wlan.module.postfix" to "_consys_mt6630")),
        // MT6753 (DERIVED from the MT6752 dump: same kernel 3.10 layout and A53 r0p2; 8 x A53 at up to 1.5 GHz and a Mali-T720 (MediaTek's launch release; the 1.3 GHz I first wrote was a lower bin; 28 nm confirmed). Whether the eight cores are one cluster or two, the minimum clock, platform name "mt6753" and GLES 3.0 are UNVERIFIED.
        mt("mt6753", "MediaTek MT6753", "MT6753", "mt6753", "6753", 8, "fp asimd aes pmull sha1 sha2 crc32", listOf(c(8, 0x41, 0, 0xd03, 2, 1500, 468)),
           "Mali-T720", ARM_GPU, "28 nm").copy(cpuinfoStyle = CpuinfoStyle.A64_K310M, extraProps = mapOf("ro.opengles.version" to "196608")),
        // MT6732 (DERIVED from the MT6752 dump; 4 x A53 at 1.5 GHz, Mali-T760 MP2: confirmed by MediaTek's announcement as reported by GSMArena / Notebookcheck). Minimum clock, platform name "mt6732" and GLES 3.0 are UNVERIFIED.
        mt("mt6732", "MediaTek MT6732", "MT6732", "mt6732", "6732", 8, "fp asimd aes pmull sha1 sha2 crc32", listOf(c(4, 0x41, 0, 0xd03, 2, 1500, 468)),
           "Mali-T760", ARM_GPU, "28 nm").copy(cpuinfoStyle = CpuinfoStyle.A64_K310M, extraProps = mapOf("ro.opengles.version" to "196608")),
        mt("mt6797", "MediaTek Helio X20 MT6797", "MT6797", "mt6797", "6797", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 2, 1400, 598), c(4, 0x41, 0, 0xd03, 2, 1850, 598), c(2, 0x41, 0, 0xd08, 2, 2100, 598)), "Mali-T880", ARM_GPU, "20 nm"),
        mt("mt6757", "MediaTek Helio P20 MT6757", "MT6757", "mt6757", "6757", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1600, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-T880", ARM_GPU, "16 nm"),
        mt("mt6799", "MediaTek Helio X30 MT6799", "MT6799", "mt6799", "6799", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd04, 0, 1900, 500), c(4, 0x41, 0, 0xd03, 4, 2200, 500), c(2, 0x41, 0, 0xd09, 2, 2600, 500)), "PowerVR Rogue GT7400 Plus", IMG, "10 nm"),
        mt("mt6757_p25", "MediaTek Helio P25 MT6757T", "MT6757", "mt6757", "6757", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1600, 500), c(4, 0x41, 0, 0xd03, 4, 2600, 500)), "Mali-T880", ARM_GPU, "16 nm"),
        // Helio X25: Meizu Pro 6 / 6s dump (kernel 3.18 / 4.4): A53 r0p4 x4 221-1547 + x4 325-2002 MHz, A72 cluster 338 MHz min, BogoMIPS 26.00, Hardware "MT6797T",
        // ro.board.platform / ro.mediatek.platform "mt6797" (the T is only in the Hardware line). A72 r0p2 and its 2.5 GHz top clock are NOT in the dump (those cores were offline): X20 row / MediaTek spec.
        mt("mt6797_x25", "MediaTek Helio X25 MT6797T", "MT6797", "mt6797", "6797", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1547, 221), c(4, 0x41, 0, 0xd03, 4, 2002, 325), c(2, 0x41, 0, 0xd08, 2, 2500, 338)), "Mali-T880", ARM_GPU, "20 nm").copy(hardware = "MT6797T"),
        mt("mt6763", "MediaTek Helio P23 MT6763", "MT6763", "mt6763", "6763", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1650, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-G71 MP2", ARM_GPU, "16 nm"),
        mt("mt6758", "MediaTek Helio P30 MT6758", "MT6758", "mt6758", "6758", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1650, 500), c(4, 0x41, 0, 0xd03, 4, 2300, 500)), "Mali-G71 MP2", ARM_GPU, "16 nm"),
        mt("mt6739", "MediaTek MT6739",                 "MT6739", "mt6739", "6739", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1500, 598)),                                  "PowerVR Rogue GE8100",  "Imagination Technologies", "28 nm", serial = "2036184097", bogo = "26.00"),
        mt("mt6762", "MediaTek Helio P22 MT6762",       "MT6762", "mt6762", "6762", 8, A64_LITE, listOf(c(8, 0x41, 0, 0xd03, 4, 2000, 598)),                                  "PowerVR Rogue GE8320",  "Imagination Technologies", "12 nm", serial = "1853390726", bogo = "26.00"),
        mt("mt6771", "MediaTek Helio P60 MT6771", "MT6771", "mt6771", "6771", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 500), c(4, 0x41, 0, 0xd09, 2, 2000, 500)), "Mali-G72 MP3", ARM_GPU, "12 nm"),
        mt("mt6771_p70", "MediaTek Helio P70 MT6771V/CT", "MT6771", "mt6771", "6771", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 500), c(4, 0x41, 0, 0xd09, 2, 2100, 500)), "Mali-G72 MP3", ARM_GPU, "12 nm"),
        // Helio P65 (MT6768): 2x A75 2.0 GHz + 6x A55 1.7 GHz, Mali-G52 MC2, 12 nm. Core layout of the existing G70 row; platform "mt6768" as the G85 row.
        mt("mt6768_p65", "MediaTek Helio P65 MT6768", "MT6768", "mt6768", "6768", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1700, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6761", "MediaTek Helio A22 MT6761", "MT6761", "mt6761", "6761", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 2000, 598)), "PowerVR Rogue GE8300", IMG, "12 nm"),
        mt("mt6765", "MediaTek Helio P35 MT6765", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6779", "MediaTek Helio P90 MT6779", "MT6779", "mt6779", "6779", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 0, 0xd0a, 0, 2200, 500)), "PowerVR Rogue GM9446", IMG, "12 nm"),
        // Helio P95 = the P90's silicon (same cores and clocks), GPU clock higher
        mt("mt6779_p95", "MediaTek Helio P95 MT6779V", "MT6779", "mt6779", "6779", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 0, 0xd0a, 0, 2200, 500)), "PowerVR Rogue GM9446", IMG, "12 nm"),
        mt("mt6785", "MediaTek Helio G90T MT6785", "MT6785", "mt6785", "6785", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G76 MC4", ARM_GPU, "12 nm"),
        mt("mt6785_g95", "MediaTek Helio G95 MT6785", "MT6785", "mt6785", "6785", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G76 MC4", ARM_GPU, "12 nm"),
        mt("mt6873", "MediaTek Dimensity 800 MT6873", "MT6873", "mt6873", "6873", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd0b, 0, 2000, 500)), "Mali-G57 MC4", ARM_GPU, "7 nm"),
        mt("mt6765_g35", "MediaTek Helio G35 MT6765G", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        // Helio G25 = the P22's silicon (MT6762, 8x A53 2.0 GHz, GE8320); Android 10 / kernel 4.9 launch
        mt("mt6762_g25", "MediaTek Helio G25 MT6762G", "MT6762", "mt6762", "6762", 8, A64_LITE, listOf(c(8, 0x41, 0, 0xd03, 4, 2000, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6768_g70", "MediaTek Helio G70 MT6769V/CB", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1700, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6768_g80", "MediaTek Helio G80 MT6769T", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6768_g85", "MediaTek Helio G85 MT6769Z", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6853", "MediaTek Dimensity 720 MT6853", "MT6853", "mt6853", "6853", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2000, 500)), "Mali-G57 MC3", ARM_GPU, "7 nm"),
        // Dimensity 800U = the 720's silicon with the A76 pair at 2.4 GHz (MediaTek launch figures, unverified)
        mt("mt6853_800u", "MediaTek Dimensity 800U MT6853V", "MT6853", "mt6853", "6853", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC3", ARM_GPU, "7 nm"),
        // Dimensity 820 (4x A76 2.6 GHz + 4x A55 2.0 GHz, Mali-G57 MC5), 1000L (2x A77 2.2 GHz + 6x A55 2.0 GHz, Mali-G77 MC7), 1000+ (4x A77 2.6 GHz + 4x A55 2.0 GHz, Mali-G77 MC9; core / GPU
        // figures of the 1000+ cross-checked against Notebookcheck and the realme X7 Pro spec page). Platform = "mt" + model number (the rule above), NOT seen in a device tree.
        mt("mt6875", "MediaTek Dimensity 820 MT6875", "MT6875", "mt6875", "6875", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd0b, 0, 2600, 500)), "Mali-G57 MC5", ARM_GPU, "7 nm"),
        mt("mt6885", "MediaTek Dimensity 1000L MT6885", "MT6885", "mt6885", "6885", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0d, 0, 2200, 500)), "Mali-G77 MC7", ARM_GPU, "7 nm"),
        mt("mt6889_plus", "MediaTek Dimensity 1000+ MT6889", "MT6889", "mt6889", "6889", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd0d, 0, 2600, 500)), "Mali-G77 MC9", ARM_GPU, "7 nm"),
        mt("mt6833", "MediaTek Dimensity 700 MT6833", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "7 nm"),
        mt("mt6893", "MediaTek Dimensity 1200 MT6893", "MT6893", "mt6893", "6893", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(3, 0x41, 1, 0xd41, 0, 2600, 500), c(1, 0x41, 1, 0xd41, 0, 3000, 500)), "Mali-G77 MC9", ARM_GPU, "6 nm"),
        mt("mt6891", "MediaTek Dimensity 1100 MT6891", "MT6891", "mt6891", "6891", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G77 MC9", ARM_GPU, "6 nm"),
        mt("mt6833_810", "MediaTek Dimensity 810 MT6833P", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6877", "MediaTek Dimensity 900 MT6877", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2400, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6781", "MediaTek Helio G96 MT6781", "MT6781", "mt6781", "6781", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2050, 500)), "Mali-G57 MC2", ARM_GPU, "12 nm"),
        mt("mt6768_g88", "MediaTek Helio G88 MT6769H", "MT6768", "mt6768", "6769", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 1800, 500), c(2, 0x41, 0, 0xd0a, 0, 2000, 500)), "Mali-G52 MC2", ARM_GPU, "12 nm"),
        mt("mt6765_g37", "MediaTek Helio G37 MT6765H", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2300, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6983", "MediaTek Dimensity 9000 MT6983", "MT6983", "mt6983", "6983", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd47, 0, 2850, 500), c(1, 0x41, 1, 0xd48, 0, 3050, 500)), "Mali-G710 MC10", ARM_GPU, "4 nm"),
        mt("mt6765_g36", "MediaTek Helio G36 MT6765", "MT6765", "mt6765", "6765", 8, A64_LITE, listOf(c(4, 0x41, 0, 0xd03, 4, 1800, 598), c(4, 0x41, 0, 0xd03, 4, 2200, 598)), "PowerVR Rogue GE8320", IMG, "12 nm"),
        mt("mt6895", "MediaTek Dimensity 8100 MT6895", "MT6895", "mt6895", "6895", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2850, 500)), "Mali-G610 MC6", ARM_GPU, "5 nm"),
        mt("mt6895_d8000", "MediaTek Dimensity 8000 MT6895", "MT6895", "mt6895", "6895", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2750, 500)), "Mali-G610 MC6", ARM_GPU, "5 nm"),
        mt("mt6789", "MediaTek Helio G99 MT6789",       "MT6789", "mt6789", "6789", 8, A64_V82,  listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", "ARM",   "6 nm",  serial = "3490172865", bogo = "26.00"),
        mt("mt6877_1080", "MediaTek Dimensity 1080 MT6877V", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6983_plus", "MediaTek Dimensity 9000+ MT6983", "MT6983", "mt6983", "6983", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd47, 0, 2850, 500), c(1, 0x41, 1, 0xd48, 0, 3200, 500)), "Mali-G710 MC10", ARM_GPU, "4 nm"),
        mt("mt6879", "MediaTek Dimensity 1050 MT6879", "MT6879", "mt6879", "6879", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2500, 500)), "Mali-G610 MC3", ARM_GPU, "6 nm"),
        mt("mt6855", "MediaTek Dimensity 930 MT6855", "MT6855", "mt6855", "6855", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2200, 500)), "PowerVR B-Series BXM-8-256", IMG, "6 nm"),
        mt("mt6985", "MediaTek Dimensity 9200 MT6985", "MT6985", "mt6985", "6985", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 1800, 500), c(3, 0x41, 0, 0xd4d, 0, 2850, 500), c(1, 0x41, 1, 0xd4e, 0, 3050, 500)), "Immortalis-G715 MC11", ARM_GPU, "4 nm"),
        mt("mt6896", "MediaTek Dimensity 8200 MT6896", "MT6896", "mt6896", "6896", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(3, 0x41, 1, 0xd41, 0, 3000, 500), c(1, 0x41, 1, 0xd41, 0, 3100, 500)), "Mali-G610 MC6", ARM_GPU, "4 nm"),
        mt("mt6855_7020", "MediaTek Dimensity 7020 MT6855", "MT6855", "mt6855", "6855", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2200, 500)), "PowerVR B-Series BXM-8-256", IMG, "6 nm"),
        mt("mt6833_6020", "MediaTek Dimensity 6020 MT6833", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "7 nm"),
        mt("mt6886", "MediaTek Dimensity 7200 MT6886", "MT6886", "mt6886", "6886", 8, A64_V9, listOf(c(6, 0x41, 0, 0xd46, 0, 2000, 500), c(2, 0x41, 0, 0xd4d, 0, 2800, 500)), "Mali-G610 MC4", ARM_GPU, "4 nm"),
        mt("mt6833_6080", "MediaTek Dimensity 6080 MT6833P", "MT6833", "mt6833", "6833", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6985_plus", "MediaTek Dimensity 9200+ MT6985", "MT6985", "mt6985", "6985", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 2000, 500), c(3, 0x41, 0, 0xd4d, 0, 3000, 500), c(1, 0x41, 1, 0xd4e, 0, 3350, 500)), "Immortalis-G715 MC11", ARM_GPU, "4 nm"),
        mt("mt6877_7050", "MediaTek Dimensity 7050 MT6877V", "MT6877", "mt6877", "6877", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd41, 0, 2600, 500)), "Mali-G68 MC4", ARM_GPU, "6 nm"),
        mt("mt6897", "MediaTek Dimensity 8300 MT6897", "MT6897", "mt6897", "6897", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd46, 0, 2200, 500), c(3, 0x41, 0, 0xd4d, 0, 3200, 500), c(1, 0x41, 0, 0xd4d, 0, 3350, 500)), "Mali-G615 MC6", ARM_GPU, "4 nm"),
        mt("mt6989", "MediaTek Dimensity 9300 MT6989", "MT6989", "mt6989", "6989", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2000, 500), c(3, 0x41, 0, 0xd82, 0, 2850, 500), c(1, 0x41, 0, 0xd82, 0, 3250, 500)), "Immortalis-G720 MC12", ARM_GPU, "4 nm"),
        mt("mt6835", "MediaTek Dimensity 6300 MT6835", "MT6835", "mt6835", "6835", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2400, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6789_g100", "MediaTek Helio G100 MT6789H", "MT6789", "mt6789", "6789", 8, A64_V82, listOf(c(6, 0x41, 0, 0xd05, 0, 2000, 500), c(2, 0x41, 1, 0xd0b, 0, 2200, 500)), "Mali-G57 MC2", ARM_GPU, "6 nm"),
        mt("mt6878", "MediaTek Dimensity 7300 MT6878",  "MT6878", "mt6878", "6878", 8, A64_V82,  listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(4, 0x41, 1, 0xd41, 0, 2500, 500)), "Mali-G615 MC2", "ARM",  "4 nm",  serial = "1126973504", bogo = "26.00"),
        mt("mt6989_plus", "MediaTek Dimensity 9300+ MT6989", "MT6989", "mt6989", "6989", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2000, 500), c(3, 0x41, 0, 0xd82, 0, 2850, 500), c(1, 0x41, 0, 0xd82, 0, 3400, 500)), "Immortalis-G720 MC12", ARM_GPU, "4 nm"),
        mt("mt6991", "MediaTek Dimensity 9400 MT6991", "MT6991", "mt6991", "6991", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2400, 500), c(3, 0x41, 0, 0xd82, 0, 3300, 500), c(1, 0x41, 0, 0xd85, 0, 3620, 500)), "Immortalis-G925 MC12", ARM_GPU, "3 nm"),
        mt("mt6899", "MediaTek Dimensity 8400 MT6899", "MT6899", "mt6899", "6899", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd87, 0, 2100, 500), c(3, 0x41, 0, 0xd87, 0, 3000, 500), c(1, 0x41, 0, 0xd87, 0, 3250, 500)), "Mali-G720 MC7", ARM_GPU, "4 nm"),
        mt("mt6991_plus", "MediaTek Dimensity 9400+ MT6991", "MT6991", "mt6991", "6991", 8, A64_V9, listOf(c(4, 0x41, 0, 0xd81, 0, 2400, 500), c(3, 0x41, 0, 0xd82, 0, 3300, 500), c(1, 0x41, 0, 0xd85, 0, 3730, 500)), "Immortalis-G925 MC12", ARM_GPU, "3 nm"),
        // ==== Samsung Exynos, oldest first. id, menu name, ro.soc.model, platform, ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node, cpuinfo layout
        ex("exynos3110", "Samsung Exynos 3110 (Hummingbird)", "exynos3110", "s5pc110", 7, EXY_A8, listOf(c(1, 0x41, 2, 0xc08, 2, 1000, 100)),
            "PowerVR SGX 540", IMG, "45 nm", CpuinfoStyle.ARM_K30, hardware = "herring", bogo = "994.65", hwRev = "0022",
            props = mapOf("ro.hardware" to "herring", "ro.product.board" to "herring", "ro.opengles.version" to "131072")),
        ex("exynos4212", "Samsung Exynos 4212 (Exynos 4 Dual)", "exynos4212", "exynos4", 7, EXY_A9, listOf(c(2, 0x41, 3, 0xc09, 0, 1500, 200)),
            "Mali-400 MP", ARM_GPU, "32 nm", CpuinfoStyle.ARM_K30, hardware = "SMDK4x12", bogo = "1493.50", hwRev = "000c",
            props = mapOf("ro.hardware" to "smdk4x12", "ro.product.board" to "smdk4x12", "ro.opengles.version" to "131072")),
        ex("exynos4412", "Samsung Exynos 4412 (Exynos 4 Quad)", "exynos4412", "exynos4", 7, EXY_A9, listOf(c(4, 0x41, 3, 0xc09, 0, 1400, 200)),
            "Mali-400 MP", ARM_GPU, "32 nm", CpuinfoStyle.ARM_K30, hardware = "SMDK4x12", bogo = "1393.17", hwRev = "000c",
            props = mapOf("ro.hardware" to "smdk4x12", "ro.product.board" to "smdk4x12", "ro.opengles.version" to "131072")),
        // Exynos 5250 = Nexus 10 (real dump, kernel 3.4): Processor header, BogoMIPS 997.78, one shared Features block, Hardware "Manta", Revision 0008; exynos_cpufreq 200-1700 MHz,
        // ro.board.platform exynos5, ro.product.board manta, GLES 3.0 (196609). The GL_RENDERER string and ro.soc.model are NOT from the dump.
        ex("exynos5250", "Samsung Exynos 5250 (Exynos 5 Dual)", "exynos5250", "exynos5", 7, EXY_A15, listOf(c(2, 0x41, 0, 0xc0f, 4, 1700, 200)),
            "Mali-T604", ARM_GPU, "32 nm", CpuinfoStyle.ARM_K30, hardware = "Manta", bogo = "997.78", hwRev = "0008",
            props = mapOf("ro.hardware" to "manta", "ro.product.board" to "manta", "ro.opengles.version" to "196609")),
        ex("exynos5420", "Samsung Exynos 5420 (Exynos 5 Octa)", "exynos5420", "exynos5", 7, EXY_A15,
            listOf(c(4, 0x41, 0, 0xc07, 3, 1300, 500), c(4, 0x41, 2, 0xc0f, 3, 1900, 800)),
            "Mali-T628", ARM_GPU, "28 nm", CpuinfoStyle.ARM_K30, hardware = "universal5420", bogo = "2585.19", hwRev = "000a",
            props = mapOf("ro.hardware" to "universal5420", "ro.product.board" to "universal5420", "ro.opengles.version" to "196608")),
        // Exynos 5422 = Galaxy S5 SM-G900H (VERIFIED, real dump pytorch/cpuinfo galaxy-s5-global, kernel 3.10, Android 5.0): per-cpu blocks WITHOUT the Processor header, cpu0-3 A7 r0p3 (variant 0, part 0xc07,
        // BogoMIPS 2585.19) 500-1300 MHz, cpu4-7 A15 r2p3 (variant 2, part 0xc0f, BogoMIPS 3380.63) 800-1900 MHz, Hardware "universal5422", Revision 000a, ARM32 layout (trailing space after Features),
        // ro.board.platform exynos5, ro.product.board universal5422, ro.chipname / ro.arch exynos5422, GLES 3.0; exynos_cpufreq / exynos_idle, latency 100000, governors "userspace powersave interactive performance".
        // Mali-T628 MP6 and 28 nm confirmed (AnandTech, GSMArena, LineageOS wiki; the shipped clocks are 1.9 / 1.3 GHz, the 2.1 / 1.5 GHz are the chip's rated figures). NOT from the dump: ro.hardware (the Hardware line's value), the package ids (the S5 prints 1 for the A7 cluster and 0 for the A15 one: not reproducible here).
        // Must stay OUT of EXY_LEGACY: a "Processor" header would be printed.
        ex("exynos5422", "Samsung Exynos 5422 (Exynos 5 Octa)", "exynos5422", "exynos5", 7, EXY_A15,
           listOf(SocCluster(4, 0x41, 0, 0xc07, 3, 1300, 500, "2585.19"), SocCluster(4, 0x41, 2, 0xc0f, 3, 1900, 800, "3380.63")),
           "Mali-T628", ARM_GPU, "28 nm", CpuinfoStyle.ARM32, hardware = "universal5422", bogo = "2585.19", hwRev = "000a",
           props = mapOf("ro.hardware" to "universal5422", "ro.product.board" to "universal5422", "ro.chipname" to "exynos5422", "ro.arch" to "exynos5422", "ro.opengles.version" to "196608"))
            .copy(kernel = ArmKernel(cpuidleDriver = "exynos_idle", cpufreqDriver = "exynos_cpufreq", governors = "userspace powersave interactive performance", transitionLatencyNs = 100000)),
        ex("exynos5260", "Samsung Exynos 5260 (Exynos 5 Hexa)", "exynos5260", "exynos5", 7, EXY_A15,
            listOf(c(4, 0x41, 0, 0xc07, 3, 1300, 500), c(2, 0x41, 2, 0xc0f, 3, 1700, 800)),
            "Mali-T624", ARM_GPU, "28 nm", CpuinfoStyle.ARM_K30, hardware = "universal5260", bogo = "2585.19", hwRev = "000a",
            props = mapOf("ro.hardware" to "universal5260", "ro.product.board" to "universal5260", "ro.opengles.version" to "196608")),
        ex("exynos3470", "Samsung Exynos 3470 (Exynos 3 Quad)", "exynos3470", "exynos3", 7, EXY_A15, listOf(c(4, 0x41, 0, 0xc07, 3, 1400, 200)),
            "Mali-400 MP", ARM_GPU, "28 nm", CpuinfoStyle.ARM_K30, hardware = "universal3470", bogo = "2785.86", hwRev = "000a",
            props = mapOf("ro.hardware" to "universal3470", "ro.product.board" to "universal3470", "ro.opengles.version" to "131072")),
        ex("exynos7420", "Samsung Exynos 7420 (Exynos 7 Octa)", "exynos7420", "exynos5", 8, EXY_K310,
            listOf(c(4, 0x41, 0, 0xd03, 2, 1500, 400), c(4, 0x41, 1, 0xd07, 1, 2100, 800)),
            "Mali-T760", ARM_GPU, "14 nm", CpuinfoStyle.A64_K310, hardware = "SAMSUNG Exynos7420",
            props = mapOf("ro.hardware" to "samsungexynos7420", "ro.boot.hardware" to "samsungexynos7420", "ro.product.board" to "universal7420",
                "ro.chipname" to "exynos7420", "ro.arch" to "exynos7420", "ro.opengles.version" to "196609")),
        // Exynos 7580 = Galaxy J7 SM-J700F (real dump, kernel 3.10, 64-bit kernel): A53 r0p3 x8, 400-1500 MHz, Hardware "SAMSUNG Exynos7580", ro.product.board universal7580,
        // ro.chipname / ro.arch exynos7580, GLES 3.1 (196609), exynos64_idle / smp-cpufreq. NOT from the dump: ro.hardware / ro.boot.hardware (pattern of the 7420), GL_RENDERER, ro.soc.model.
        ex("exynos7580", "Samsung Exynos 7580 (Exynos 7 Octa)", "exynos7580", "exynos5", 8, EXY_K310,
            listOf(c(4, 0x41, 0, 0xd03, 3, 1500, 400), c(4, 0x41, 0, 0xd03, 3, 1500, 400)),
            "Mali-T720", ARM_GPU, "28 nm", CpuinfoStyle.A64_K310, hardware = "SAMSUNG Exynos7580",
            props = mapOf("ro.hardware" to "samsungexynos7580", "ro.boot.hardware" to "samsungexynos7580", "ro.product.board" to "universal7580",
                "ro.chipname" to "exynos7580", "ro.arch" to "exynos7580", "ro.opengles.version" to "196609")),
        // Exynos 7578 = Galaxy A3 (2016) SM-A310F (VERIFIED, real dump galaxy-a3-2016-eu, kernel 3.10, Android 5.1.1): the 7580's layout with 4 cpus, A53 r0p3, 400-1500 MHz (smp-cpufreq, exynos64_idle),
        // Hardware "SAMSUNG Exynos7580" and ro.chipname / ro.product.board exynos7580 / universal7580 (a 7578 reports the 7580's names), GLES 3.0 (196608, the J7's is 3.1). Mali-T720 MP2 and 28 nm confirmed (Notebookcheck, GSMArena). NOT from the dump: ro.hardware / ro.arch.
        ex("exynos7578", "Samsung Exynos 7578 (Exynos 7 Quad)", "exynos7578", "exynos5", 8, EXY_K310, listOf(c(4, 0x41, 0, 0xd03, 3, 1500, 400)),
           "Mali-T720", ARM_GPU, "28 nm", CpuinfoStyle.A64_K310, hardware = "SAMSUNG Exynos7580",
           props = mapOf("ro.hardware" to "samsungexynos7580", "ro.boot.hardware" to "samsungexynos7580", "ro.product.board" to "universal7580",
               "ro.chipname" to "exynos7580", "ro.arch" to "exynos7580", "ro.opengles.version" to "196608")),
        // Exynos 7880 (Galaxy A5 / A7 2017): DERIVED from the Exynos 7870 dump row (same A53 r0p4, kernel 3.18 layout): 8 x A53 at 1.9 GHz, Mali-T830 MP3, 14 nm (Samsung). UNVERIFIED: minimum clock,
        // property values (the 7870 pattern with universal7880), GLES version.
        ex("exynos7880", "Samsung Exynos 7880 (Exynos 7 Octa)", "exynos7880", "exynos5", 8, A64_BASE, listOf(c(8, 0x41, 0, 0xd03, 4, 1900, 546)),
           "Mali-T830", ARM_GPU, "14 nm", CpuinfoStyle.A64_K318,
           props = mapOf("ro.hardware" to "samsungexynos7880", "ro.boot.hardware" to "samsungexynos7880", "ro.product.board" to "universal7880",
               "ro.chipname" to "exynos7880", "ro.arch" to "exynos7880", "ro.opengles.version" to "196608")),
        ex("exynos8890", "Samsung Exynos 8890 (Exynos 8 Octa)", "exynos8890", "exynos5", 8, A64_BASE,
            listOf(c(4, 0x41, 0, 0xd03, 4, 1586, 442), c(4, 0x53, 1, 0x001, 1, 2600, 728)),
            "Mali-T880", ARM_GPU, "14 nm", CpuinfoStyle.A64_K318,
            props = mapOf("ro.hardware" to "samsungexynos8890", "ro.boot.hardware" to "samsungexynos8890", "ro.product.board" to "universal8890",
                "ro.chipname" to "exynos8890", "ro.arch" to "exynos8890", "ro.opengles.version" to "196610"),
            family = "Samsung Exynos", machine = "Samsung UNIVERSAL8890 board based on EXYNOS8890", socId = "EXYNOS8890", rev = "32"),
        ex("exynos7870", "Samsung Exynos 7870 (Exynos 7 Octa)", "exynos7870", "exynos5", 8, A64_BASE, listOf(c(8, 0x41, 0, 0xd03, 4, 1586, 546)),
            "Mali-T830", ARM_GPU, "14 nm", CpuinfoStyle.A64_K318,
            props = mapOf("ro.hardware" to "samsungexynos7870", "ro.boot.hardware" to "samsungexynos7870", "ro.product.board" to "universal7870",
                "ro.chipname" to "exynos7870", "ro.arch" to "exynos7870", "ro.opengles.version" to "196608")),
        ex("exynos7570", "Samsung Exynos 7570 (Exynos 7 Quad)", "exynos7570", "exynos5", 8, A64_BASE, listOf(c(4, 0x41, 0, 0xd03, 4, 1400, 546)),
            "Mali-T720", ARM_GPU, "14 nm", CpuinfoStyle.A64_K318,
            props = mapOf("ro.hardware" to "samsungexynos7570", "ro.boot.hardware" to "samsungexynos7570", "ro.product.board" to "universal7570",
                "ro.chipname" to "exynos7570", "ro.arch" to "exynos7570", "ro.opengles.version" to "196608")),
        // Exynos 8895 = Galaxy S8 SM-G950F (real dump, kernel 4.4, 32-bit view): A53 r0p4 x4 455-1690 MHz + Samsung M2 (implementer 0x53, variant 4, part 0x001, rev 0) x4 741-2314 MHz,
        // BogoMIPS 52.00, soc0 family / machine / soc_id / revision 17, ro.product.board universal8895, ro.chipname / ro.arch exynos8895, ro.hardware.egl mali, GLES 3.2.
        // The machine string is that phone's (DREAMLTE EUR rev06). NOT from the dump: ro.hardware / ro.boot.hardware (pattern of the 8890), GL_RENDERER, ro.soc.model.
        ex("exynos8895", "Samsung Exynos 8895 (Exynos 8 Octa)", "exynos8895", "exynos5", 8, A64_BASE,
            listOf(c(4, 0x41, 0, 0xd03, 4, 1690, 455), c(4, 0x53, 4, 0x001, 0, 2314, 741)),
            "Mali-G71", ARM_GPU, "10 nm", CpuinfoStyle.A64_K44, bogo = "52.00",
            props = mapOf("ro.hardware" to "samsungexynos8895", "ro.boot.hardware" to "samsungexynos8895", "ro.product.board" to "universal8895",
                "ro.chipname" to "exynos8895", "ro.arch" to "exynos8895", "ro.hardware.egl" to "mali", "ro.opengles.version" to "196610"),
            family = "Samsung Exynos", machine = "Samsung DREAMLTE EUR rev06 board based on EXYNOS8895", socId = "EXYNOS8895", rev = "17"),
        ex("exynos9810", "Samsung Exynos 9810 (Exynos 9 Octa)", "exynos9810", "exynos5", 8, EXY_A64_K49,
            listOf(c(4, 0x41, 0, 0xd05, 1, 1794, 455), c(4, 0x53, 1, 0x002, 0, 2704, 650)),
            "Mali-G72 MP18", ARM_GPU, "10 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "samsungexynos9810", "ro.boot.hardware" to "samsungexynos9810", "ro.product.board" to "universal9810",
                "ro.chipname" to "exynos9810", "ro.arch" to "exynos9810", "ro.hardware.egl" to "mali", "ro.opengles.version" to "196610")),
        // Exynos 7885 = Galaxy A8 (2018) SM-A530F (real dump, kernel 4.4, 32-bit view): A53 r0p4 x6 449-1586 MHz + A73 r0p2 x2 936-2184 MHz, BogoMIPS 52.00, soc0 revision 1,
        // ro.product.board universal7885, ro.chipname / ro.arch exynos7885, GLES 3.2. The machine string is that phone's (JACKPOTLTE EUR rev07). NOT from the dump: ro.hardware /
        // ro.boot.hardware (pattern of the 8890), GL_RENDERER, ro.soc.model.
        ex("exynos7885", "Samsung Exynos 7885 (Exynos 7 Octa)", "exynos7885", "exynos5", 8, A64_BASE,
            listOf(c(6, 0x41, 0, 0xd03, 4, 1586, 449), c(2, 0x41, 0, 0xd09, 2, 2184, 936)),
            "Mali-G71", ARM_GPU, "14 nm", CpuinfoStyle.A64_K44, bogo = "52.00",
            props = mapOf("ro.hardware" to "samsungexynos7885", "ro.boot.hardware" to "samsungexynos7885", "ro.product.board" to "universal7885",
                "ro.chipname" to "exynos7885", "ro.arch" to "exynos7885", "ro.opengles.version" to "196610"),
            family = "Samsung Exynos", machine = "Samsung JACKPOTLTE EUR rev07 board based on EXYNOS7885", socId = "EXYNOS7885", rev = "1"),
        ex("exynos9611", "Samsung Exynos 9611", "exynos9611", "exynos9611", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1742, 403), c(4, 0x41, 0, 0xd09, 2, 2314, 403)),
            "Mali-G72 MP3", ARM_GPU, "10 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "exynos9611", "ro.boot.hardware" to "exynos9611", "ro.opengles.version" to "196610")),
        ex("exynos850", "Samsung Exynos 850", "exynos850", "exynos850", 8, A64_V82, listOf(c(4, 0x41, 0, 0xd05, 1, 2002, 546), c(4, 0x41, 0, 0xd05, 1, 2002, 546)),
            "Mali-G52 MP1", ARM_GPU, "8 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "exynos850", "ro.boot.hardware" to "exynos850", "ro.opengles.version" to "196610")),
        ex("exynos2200", "Samsung Exynos 2200", "s5e9925", "s5e9925", 8, V9_PIXEL8, listOf(c(4, 0x41, 0, 0xd46, 2, 1820, 400), c(3, 0x41, 2, 0xd47, 0, 2520, 400), c(1, 0x41, 2, 0xd48, 0, 2803, 400)),
            "ANGLE (Samsung Xclipse 920) on Vulkan 1.3.231", SAMSUNG_GPU, "4 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "s5e9925", "ro.boot.hardware" to "s5e9925", "ro.opengles.version" to "196610")),
        ex("exynos2400", "Samsung Exynos 2400", "s5e9945", "s5e9945", 8, V9_PIXEL10,
            listOf(c(4, 0x41, 0, 0xd80, 1, 1960, 400), c(3, 0x41, 0, 0xd81, 1, 2590, 400), c(2, 0x41, 0, 0xd81, 1, 2900, 400), c(1, 0x41, 0, 0xd82, 1, 3207, 400)),
            "ANGLE (Samsung Xclipse 940) on Vulkan 1.3.279", SAMSUNG_GPU, "4 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "s5e9945", "ro.boot.hardware" to "s5e9945", "ro.opengles.version" to "196610")),
        // Exynos 9820: VERIFIED real S10 (beyond1) dump, kernel 4.14. cpu0-3 A55 (variant 1 rev 0), cpu4-5 A75 (variant 2 rev 1), cpu6-7 Samsung M4 (0x53 / 0x003).
        // Clocks 1950 / 2314 / 2730 are Samsung's; minimum clocks are the 9810 row's. No soc0 values (none known for this chip).
        ex("exynos9820", "Samsung Exynos 9820 (Exynos 9 Octa)", "exynos9820", "exynos5", 8, EXY_A64_K414,
            listOf(c(4, 0x41, 1, 0xd05, 0, 1950, 455), c(2, 0x41, 2, 0xd0a, 1, 2314, 741), c(2, 0x53, 1, 0x003, 0, 2730, 650)),
            "Mali-G76 MP12", ARM_GPU, "8 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "samsungexynos9820", "ro.boot.hardware" to "samsungexynos9820", "ro.product.board" to "universal9820",
                "ro.chipname" to "exynos9820", "ro.arch" to "exynos9820", "ro.hardware.egl" to "mali", "ro.opengles.version" to "196610")),
        // Exynos 9825 = Galaxy Note 10 (DERIVED from the S10 / Exynos 9820 dump: identical cores, kernel 4.14 layout). Clocks 2 x M4 2.73, 2 x A75 2.4, 4 x A55 1.95 GHz, Mali-G76 MP12, 7 nm EUV: CONFIRMED (AnandTech, SamMobile, Samsung's launch table).
        // VERIFIED (ianmacd/twrp_d2s BoardConfig, Note 10): ro.board.platform exynos5, board universal9825, androidboot.hardware exynos9825. The 9820 row's chipname / arch / egl pattern is reused (UNVERIFIED).
        ex("exynos9825", "Samsung Exynos 9825 (Exynos 9 Octa)", "exynos9825", "exynos5", 8, EXY_A64_K414,
           listOf(c(4, 0x41, 1, 0xd05, 0, 1950, 455), c(2, 0x41, 2, 0xd0a, 1, 2400, 741), c(2, 0x53, 1, 0x003, 0, 2730, 650)),
           "Mali-G76 MP12", ARM_GPU, "7 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
           props = mapOf("ro.hardware" to "exynos9825", "ro.boot.hardware" to "exynos9825", "ro.product.board" to "universal9825",
               "ro.chipname" to "exynos9825", "ro.arch" to "exynos9825", "ro.hardware.egl" to "mali", "ro.opengles.version" to "196610")),
        // Exynos 1280: VERIFIED real Galaxy A53 dump (kernel 5.10): 6 x A55 (variant 2 rev 0), 2 x A78 (variant 1 rev 1), BogoMIPS 52.00. Clocks 2.0 / 2.4 GHz are Samsung's;
        // minimum clocks, the s5e8825 platform name (pattern of the 2200 / 2400 rows) and GL_RENDERER are UNVERIFIED.
        ex("exynos1280", "Samsung Exynos 1280", "s5e8825", "s5e8825", 8, A64_V82,
            listOf(c(6, 0x41, 2, 0xd05, 0, 2000, 400), c(2, 0x41, 1, 0xd41, 1, 2400, 500)),
            "Mali-G68 MP4", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "s5e8825", "ro.boot.hardware" to "s5e8825", "ro.opengles.version" to "196610")),
        // Exynos 990 = Galaxy S20 SM-G980F (PARTLY VERIFIED): kernel 4.19.87, Features exactly "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp", BogoMIPS 52.00,
        // 442-2730 MHz, A76 CPUID 41.3.8.D0B.1 = r3p1 (CPU-Z validation pages), M5 = implementer 0x53 variant 1 part 0x004 revision 0 (Geekbench identifier), 4 x A55 2.0 / 2 x A76 2.5 / 2 x M5 2.73 GHz,
        // Build.HARDWARE "exynos990". UNVERIFIED: the A55's variant / revision (the 9820 dump's 1 / 0), minimum clocks of the A76 / M5, ro.soc.model "s5e9830" (Samsung's internal name, the pattern of the 2200's s5e9925), GL_RENDERER (Mali-G77 MP11).
        // CORRECTED after checking the Exynoobs x1s TWRP tree (stock-derived): TARGET_BOARD_PLATFORM universal990, TARGET_SOC / bootloader board name exynos9830, PRODUCT_PLATFORM exynos990.
        ex("exynos990", "Samsung Exynos 990", "s5e9830", "universal990", 8, A64_V82,
           listOf(c(4, 0x41, 1, 0xd05, 0, 2000, 442), c(2, 0x41, 3, 0xd0b, 1, 2500, 741), c(2, 0x53, 1, 0x004, 0, 2730, 650)),
           "Mali-G77 MP11", ARM_GPU, "7 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
           props = mapOf("ro.hardware" to "exynos990", "ro.boot.hardware" to "exynos990", "ro.product.board" to "exynos9830", "ro.opengles.version" to "196610")),
        // Exynos 1330 / 1380 (Galaxy A14 5G / A54 5G / A35): DERIVED from the Exynos 1280 dump row (same A55 variant 2 rev 0 and A78 variant 1 rev 1, kernel 5.10 layout). Samsung: 1380 = 4 x A78 2.4 + 4 x A55 2.0 GHz,
        // Mali-G68 MP5; 1330 = 2 x A78 2.4 + 6 x A55 2.0 GHz, Mali-G68 MP2; both 5 nm. Model numbers S5E8835 / S5E8535 are Samsung's (the 1280's S5E8825 pattern). UNVERIFIED: platform names, minimum clocks.
        ex("exynos1380", "Samsung Exynos 1380", "s5e8835", "s5e8835", 8, A64_V82,
           listOf(c(4, 0x41, 2, 0xd05, 0, 2000, 400), c(4, 0x41, 1, 0xd41, 1, 2400, 500)),
           "Mali-G68 MP5", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
           props = mapOf("ro.hardware" to "s5e8835", "ro.boot.hardware" to "s5e8835", "ro.opengles.version" to "196610")),
        ex("exynos1330", "Samsung Exynos 1330", "s5e8535", "s5e8535", 8, A64_V82,
           listOf(c(6, 0x41, 2, 0xd05, 0, 2000, 400), c(2, 0x41, 1, 0xd41, 1, 2400, 500)),
           "Mali-G68 MP2", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
           props = mapOf("ro.hardware" to "s5e8535", "ro.boot.hardware" to "s5e8535", "ro.opengles.version" to "196610")),
        // Exynos 2100: DERIVED. Pixel 6's real X1 (variant 1 rev 0) + the Exynos 1280 dump's A78 / A55 revisions; 4 x A55 2.2, 3 x A78 2.8, 1 x X1 2.9 GHz (Samsung).
        // The s5e9840 name and every minimum clock are UNVERIFIED.
        ex("exynos2100", "Samsung Exynos 2100", "s5e9840", "s5e9840", 8, A64_V82,
            listOf(c(4, 0x41, 2, 0xd05, 0, 2200, 400), c(3, 0x41, 1, 0xd41, 1, 2810, 500), c(1, 0x41, 1, 0xd44, 0, 2900, 500)),
            "Mali-G78 MP14", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "52.00",
            props = mapOf("ro.hardware" to "s5e9840", "ro.boot.hardware" to "s5e9840", "ro.opengles.version" to "196610")),
    )

    // ---- Spreadtrum / Unisoc and NVIDIA Tegra (added 2026-11-22) ------------------------------------------------------------
    // Oldest first inside each vendor. Sources and "unverified" marks: see the fifth-pass notes at the top.
    private fun vendorRows(): List<SocSpec> = listOf(
        // ---- NVIDIA Tegra
        //  id        label                          model   platform arch features                                                  clusters                                          GL_RENDERER     node   hw          bogo     gles   style
        tg("tegra2_t20", "NVIDIA Tegra 2 T20", "T20", "tegra", 7, "swp half thumb fastmult vfp edsp vfpv3 vfpv3d16",
            listOf(c(2, 0x41, 1, 0xc09, 0, 1000, 216)), "NVIDIA Tegra", "40 nm", hw = "ventana", bogo = "1998.84", gles = 131072, style = CpuinfoStyle.ARM_K30, serial = "3a7c0e1d5b92f604"),
        tg("tegra3_t30l", "NVIDIA Tegra 3 T30L", "T30L", "tegra3", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls",
            listOf(c(4, 0x41, 2, 0xc09, 9, 1200, 51)), "NVIDIA Tegra 3", "40 nm", hw = "grouper", bogo = "1993.93", gles = 131072, style = CpuinfoStyle.ARM_K30, serial = "1b5e0c3a7d4802f9"),
        tg("tegra4_t114", "NVIDIA Tegra 4 T114", "T114", "tegra", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt",
            listOf(c(4, 0x41, 2, 0xc0f, 2, 1900, 51)), "NVIDIA Tegra 4", "28 nm", hw = "tegratab", bogo = "1176.57", gles = 131072, style = CpuinfoStyle.ARM_K30, serial = "2c9d41e07a6b3158"),
        // arm64 kernels of the Nexus 9 / Pixel C: no Hardware line, no Serial; Denver adds its "MTS version" line
        tg("tegra_k1_t132", "NVIDIA Tegra K1 T132 (Denver)", "T132", "tegra132", 8, "fp asimd aes pmull sha1 sha2 crc32",
            listOf(c(2, 0x4e, 0, 0x000, 0, 2499, 204)), "NVIDIA Tegra", "28 nm", bogo = "24.00", gles = 196609, socId = "19", rev = "A02", hardwareLine = false,
            tail = mapOf("MTS version" to "33985182"),
            kernel = ArmKernel("denver_idle", "tegra", "interactive conservative ondemand powersave userspace performance", 300000, -1)),
        tg("tegra_x1_t210", "NVIDIA Tegra X1 T210", "T210", "tegra", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 1, 0xd07, 1, 1912, 51)), "NVIDIA Tegra", "20 nm", bogo = "none", gles = 196610, socId = "33", rev = "A02", hardwareLine = false,
            kernel = ArmKernel("arm_idle", "cpufreq-tegra210", "conservative ondemand userspace powersave interactive performance", 300000, 0)),
        // ---- Spreadtrum / Unisoc
        // SC6820: Cortex-A5, Android 2.3 / 4.0 on a 2.6.3x kernel. Hardware line, Features and BogoMIPS UNVERIFIED (no dump found).
        sp("sc6820", "Spreadtrum SC6820", "SC6820", "sc8810", 7, "swp half thumb fastmult vfp edsp vfpv3 vfpv3d16",
            listOf(c(1, 0x41, 0, 0xc05, 1, 1000, 200)), "Mali-400 MP", "ARM", "40 nm", hw = "sc8810", bogo = "1996.80", gles = 131072, style = CpuinfoStyle.ARM_K30),
        // SC7731E: Cortex-A7 r0p5 as on the SC773x dumps, Android 8.1 Go. Hardware line and ro.board.platform UNVERIFIED (platform by the sp9832e pattern).
        sp("sc7731e", "Unisoc SC7731E", "SC7731E", "sp7731e", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32",
            listOf(c(4, 0x41, 0, 0xc07, 5, 1300, 768)), "Mali-T820", "ARM", "28 nm", hw = "Spreadtrum SC7731e", bogo = "2603.41", gles = 196610,
            board = "sp7731e_1h10", legacy = true, style = CpuinfoStyle.ARM32),
        // SC7730SE = Galaxy Grand Prime VE SM-G531H, SC7727SE = Galaxy J1 (2016) SM-J120H, SC7730S = Galaxy Tab 3 Lite SM-T113 (all VERIFIED, real dumps pytorch/cpuinfo galaxy-grand-prime-value-edition /
        // galaxy-j1-2016 / galaxy-tab-3-lite, kernel 3.10): "Processor" header + per-cpu blocks (ARM32 layout), 4 x A7 r0p5, Hardware "sc8830", Revision 0004 / 0005 / 0004, 768 MHz minimum, scaling_driver "sprd",
        // governors "interactive performance", sc_cpuidle, physical_package_id 15, ro.chipname / ro.product.board = the chip name, ro.board.platform sc8830, GLES 2.0. BogoMIPS is cpu0's value of each dump (boot clock).
        // The Tab 3 Lite's Features lack vfpd32. GPU: a Mali-400 (ARM's renderer string for every Mali-400 is "Mali-400 MP"; SC8830-family / SC7727SE Mali-400 and the 28 nm node confirmed by Spreadtrum's press releases, Notebookcheck and SamMobile). NOT from the dump: ro.hardware.
        sp("sc7730se", "Spreadtrum SC7730SE", "SC7730SE", "sc8830", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32", listOf(c(4, 0x41, 0, 0xc07, 5, 1300, 768)),
           "Mali-400 MP", ARM_GPU, "28 nm", hw = "sc8830", bogo = "2603.41", gles = 131072, legacy = true, style = CpuinfoStyle.ARM32, hwRev = "0004",
           props = mapOf("ro.chipname" to "SC7730SE", "ro.product.board" to "SC7730SE"), kernel = ArmKernel("sc_cpuidle", "sprd", "interactive performance", 50000, 15)),
        sp("sc7727se", "Spreadtrum SC7727SE", "SC7727SE", "sc8830", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32", listOf(c(4, 0x41, 0, 0xc07, 5, 1200, 768)),
           "Mali-400 MP", ARM_GPU, "28 nm", hw = "sc8830", bogo = "2413.36", gles = 131072, legacy = true, style = CpuinfoStyle.ARM32, hwRev = "0005",
           props = mapOf("ro.chipname" to "SC7727SE", "ro.product.board" to "SC7727SE"), kernel = ArmKernel("sc_cpuidle", "sprd", "interactive performance", 50000, 15)),
        sp("sc7730s", "Spreadtrum SC7730S", "SC7730S", "sc8830", 7, "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt", listOf(c(4, 0x41, 0, 0xc07, 5, 1300, 768)),
           "Mali-400 MP", ARM_GPU, "28 nm", hw = "sc8830", bogo = "1538.01", gles = 131072, legacy = true, style = CpuinfoStyle.ARM32, hwRev = "0004",
           props = mapOf("ro.chipname" to "sc7730s", "ro.product.board" to "sc7730s"), kernel = ArmKernel("sc_cpuidle", "sprd", "interactive performance", 100000, 15)),
        sp("sc9863a", "Unisoc SC9863A", "SC9863A", "sp9863a", 8, A64_V82,
            listOf(c(4, 0x41, 0, 0xd05, 0, 1200, 768), c(4, 0x41, 0, 0xd05, 0, 1600, 768)), "PowerVR Rogue GE8322", IMG, "28 nm", hw = "UNISOC SC9863A", bogo = "52.00", gles = 196610,
            board = "s9863a1h10", kernel = ArmKernel(dtModel = "Spreadtrum SC9863A-1H10 Board", dtCompatible = listOf("sprd,sp9863a-1h10", "sprd,sc9863a"))),
        sp("ums9230_t606", "Unisoc Tiger T606", "T606", "ums9230", 8, A64_V82,
            listOf(c(6, 0x41, 0, 0xd05, 0, 1612, 614), c(2, 0x41, 0, 0xd0a, 0, 1612, 768)), "Mali-G57 MC1", ARM_GPU, "12 nm", hw = "Unisoc ums9230", bogo = "52.00", gles = 196610,
            board = "ums9230_1h10", kernel = ArmKernel(dtModel = "Spreadtrum UMS9230 1H10 Board", dtCompatible = listOf("sprd,ums9230-1h10"))),
        // T7520 = UMS9620 silicon (4 x A55, 3 + 1 x A76); Unisoc never published T7520 clocks, these are the UMS9620 datasheet ones
        sp("ums9620_t7520", "Unisoc Tiger T7520", "T7520", "ums9620", 8, A64_V82,
            listOf(c(4, 0x41, 0, 0xd05, 0, 2000, 500), c(3, 0x41, 0, 0xd0b, 0, 2200, 500), c(1, 0x41, 0, 0xd0b, 0, 2200, 500)), "Mali-G57 MC4", ARM_GPU, "6 nm", hw = "Unisoc ums9620", bogo = "52.00", gles = 196610,
            board = "ums9620_2h10", kernel = ArmKernel(dtModel = "Spreadtrum UMS9620 2H10 Board", dtCompatible = listOf("sprd,ums9620-2h10"))),
        // ---- Marvell (added 2026-11-27)
        // PXA986 = Galaxy Tab 3 7.0 SM-T210L (VERIFIED, real dump pytorch/cpuinfo galaxy-tab-3-7.0, kernel 3.4, Android 4.1.2): ARM_K30 layout, 2 x Cortex-A9 variant 3 part 0xc09 rev 0, Features
        // "swp half thumb fastmult vfp edsp thumbee neon vfpv3 tls ", BogoMIPS 313.53 (the 312 MHz boot clock), Hardware "PXA988", Revision 0007, 312-1205 MHz (pxa988-cpufreq, pxa988_idle, latency 10000,
        // governors "interactive conservative ondemand powersave userspace performance"), ro.board.platform mrvl, ro.product.board PXA986, physical_package_id 0. No soc0 / ro.hardware.chipname (see NO_SOC0).
        // Dual Cortex-A9 at 1.2 GHz + Vivante GC1000 confirmed (Marvell / Vivante press releases, Notebookcheck). NOT confirmed, so left blank: the GL_RENDERER / GL_VENDOR strings (the phone's own are used) and the process node.
        ot("pxa986", "Marvell PXA986", "Marvell", "PXA986", "mrvl", "PXA988", 7, "swp half thumb fastmult vfp edsp thumbee neon vfpv3 tls", listOf(c(2, 0x41, 3, 0xc09, 0, 1205, 312)),
           "", "", "", CpuinfoStyle.ARM_K30, "313.53", hwRev = "0007", props = mapOf("ro.product.board" to "PXA986"),
           kernel = ArmKernel("pxa988_idle", "pxa988-cpufreq", "interactive conservative ondemand powersave userspace performance", 10000, 0)),
        // ---- HiSilicon Kirin / Xiaomi XRING / JLQ / TI OMAP (added 2026-11-23; sources and VERIFIED / UNVERIFIED marks: see the ninth-pass notes at the top)
        // Kirin 980: complete real dump (Mate 20 HMA-L29, kernel 4.9). 4 x A55, then 2 + 2 A76 with HiSilicon's implementer 0x48 / part 0xd40.
        ot("kirin980", "HiSilicon Kirin 980", "HiSilicon", "Kirin980", "kirin980", "", 8, EXY_A64_K49,
            listOf(c(4, 0x41, 1, 0xd05, 0, 1805, 830), c(2, 0x48, 1, 0xd40, 0, 1901, 826), c(2, 0x48, 1, 0xd40, 0, 2600, 1460)),
            "Mali-G76", ARM_GPU, "7 nm", CpuinfoStyle.A64_K49, bogo = "3.84",
            props = mapOf("ro.hardware" to "kirin980", "ro.boot.hardware" to "kirin980", "ro.board.chiptype" to "kirin980_cs", "ro.opengles.version" to "196610"),
            kernel = ArmKernel("hisi_middle_cluster_idle", "cpufreq-dt", "interactive conservative ondemand userspace powersave performance schedutil", 2000000, packageIdPerCluster = true)),

        // ---- Google Tensor (added 2026-11-25; see the eleventh-pass notes at the top for what is VERIFIED / DERIVED / UNVERIFIED)
        ot("tensor_g1", "Google Tensor G1 (GS101)", "Google", "Tensor", "gs101", "", 8, A64_V82,
            listOf(c(4, 0x41, 2, 0xd05, 0, 1803, 300), c(2, 0x41, 4, 0xd0b, 0, 2253, 400), c(2, 0x41, 1, 0xd44, 0, 2802, 500)),
            "Mali-G78", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "49.15",
            props = mapOf("ro.boot.hardware.platform" to "gs101", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "psci_idle", packageId = 0)),
        ot("tensor_g2", "Google Tensor G2 (GS201)", "Google", "GS201", "gs201", "", 8, A64_V82,
            listOf(c(4, 0x41, 2, 0xd05, 0, 1803, 300), c(2, 0x41, 1, 0xd41, 1, 2348, 500), c(2, 0x41, 1, 0xd44, 0, 2850, 500)),
            "Mali-G710", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "49.15",
            props = mapOf("ro.boot.hardware.platform" to "gs201", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "psci_idle", packageId = 0)),
        ot("tensor_g3", "Google Tensor G3 (Zuma)", "Google", "Tensor G3", "zuma", "", 8, V9_PIXEL8,
            listOf(c(4, 0x41, 1, 0xd46, 1, 1704, 324), c(4, 0x41, 1, 0xd4d, 0, 2367, 402), c(1, 0x41, 1, 0xd4e, 0, 2914, 500)),
            "Mali-G715", ARM_GPU, "4 nm", CpuinfoStyle.A64_K49, bogo = "49.15",
            props = mapOf("ro.boot.hardware.platform" to "zuma", "ro.opengles.version" to "196610"),
            kernel = ArmKernel("psci_idle", "exynos_cpufreq", "sched_pixel conservative powersave performance schedutil", 5000000, 0)),
        ot("tensor_g4", "Google Tensor G4 (Zuma Pro)", "Google", "Tensor G4", "zumapro", "", 8, V9_PIXEL8,
            listOf(c(4, 0x41, 0, 0xd80, 1, 1950, 324), c(3, 0x41, 0, 0xd81, 1, 2600, 402), c(1, 0x41, 0, 0xd82, 1, 3105, 500)),
            "Mali-G715", ARM_GPU, "4 nm", CpuinfoStyle.A64_K49, bogo = "49.15",
            props = mapOf("ro.boot.hardware.platform" to "zumapro", "ro.opengles.version" to "196610"),
            kernel = ArmKernel("psci_idle", "exynos_cpufreq", "sched_pixel conservative powersave performance schedutil", 5000000, 0)),
        ot("tensor_g5", "Google Tensor G5 (Laguna)", "Google", "Tensor G5", "laguna", "", 8, V9_PIXEL10,
            listOf(c(2, 0x41, 0, 0xd80, 1, 2250, 400), c(5, 0x41, 0, 0xd87, 1, 3050, 500), c(1, 0x41, 0, 0xd82, 1, 3780, 500)),
            "PowerVR D-Series DXT-48-1536", IMG, "3 nm", CpuinfoStyle.A64_K49, bogo = "76.80",
            props = mapOf("ro.boot.hardware.platform" to "laguna", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "psci_idle", packageId = 0, dtModel = "FRANKEL MP based on LGA", dtCompatible = listOf("google,lga-frankel", "google,lga"))),

        // ---- HiSilicon Kirin, older chips (added 2026-11-25)
        // Kirin 910T: Ascend P7 dump (VERIFIED). Kirin 910 proper is 1.6 GHz; the dump is the 1.8 GHz "T".
        ot("kirin910t", "HiSilicon Kirin 910T", "HiSilicon", "Kirin910T", "hi6620oem", "hi6620oem", 7, "swp half thumb fastmult vfp edsp thumbee neon vfpv3",
            listOf(c(4, 0x41, 4, 0xc09, 1, 1795, 208)), "Mali-450 MP4", ARM_GPU, "28 nm", CpuinfoStyle.ARM_K30, bogo = "3577.00", hwRev = "0000", serial = "0000000000000000",
            props = mapOf("ro.opengles.version" to "131072"), legacy = true,
            kernel = ArmKernel(cpuidleDriver = "cpuidle", cpufreqDriver = "k3v2cpufreq", governors = "pwrctrl_hotplug", transitionLatencyNs = 1000, packageId = -1)),
        // Kirin 620: P8 Lite dump (VERIFIED): the HiSilicon 3.10 layout, A53 r0p3.
        ot("kirin620", "HiSilicon Kirin 620", "HiSilicon", "Kirin620", "hi6210sft", "hi6210sft", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 3, 1200, 208), c(4, 0x41, 0, 0xd03, 3, 1200, 208)), "Mali-450 MP4", ARM_GPU, "28 nm", CpuinfoStyle.A64_K310H, bogo = "",
            props = mapOf("ro.opengles.version" to "131072"), legacy = true,
            kernel = ArmKernel(cpuidleDriver = "arm64_idle", cpufreqDriver = "hi6xxx-smp", governors = "optdemand performance", packageIdPerCluster = true)),
        // Kirin 650: P9 Lite dump (VERIFIED): A53 r0p4, 480-1709 MHz and 1402-2016 MHz clusters.
        ot("kirin650", "HiSilicon Kirin 650", "HiSilicon", "Kirin650", "hi6250", "hi6250", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1709, 480), c(4, 0x41, 0, 0xd03, 4, 2016, 1402)), "Mali-T830 MP2", ARM_GPU, "16 nm", CpuinfoStyle.A64_K310H, bogo = "",
            props = mapOf("ro.opengles.version" to "196609"), legacy = true,
            kernel = ArmKernel(cpuidleDriver = "hisi_little_cluster_idle", cpufreqDriver = "arm-big-little", governors = "interactive conservative ondemand userspace powersave performance", packageIdPerCluster = true)),
        // Kirin 659: DERIVED from the 650 (the 655 / 658 / 659 all report hi6250); 2.36 / 1.70 GHz, EMUI 8 kernel layout of the 970 dump. Minimum clocks UNVERIFIED.
        ot("kirin659", "HiSilicon Kirin 659", "HiSilicon", "Kirin659", "hi6250", "", 8, A64_BASE,
            listOf(c(4, 0x41, 0, 0xd03, 4, 1700, 480), c(4, 0x41, 0, 0xd03, 4, 2360, 1402)), "Mali-T830 MP2", ARM_GPU, "16 nm", CpuinfoStyle.A64_K44, bogo = "3.84",
            props = mapOf("ro.hardware" to "hi6250", "ro.boot.hardware" to "hi6250", "ro.opengles.version" to "196609"),
            kernel = ArmKernel(cpuidleDriver = "hisi_little_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        // Kirin 950: Mate 8 dump (VERIFIED): GENERIC layout with the "Processor" header, A53 r0p4 + A72 r0p0. Platform hi3650 UNVERIFIED (generic build.prop in the dump).
        ot("kirin950", "HiSilicon Kirin 950", "HiSilicon", "Kirin950", "hi3650", "", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1805, 480), c(4, 0x41, 0, 0xd08, 0, 2304, 480)), "Mali-T880 MP4", ARM_GPU, "16 nm", CpuinfoStyle.GENERIC, bogo = "3.84",
            props = mapOf("ro.hardware" to "hi3650", "ro.boot.hardware" to "hi3650", "ro.opengles.version" to "196609"), legacy = true,
            kernel = ArmKernel(cpuidleDriver = "hisi_big_cluster_idle", cpufreqDriver = "arm-big-little", governors = "interactive conservative ondemand userspace powersave performance", packageIdPerCluster = true)),
        // Kirin 960: Mate 9 dump (VERIFIED): A53 r0p4 533-1844 + A73 r0p1 903-2362. Platform hi3660 UNVERIFIED.
        ot("kirin960", "HiSilicon Kirin 960", "HiSilicon", "Kirin960", "hi3660", "", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1844, 533), c(4, 0x41, 0, 0xd09, 1, 2362, 903)), "Mali-G71 MP8", ARM_GPU, "16 nm", CpuinfoStyle.GENERIC, bogo = "3.84",
            props = mapOf("ro.hardware" to "hi3660", "ro.boot.hardware" to "hi3660", "ro.opengles.version" to "196609"), legacy = true,
            kernel = ArmKernel(cpuidleDriver = "hisi_big_cluster_idle", cpufreqDriver = "arm-big-little", governors = "interactive conservative ondemand userspace powersave performance", packageIdPerCluster = true)),
        // Kirin 970: Mate 10 / P20 Pro dumps (VERIFIED): K44 layout, A53 r0p4 509-1844 + A73 r0p2 682-2362. Platform hi3670 UNVERIFIED.
        ot("kirin970", "HiSilicon Kirin 970", "HiSilicon", "Kirin970", "hi3670", "", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1844, 509), c(4, 0x41, 0, 0xd09, 2, 2362, 682)), "Mali-G72 MP12", ARM_GPU, "10 nm", CpuinfoStyle.A64_K44, bogo = "3.84",
            props = mapOf("ro.hardware" to "hi3670", "ro.boot.hardware" to "hi3670", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "hisi_little_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        // Kirin 710: DERIVED from the 970 dump (A73 r0p2 + A53 r0p4), 2.2 / 1.7 GHz. Minimum clocks and the kirin710 platform are UNVERIFIED.
        ot("kirin710", "HiSilicon Kirin 710", "HiSilicon", "Kirin710", "kirin710", "", 8, A64_BASE,
            listOf(c(4, 0x41, 0, 0xd03, 4, 1700, 509), c(4, 0x41, 0, 0xd09, 2, 2200, 682)), "Mali-G51 MP4", ARM_GPU, "12 nm", CpuinfoStyle.A64_K44, bogo = "3.84",
            props = mapOf("ro.hardware" to "kirin710", "ro.boot.hardware" to "kirin710", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "hisi_little_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        // Kirin 810 / 990 / 9000: DERIVED from the Kirin 980 dump (same K49 layout, HiSilicon-labelled A76 = impl 0x48 part 0xd40 variant 1 rev 0; the 9000's A77 is part 0xd41).
        // Clocks are Huawei's; minimum clocks, platform names and Features (V82 words of a 4.14 kernel) are UNVERIFIED.
        ot("kirin810", "HiSilicon Kirin 810", "HiSilicon", "Kirin810", "kirin810", "", 8, A64_V82,
            listOf(c(6, 0x41, 1, 0xd05, 0, 1880, 500), c(2, 0x48, 1, 0xd40, 0, 2270, 800)), "Mali-G52 MP6", ARM_GPU, "7 nm", CpuinfoStyle.A64_K49, bogo = "3.84",
            props = mapOf("ro.hardware" to "kirin810", "ro.boot.hardware" to "kirin810", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "hisi_middle_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance schedutil", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        ot("kirin990", "HiSilicon Kirin 990", "HiSilicon", "Kirin990", "kirin990", "", 8, A64_V82,
            listOf(c(4, 0x41, 1, 0xd05, 0, 1950, 500), c(2, 0x48, 1, 0xd40, 0, 2360, 800), c(2, 0x48, 1, 0xd40, 0, 2860, 1000)), "Mali-G76 MP16", ARM_GPU, "7 nm", CpuinfoStyle.A64_K49, bogo = "3.84",
            props = mapOf("ro.hardware" to "kirin990", "ro.boot.hardware" to "kirin990", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "hisi_middle_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance schedutil", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        ot("kirin9000", "HiSilicon Kirin 9000", "HiSilicon", "Kirin9000", "kirin9000", "", 8, A64_V82,
            listOf(c(4, 0x41, 1, 0xd05, 0, 2050, 500), c(3, 0x48, 1, 0xd41, 0, 2540, 800), c(1, 0x48, 1, 0xd41, 0, 3130, 1000)), "Mali-G78 MP24", ARM_GPU, "5 nm", CpuinfoStyle.A64_K49, bogo = "3.84",
            props = mapOf("ro.hardware" to "kirin9000", "ro.boot.hardware" to "kirin9000", "ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "hisi_middle_cluster_idle", cpufreqDriver = "cpufreq-dt", governors = "interactive conservative ondemand userspace powersave performance schedutil", transitionLatencyNs = 2000000, packageIdPerCluster = true)),
        // Xiaomi Surge S1: Mi 5C dump (VERIFIED): 8 x A53 r0p4 in two clusters (the dump shows 6 cpus online), BogoMIPS 52.16, K44. The two clusters have different
        // transition latencies on the phone (65536 / 78536 ns); one value fits here.
        ot("xiaomi_surge_s1", "Xiaomi Surge S1", "Xiaomi", "Surge S1", "song", "", 8, "fp asimd evtstrm aes pmull sha1 sha2 crc32",
            listOf(c(4, 0x41, 0, 0xd03, 4, 1404, 461), c(4, 0x41, 0, 0xd03, 4, 2158, 1843)), "Mali-T860 MP4", ARM_GPU, "28 nm", CpuinfoStyle.A64_K44, bogo = "52.16",
            props = mapOf("ro.opengles.version" to "196610"),
            kernel = ArmKernel(cpuidleDriver = "arm64_idle", cpufreqDriver = "song-cpufreq", governors = "ondemand userspace powersave interactive performance", transitionLatencyNs = 65536, packageIdPerCluster = true)),
        // JR510: real dump of a late-PVT Poco C40 (frost, Android 11, test-keys build): /proc/cpuinfo, getprop and SurfaceFlinger's GLES line.
        // Features has NO crypto words (no aes / pmull / sha1 / sha2) and the kernel prints "Hardware : JLQ JR510" LAST, which the K49 layout
        // would not print on its own, so it goes in through the cpuinfo tail. Clocks (1500 / 2000 MHz) are Geekbench's; minimum clocks are not from any dump.
        ot("jlq_jr510", "JLQ JR510", "JLQ", "JR510", "jr510", "", 8, A64_V82_NOCRYPTO,
            listOf(c(4, 0x41, 2, 0xd05, 0, 1500, 500), c(4, 0x41, 2, 0xd05, 0, 2000, 500)),
            "Mali-G52", ARM_GPU, "11 nm", CpuinfoStyle.A64_K49, bogo = "38.40",
            props = mapOf("ro.hardware" to "jlq", "ro.boot.hardware" to "jlq", "ro.hardware.egl" to "mali", "ro.hardware.vulkan" to "pastel",
                "ro.opengles.version" to "196610"),
            tail = mapOf("Hardware" to "JLQ JR510")),
        // XRING O1: layout from Geekbench (Xiaomi 25042PN24C), Features = real X925 / A725 printout; platform unknown
        ot("xring_o1", "Xiaomi XRING O1", "Xiaomi", "XRING O1", "", "", 8, V9_PIXEL10,
            listOf(c(2, 0x41, 0, 0xd80, 1, 1800, 500), c(2, 0x41, 0, 0xd87, 1, 1890, 500), c(4, 0x41, 0, 0xd87, 1, 3400, 500), c(2, 0x41, 0, 0xd85, 1, 3900, 500)),
            "Immortalis-G925", ARM_GPU, "3 nm", CpuinfoStyle.A64_K49, bogo = "38.40",
            props = mapOf("ro.product.board" to "O1_asic", "ro.opengles.version" to "196610")),
        // OMAP4430: the Pandaboard dump (Hardware, Revision, Serial, BogoMIPS, Features all as printed)
        ot("omap4430", "Texas Instruments OMAP4430", "Texas Instruments", "OMAP4430", "omap4", "OMAP4 Panda board", 7, "swp half thumb fastmult vfp edsp thumbee neon vfpv3 tls",
            listOf(c(2, 0x41, 1, 0xc09, 2, 1000, 300)), "PowerVR SGX 540", IMG, "45 nm", CpuinfoStyle.ARM_K30, bogo = "599.22", hwRev = "0020", serial = "0000000000000000",
            props = mapOf("ro.opengles.version" to "131072"), legacy = true),
        // OMAP3630: UNVERIFIED (no dump); A8 r1p3 per TI
        ot("omap3630", "Texas Instruments OMAP3630", "Texas Instruments", "OMAP3630", "omap3", "OMAP3 Beagle Board", 7, "swp half thumb fastmult vfp edsp thumbee neon vfpv3 tls",
            listOf(c(1, 0x41, 1, 0xc08, 3, 1000, 300)), "PowerVR SGX 530", IMG, "45 nm", CpuinfoStyle.ARM_K30, bogo = "994.65", hwRev = "0020", serial = "0000000000000000",
            props = mapOf("ro.opengles.version" to "131072"), legacy = true),
    )

    // ---- per-chip data that is not a row column ---------------------------------------------------------------------
    /** soc0/foundry_id (1 TSMC, 3 Samsung) from the "Fab" column of the Wikipedia list; absent = several or other foundries */
    private val FAB = mapOf("msm8225q" to 1, "msm8210" to 1, "msm8909" to 1, "msm8226" to 1, "msm8974" to 1, "msm8916" to 1, "msm8917" to 1, "msm8940" to 1, "sdm439" to 1, "sdm450" to 3, "msm8952" to 1, "msm8953" to 3, "sdm636" to 3, "sm6115" to 3, "sm4250" to 3, "sm6225" to 1, "sm6375" to 1, "sm4350_480" to 3, "sm4350" to 3, "sm4635" to 3, "sm6475" to 3, "sm7125" to 3, "sm7150" to 3, "sm7325" to 1, "sm7325p" to 1, "sm7475" to 1, "sm7635_gen3" to 1, "sm7635" to 1, "msm8992" to 1, "msm8994" to 1, "msm8996" to 3, "msm8996l" to 3, "msm8998" to 3, "sdm845" to 3, "sm8150" to 1, "sm8250" to 1, "sm8350" to 3, "sm8350ac" to 3, "sm8450" to 3, "sm8450_2" to 3, "sm8450_3" to 3, "sm8475" to 1, "sm8475_2" to 1, "sm8550" to 1, "sm8550p" to 1, "sm8650" to 1, "sm8650p" to 1, "sm8735" to 1, "sm8750" to 1, "msm8625q" to 1, "msm8610" to 1, "msm8212" to 1, "msm8612" to 1, "msm8208" to 1, "msm8905" to 1, "apq8026" to 1, "msm8926" to 1, "msm8626" to 1, "apq8028" to 1, "msm8228" to 1, "msm8628" to 1, "msm8928" to 1, "msm8930aa" to 1, "msm8930ab" to 1, "apq8064" to 1, "apq8064t" to 1, "sm8250ac" to 1, "apq8064ab" to 1, "apq8084" to 1, "qm215" to 1, "apq8016" to 1, "msm8936" to 1, "msm8939" to 1, "msm8929" to 1, "msm8920" to 1, "msm8937" to 1, "sdm429" to 1, "msm8956" to 1, "msm8976" to 1, "sdm630" to 3, "sdm632" to 3, "sdm660" to 3, "sm6125" to 3, "sdm670" to 3, "sdm710" to 3, "sdm712" to 3, "sm6150" to 3, "sdm850" to 3, "sm7250" to 3, "sm6350" to 3, "sm7225" to 3, "sm4375" to 1, "sm7450" to 3, "sm4450" to 3, "sm6450" to 3, "sm7550" to 1, "sm7675" to 1, "sm8635" to 1, "sm6650" to 1, "sm7750" to 1, "sm8850" to 1, "sm8950" to 1, "sm8975" to 1)
    /** top GPU clock in MHz from the same list, for /sys/class/kgsl */
    private val GPU_MHZ = mapOf("qsd8250" to 226, "msm8255" to 266, "msm8260" to 266, "msm8225" to 320, "msm8225q" to 400, "msm8960" to 400, "msm8210" to 400, "msm8909" to 400, "msm8226" to 400, "msm8974" to 450, "msm8916" to 400, "msm8917" to 598, "msm8940" to 450, "sdm439" to 650, "sdm450" to 600, "msm8952" to 550, "msm8953" to 650, "sdm636" to 430, "sm6115" to 950, "sm4250" to 600, "sm6225" to 1114, "sm6375" to 840, "sm4350_480" to 650, "sm4350" to 650, "sm6475" to 940, "sm7125" to 750, "sm7150" to 800, "sm7325" to 550, "sm7325p" to 608, "sm7475" to 580, "sm7635_gen3" to 1050, "sm7635" to 1150, "msm8992" to 600, "msm8994" to 600, "msm8996" to 624, "msm8996l" to 510, "msm8998" to 670, "sdm845" to 710, "sm8150" to 585, "sm8250" to 587, "sm8350" to 840, "sm8450" to 818, "sm8450_2" to 818, "sm8450_3" to 818, "sm8475" to 900, "sm8475_2" to 900, "sm8550" to 680, "sm8550p" to 719, "sm8650" to 903, "sm8650p" to 1000, "sm8735" to 1150, "sm8750" to 1100, "msm8660" to 266, "msm8625" to 320, "msm8625q" to 400, "msm8610" to 400, "msm8212" to 400, "msm8612" to 400, "msm8208" to 400, "msm8905" to 400, "apq8026" to 400, "msm8926" to 400, "msm8626" to 400, "apq8028" to 450, "msm8228" to 450, "msm8628" to 450, "msm8928" to 450, "msm8930" to 400, "msm8930aa" to 400, "msm8930ab" to 450, "apq8064" to 400, "apq8064t" to 400, "sm8250ac" to 587, "sm8350ac" to 840, "apq8064ab" to 450, "apq8084" to 600, "qm215" to 485, "apq8016" to 400, "msm8936" to 550, "msm8939" to 550, "msm8929" to 465, "msm8920" to 598, "msm8937" to 450, "sdm429" to 320, "msm8956" to 600, "msm8976" to 600, "sdm630" to 650, "sdm632" to 725, "sdm660" to 647, "sm6125" to 950, "sdm670" to 430, "sdm710" to 504, "sdm712" to 610, "sm6150" to 845, "sdm850" to 710, "sm7250" to 625, "sm6350" to 565, "sm7225" to 800, "sm4375" to 700, "sm7450" to 443, "sm4450" to 955, "sm6450" to 676, "sm7550" to 975, "sm7675" to 950, "sm8635" to 1100, "sm6650" to 895, "sm7750" to 1150, "sm8850" to 1200, "sm8950" to 1250, "sm8975" to 1450, "msm7227" to 226, "msm7627" to 226, "qsd8650" to 226, "msm7230" to 266, "msm7630" to 266, "apq8055" to 266, "msm8655" to 266, "msm8255t" to 266, "msm8655t" to 266, "apq8060" to 266, "msm7225a" to 245, "msm7625a" to 245, "msm7227a" to 245, "msm7627a" to 245, "msm7225ab" to 245, "msm8227" to 320, "msm8627" to 320)
    /** platforms whose reference kernel is 4.9 or older: they print the "Processor" line and soc0/chip_name */
    private val LEGACY = setOf("msm7x27a", "msm8660", "msm8960", "msm8974", "msm8226", "msm8610", "apq8084", "msm8909", "msm8916", "msm8937", "msm8953",
        "msm8952", "msm8976", "msm8992", "msm8994", "msm8996", "msm8998", "sdm660", "sdm845", "sdm710", "qm215", "msm7x30", "qsd8k", "msm7x27")
    /** MediaTek chips whose reference kernel is 4.9 or older (3.18 / 4.4 / 4.9); Helio P65 / G85 and everything newer launched on 4.14+ */
    private val MTK_LEGACY = setOf("mt6580", "mt6582", "mt6592", "mt6735", "mt6755", "mt6797", "mt6757", "mt6763", "mt6758", "mt6799",
        "mt6739", "mt6761", "mt6762", "mt6765", "mt6771", "mt6771_p70", "mt6757_p25", "mt6765_g35", "mt6797_x25", "mt6762_g25",
        "mt6595", "mt6752", "mt6750", "mt8167b", "mt6753", "mt6732")
    /** Samsung chips whose reference kernel is 4.9 or older (2.6.35 / 3.0 / 3.4 / 3.10 / 3.18 / 4.9) */
    private val EXY_LEGACY = setOf("exynos3110", "exynos3470", "exynos4212", "exynos4412", "exynos5260", "exynos5420", "exynos7420", "exynos7570", "exynos7870", "exynos8890", "exynos9810", "exynos5250", "exynos7580", "exynos7885", "exynos8895", "exynos7578", "exynos7880")
    /** platforms whose Qualcomm reference board is a QRD rather than an MTP */
    private val QRD = setOf("msm7x27a", "msm8610", "msm8226", "msm8909", "msm8916", "msm8937", "msm8953", "msm8952", "qm215", "bengal", "holi", "pitti")

    private fun finish(s: SocSpec): SocSpec = when (s.manufacturer) {
        QTI -> s.copy(
            legacyKernel = s.platform in LEGACY, hwPlatform = if (s.platform in QRD) "QRD" else "MTP",
            foundryId = FAB[s.id] ?: -1, gpuMaxMhz = GPU_MHZ[s.id] ?: 0,
        )
        "MediaTek" -> s.copy(legacyKernel = s.id in MTK_LEGACY)
        "Samsung" -> s.copy(legacyKernel = s.id in EXY_LEGACY)
        else -> s   // NVIDIA / UNISOC rows set legacyKernel themselves
    }

    /** every ARM preset (Qualcomm, MediaTek, Samsung, NVIDIA, Unisoc), finished with the data above */
    val arm: List<SocSpec> = (armRows() + vendorRows()).map(::finish)

    // Generic OEM / retail-board DMI strings; serials and the UUID are zeros (added in SocProfile.x86Files). Strings are the common
    // defaults of these boards' firmware, not one machine's.
    private val BAY_TRAIL_DMI = X86Dmi("Insyde", "BayTrail", "0.1", "", "", "Insyde", "BYT-T FFD8", "0.1", "Insyde Corp.", "BYT70.X64.0001", "08/12/2015", "",
        "Insyde", "0.1", 30)
    private val CHERRY_TRAIL_DMI = X86Dmi("Insyde", "CherryTrail", "0.1", "", "", "Insyde", "Cherry Trail CR", "0.1", "Insyde Corp.", "CHT.X64.0001", "06/03/2016", "",
        "Insyde", "0.1", 30)
    private val DIY_BOARD_DMI = X86Dmi("System manufacturer", "System Product Name", "System Version", "", "SKU", "ASUSTeK COMPUTER INC.", "PRIME B460M-A", "Rev X.0x",
        "American Megatrends Inc.", "1405", "10/22/2020", "5.17", "Default string", "Default string", 3)

    // ---- x86 and x86_64 -------------------------------------------------------------------------------------------
    // only offered to x86 / x86_64 firmware, which is never offered the ARM rows above
    val x86: List<SocSpec> = listOf(
        // No ro.board.platform here: an x86 firmware finds its graphics / camera libraries by the platform name it was built with
    //    id         label                    brand string                                       cpuid-model step L2-KB cores threads maxMHz flags  phys virt  gpu vendor / GL_RENDERER   node
        atom("z2460", "Intel Atom Z2460",   "Intel(R) Atom(TM) CPU  Z2460  @ 1.60GHz",   39, 2,   512, 1, 2, 1600, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2480", "Intel Atom Z2480",   "Intel(R) Atom(TM) CPU  Z2480  @ 2.00GHz",   39, 2,   512, 1, 2, 2000, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2610", "Intel Atom Z2610",   "Intel(R) Atom(TM) CPU  Z2610  @ 1.20GHz",   39, 2,   512, 1, 2, 1200, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2420", "Intel Atom Z2420",   "Intel(R) Atom(TM) CPU  Z2420  @ 1.20GHz",   39, 2,   512, 1, 2, 1200, SALTWELL,   32, 32, IMG, "PowerVR SGX 540", "32 nm"),
        atom("z2580", "Intel Atom Z2580",   "Intel(R) Atom(TM) CPU  Z2580  @ 2.00GHz",   53, 1,   512, 2, 4, 2000, SALTWELL,   32, 32, IMG, "PowerVR SGX 544MP2", "32 nm"),
        atom("z2760", "Intel Atom Z2760",   "Intel(R) Atom(TM) CPU  Z2760  @ 1.80GHz",   53, 1,   512, 2, 4, 1800, SALTWELL,   32, 32, IMG, "PowerVR SGX 545", "32 nm"),
        atom("z3480", "Intel Atom Z3480",   "Intel(R) Atom(TM) CPU  Z3480  @ 2.13GHz",   74, 1,  1024, 2, 2, 2130, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6400", "22 nm"),
        atom("z3460", "Intel Atom Z3460",   "Intel(R) Atom(TM) CPU  Z3460  @ 1.60GHz",   74, 1,  1024, 2, 2, 1600, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6400", "22 nm"),
        atom("z3530", "Intel Atom Z3530",   "Intel(R) Atom(TM) CPU  Z3530  @ 1.33GHz",   90, 0,  1024, 4, 4, 1330, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3560", "Intel Atom Z3560",   "Intel(R) Atom(TM) CPU  Z3560  @ 1.00GHz",   90, 0,  1024, 4, 4, 1830, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3570", "Intel Atom Z3570",   "Intel(R) Atom(TM) CPU  Z3570  @ 1.33GHz",   90, 0,  1024, 4, 4, 2000, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3590", "Intel Atom Z3590",   "Intel(R) Atom(TM) CPU  Z3590  @ 2.50GHz",   90, 0,  1024, 4, 4, 2500, SILVERMONT, 36, 48, IMG, "PowerVR Rogue G6430", "22 nm"),
        atom("z3735f", "Intel Atom Z3735F", "Intel(R) Atom(TM) CPU  Z3735F @ 1.33GHz",  55, 8,  1024, 4, 4, 1830, SILVERMONT, 36, 48, MESA, "Mesa DRI Intel(R) Bay Trail", "22 nm"),
        atom("z3795", "Intel Atom Z3795",   "Intel(R) Atom(TM) CPU  Z3795  @ 1.59GHz",   55, 8,  1024, 4, 4, 2390, SILVERMONT, 36, 48, MESA, "Mesa DRI Intel(R) Bay Trail", "22 nm"),
        atom("x3c3130", "Intel Atom x3-C3130", "Intel(R) Atom(TM) x3-C3130",            93, 1,   512, 2, 2, 1000, SILVERMONT, 36, 48, ARM_GPU, "Mali-400 MP2", "28 nm"),
        atom("x3c3200rk", "Intel Atom x3-C3200RK", "Intel(R) Atom(TM) x3-C3200RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3205rk", "Intel Atom x3-C3205RK", "Intel(R) Atom(TM) x3-C3205RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3230rk", "Intel Atom x3-C3230RK", "Intel(R) Atom(TM) x3-C3230RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3235rk", "Intel Atom x3-C3235RK", "Intel(R) Atom(TM) x3-C3235RK",      93, 1,  1024, 4, 4, 1200, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3265rk", "Intel Atom x3-C3265RK", "Intel(R) Atom(TM) x3-C3265RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3295rk", "Intel Atom x3-C3295RK", "Intel(R) Atom(TM) x3-C3295RK",      93, 1,  1024, 4, 4, 1100, SILVERMONT, 36, 48, ARM_GPU, "Mali-450 MP4", "28 nm"),
        atom("x3c3405", "Intel Atom x3-C3405", "Intel(R) Atom(TM) x3-C3405",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x3c3440", "Intel Atom x3-C3440", "Intel(R) Atom(TM) x3-C3440",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x3c3445", "Intel Atom x3-C3445", "Intel(R) Atom(TM) x3-C3445",            93, 1,  1024, 4, 4, 1400, SILVERMONT, 36, 48, ARM_GPU, "Mali-T720", "28 nm"),
        atom("x5z8300", "Intel Atom x5-Z8300", "Intel(R) Atom(TM) x5-Z8300  CPU @ 1.44GHz", 76, 3, 1024, 4, 4, 1840, CHERRY_TRAIL, 36, 48, MESA, "Mesa DRI Intel(R) HD Graphics (Cherryview)", "14 nm"),
        // desktop pair, for SDK emulators (an x86 image on a PC GPU)
        desktop("i5_10400f_gtx1650", "Intel Core i5-10400F + GTX 1650", "Intel(R) Core(TM) i5-10400F CPU @ 2.90GHz", 165, 3, 12288, 6, 12, 4300, COMET_LAKE, 39, 48,
            "NVIDIA Corporation", "NVIDIA GeForce GTX 1650/PCIe/SSE2", "14 nm"),
    )

    // ---- feature strings -------------------------------------------------------------------------------------------
    /** ARM1136 (MSM7227 / 7627, real MSM7227 dump): VFP, Jazelle ("java"), no NEON */
    private const val ARM11_V6 = "swp half thumb fastmult vfp edsp java"
    private const val SCORPION = "swp half thumb fastmult vfp edsp neon vfpv3 tls"
    private const val A5_V7 = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4"
    private const val A7_V7 = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm"
    private const val KRAIT = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt"
    private const val V7 = "half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm"
    private const val A64_BASE = "fp asimd evtstrm aes pmull sha1 sha2 crc32"
    private const val A64_LITE = "fp asimd evtstrm crc32"
    private const val A64_V82 = "$A64_BASE atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    /** Armv8.2 A55 without the crypto extension (JLQ JR510, real Poco C40 dump) */
    private const val A64_V82_NOCRYPTO = "$A64_LITE atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private const val A64_V9 = "$A64_V82 sha3 sm3 sm4 sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svei8mm svebf16 i8mm bf16 dgh bti"
    private const val A64_ORYON = "$A64_V82 jscvt fcma sha3 sm3 sm4 sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 dgh bti"

    /** Cortex-A8 (Nexus S dump) and Cortex-A9 of the Exynos 4412 (supercurio's Galaxy S III report): the kernel adds a trailing space, see CpuinfoStyle.ARM_K30 */
    private const val EXY_A8 = "swp half thumb fastmult vfp edsp thumbee neon vfpv3"
    private const val EXY_A9 = "swp half thumb fastmult vfp edsp neon vfpv3 tls"
    /** Cortex-A7 / A15 on a 32-bit Exynos 3.x kernel (Exynos 5422 dump: no vfpd32, no lpae) */
    private const val EXY_A15 = "swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt"
    /** Exynos 7420 (Galaxy S6 dump): Samsung's 3.10 prints the 32-bit words after the 64-bit ones, and has no evtstrm */
    private const val EXY_K310 = "fp asimd aes pmull sha1 sha2 crc32 wp half thumb fastmult vfp edsp neon vfpv3 tlsi vfpv4 idiva idivt"
    /** Exynos 9810 (Galaxy S9 dump, 4.9): hwcaps 0..10 */
    private const val EXY_A64_K49 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp"
    /** Galaxy S10 (Exynos 9820, kernel 4.14) real Features: the 4.14 words, but no lrcpc (the M4 / A75 cores lack it) */
    private const val EXY_A64_K414 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm dcpop asimddp"
    /** Real printouts of ARMv9 phones (pytorch/cpuinfo Pixel 8, kernel 5.15; newandroidbook.com Pixel 10, kernel 6.x with ecv / afp / wfxt) */
    private const val V9_PIXEL8 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bti"
    private const val V9_PIXEL10 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti ecv afp wfxt"

    private const val X86_BASE = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe"
    private const val SALTWELL = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe nx constant_tsc arch_perfmon pebs bts nopl nonstop_tsc aperfmperf pni dtes64 monitor ds_cpl est tm2 ssse3 xtpr pdcm movbe lahf_lm arat dtherm"
    // the Z3580's own line (zenfone-2 dump in AOSP external/cpuinfo): vmx, ida and the EPT group included
    private const val SILVERMONT = "$X86_BASE syscall nx rdtscp lm constant_tsc arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc aperfmperf pni pclmulqdq dtes64 monitor ds_cpl vmx est tm2 ssse3 cx16 xtpr pdcm sse4_1 sse4_2 movbe popcnt tsc_deadline_timer aes rdrand lahf_lm 3dnowprefetch ida arat epb dtherm tpr_shadow vnmi flexpriority ept vpid tsc_adjust smep erms"
    // x5-Z8300 on a 4.15 kernel (Lemel stick dump, Ubuntu 18.04): tsc_reliable / cpuid / tsc_known_freq / pti appear, ida does not
    private const val CHERRY_TRAIL = "fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx rdtscp lm constant_tsc arch_perfmon pebs bts rep_good nopl xtopology tsc_reliable nonstop_tsc cpuid aperfmperf tsc_known_freq pni pclmulqdq dtes64 monitor ds_cpl vmx est tm2 ssse3 cx16 xtpr pdcm sse4_1 sse4_2 movbe popcnt tsc_deadline_timer aes rdrand lahf_lm 3dnowprefetch epb pti tpr_shadow vnmi flexpriority ept vpid tsc_adjust smep erms dtherm arat"
    private const val COMET_LAKE = "$X86_BASE syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc cpuid aperfmperf pni pclmulqdq dtes64 monitor ds_cpl vmx smx est tm2 ssse3 sdbg fma cx16 xtpr pdcm pcid sse4_1 sse4_2 x2apic movbe popcnt tsc_deadline_timer aes xsave avx f16c rdrand lahf_lm abm 3dnowprefetch cpuid_fault epb invpcid_single ssbd ibrs ibpb stibp ibrs_enhanced tpr_shadow vnmi flexpriority ept vpid ept_ad fsgsbase tsc_adjust bmi1 avx2 smep bmi2 erms invpcid mpx rdseed adx smap clflushopt intel_pt xsaveopt xsavec xgetbv1 xsaves dtherm ida arat pln pts hwp hwp_notify hwp_act_window hwp_epp md_clear flush_l1d arch_capabilities"

    private const val IMG = "Imagination Technologies"
    private const val MESA = "Intel Open Source Technology Center"
    private const val ARM_GPU = "ARM"
    private const val SAMSUNG_GPU = "Samsung Electronics Co., Ltd."

    // ---- row builders ----------------------------------------------------------------------------------------------

    /** (count, implementer, variant, part, revision, max MHz, min MHz); little cores first */
    private fun c(count: Int, implementer: Int, variant: Int, part: Int, revision: Int, maxMhz: Int, minMhz: Int) =
        SocCluster(count, implementer, variant, part, revision, maxMhz, minMhz)

    /** Qualcomm: id, menu name, ro.soc.model, ro.board.platform, soc0 soc_id, ARM version (6 / 7 / 8), Features, clusters, GL_RENDERER, process node; [style] = cpuinfo layout (old 2.6.35 / 3.x kernels: ARM_K30) */
    private fun qc(id: String, label: String, model: String, platform: String, socId: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, node: String, hw: String = "", rev: String = "1.0", serial: String = "", bogo: String = "",
                   style: CpuinfoStyle = CpuinfoStyle.GENERIC, hwRev: String = "", props: Map<String, String> = emptyMap()): SocSpec {
        val name = model.substringBefore('-')
        return SocSpec(
            id = id, label = label, manufacturer = QTI, model = model, platform = platform,
            hardware = hw.ifEmpty { if (arch >= 8) "Qualcomm Technologies, Inc $name" else "Qualcomm $name" },
            family = "Snapdragon", machine = model, socId = socId, revision = rev, serialNumber = serial.ifEmpty { fakeSerial(id) },
            arch = arch, clusters = clusters, features = features, bogoMips = bogo,
            isa = SocIsa.ARM, gpuVendor = "Qualcomm", gpuRenderer = gpu, note = node, cpuinfoStyle = style,
            hwRevision = hwRev, extraProps = props,
        )
    }

    /** MediaTek: id, menu name, model, platform, soc_id, ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node */
    private fun mt(id: String, label: String, model: String, platform: String, socId: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, gpuVendor: String, node: String, serial: String = "", bogo: String = "26.00") = SocSpec(
        id = id, label = label, manufacturer = "MediaTek", model = model, platform = platform, hardware = model,
        family = "MediaTek", machine = model, socId = socId, revision = "1.0", serialNumber = serial.ifEmpty { fakeSerial(id) },
        arch = arch, clusters = clusters, features = features, bogoMips = bogo,
        isa = SocIsa.ARM, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node,
    )

    /**
     * Samsung Exynos: id, menu name, ro.soc.model, ro.board.platform, ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node, cpuinfo layout.
     * [props] are the chip's firmware properties (ro.hardware, ro.product.board, ro.chipname ... as the reference phone has them).
     */
    private fun ex(id: String, label: String, model: String, platform: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, gpuVendor: String, node: String, style: CpuinfoStyle, hardware: String = "", bogo: String = "", hwRev: String = "",
                   props: Map<String, String> = emptyMap(), family: String = "", machine: String = "", socId: String = "", rev: String = "") = SocSpec(
        id = id, label = label, manufacturer = "Samsung", family = family, machine = machine, socId = socId, revision = rev, model = model, platform = platform, hardware = hardware,
        serialNumber = fakeSerial(id), arch = arch, clusters = clusters, features = features, bogoMips = bogo,
        isa = SocIsa.ARM, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node, extraProps = props, cpuinfoStyle = style, hwRevision = hwRev,
    )

    /**
     * Spreadtrum / Unisoc: id, menu name, ro.soc.model, ro.board.platform, ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node, then "Hardware" line,
     * BogoMIPS, ro.opengles.version, [board] (ro.hardware = ro.boot.hardware), [legacy] (3.x kernel: "Processor" header), [style] (see CpuinfoStyle), [kernel].
     * No soc0 (these kernels register none): family / machine / soc_id stay blank.
     */
    private fun sp(id: String, label: String, model: String, platform: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, gpuVendor: String, node: String, hw: String, bogo: String, gles: Int, board: String = "",
                   legacy: Boolean = false, style: CpuinfoStyle = CpuinfoStyle.GENERIC, kernel: ArmKernel? = null,
                   hwRev: String = "", props: Map<String, String> = emptyMap()) = SocSpec(
        id = id, label = label, manufacturer = "UNISOC", model = model, platform = platform, hardware = hw,
        serialNumber = fakeSerial(id), arch = arch, clusters = clusters, features = features, bogoMips = bogo,
        isa = SocIsa.ARM, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node, legacyKernel = legacy, cpuinfoStyle = style, kernel = kernel, hwRevision = hwRev,
        extraProps = LinkedHashMap<String, String>().apply {
            if (board.isNotBlank()) { put("ro.hardware", board); put("ro.boot.hardware", board) }
            put("ro.opengles.version", gles.toString())
            putAll(props)
        },
    )

    /**
     * NVIDIA Tegra: id, menu name, ro.soc.model, ro.board.platform, ARM version, Features, clusters, GL_RENDERER, node, then the "Hardware" line / board name
     * (blank on the arm64 kernels), BogoMIPS ("none" = no line), ro.opengles.version, [style], [serial] (the 16-digit id of the 32-bit kernels' Serial line),
     * [socId] / [rev] (soc0, kernels from 3.10; blank = no soc0), [hardwareLine], [tail] and [kernel].
     */
    private fun tg(id: String, label: String, model: String, platform: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, node: String, hw: String = "", bogo: String, gles: Int, style: CpuinfoStyle = CpuinfoStyle.GENERIC, serial: String = "",
                   socId: String = "", rev: String = "", hardwareLine: Boolean = true, tail: Map<String, String> = emptyMap(), kernel: ArmKernel? = null) = SocSpec(
        id = id, label = label, manufacturer = "NVIDIA", model = model, platform = platform, hardware = hw,
        family = if (socId.isNotBlank()) "Tegra" else "", socId = socId, revision = rev, serialNumber = serial.ifEmpty { fakeSerial(id) },
        arch = arch, clusters = clusters, features = features, bogoMips = bogo,
        isa = SocIsa.ARM, gpuVendor = "NVIDIA Corporation", gpuRenderer = gpu, note = node,
        cpuinfoStyle = style, hardwareLine = hardwareLine, cpuinfoTail = tail, kernel = kernel,
        extraProps = mapOf("ro.opengles.version" to gles.toString()),
    )

    /**
     * Added 2026-11-23. HiSilicon / Xiaomi / JLQ / TI: id, menu name, manufacturer, ro.soc.model, ro.board.platform ("" = firmware's own), "Hardware" line (blank = none printed),
     * ARM version, Features, clusters, GL_RENDERER, GL_VENDOR, node, cpuinfo layout, BogoMIPS, Revision line, 16-digit Serial, firmware [props], [legacy] (old kernel) and [kernel].
     * No soc0 (see NO_SOC0 in SocProfile.kt).
     */
    private fun ot(id: String, label: String, mfr: String, model: String, platform: String, hardware: String, arch: Int, features: String, clusters: List<SocCluster>,
                   gpu: String, gpuVendor: String, node: String, style: CpuinfoStyle, bogo: String, hwRev: String = "", serial: String = "",
                   props: Map<String, String> = emptyMap(), legacy: Boolean = false, kernel: ArmKernel? = null, tail: Map<String, String> = emptyMap()) = SocSpec(
        id = id, label = label, manufacturer = mfr, model = model, platform = platform, hardware = hardware, serialNumber = serial.ifEmpty { fakeSerial(id) },
        arch = arch, clusters = clusters, features = features, bogoMips = bogo, isa = SocIsa.ARM, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node,
        extraProps = props, legacyKernel = legacy, cpuinfoStyle = style, hwRevision = hwRev, hardwareLine = hardware.isNotBlank(), kernel = kernel,
        cpuinfoTail = tail,
    )

    /** Intel Atom: id, menu name, brand string, cpuid model, stepping, L2 KB, cores, threads, max MHz, flags, address bits, GL_VENDOR, GL_RENDERER, node */
    private fun atom(id: String, label: String, brand: String, model: Int, stepping: Int, cacheKb: Int, cores: Int, threads: Int, maxMhz: Int, flags: String,
                     phys: Int, virt: Int, gpuVendor: String, gpu: String, node: String): SocSpec {
        val saltwell = flags == SALTWELL
        val bay = model == 55
        val cht = model == 76
        // Bay / Cherry Trail boards are UEFI machines (DMI, intel_pstate, a PCI HD Graphics); the phone Atoms boot a 3.x BSP kernel without them
        val pci = when { bay -> "0x8086:0x0f31"; cht -> "0x8086:0x22b1"; else -> "" }
        val dmi = when { bay -> BAY_TRAIL_DMI; cht -> CHERRY_TRAIL_DMI; else -> null }
        val x86 = X86Cpu(
            vendorId = "GenuineIntel", family = 6, model = model, stepping = stepping, modelName = brand, cacheKb = cacheKb, physicalCores = cores, flags = flags,
            physBits = phys, virtBits = virt,
            // microcode from real dumps: Z3580 0x34 / 0x38 (pytorch/cpuinfo zenfone-2), Z3560 0x34, Z3735F 0x82b / 0x82f / 0x838, x5-Z8300 0x35f / 0x363 / 0x368.
            // Merrifield (Z34xx) and Saltwell (0x218) values are NOT from a dump: plausible only
            microcode = when { saltwell -> "0x218"; bay -> "0x838"; cht -> "0x368"; else -> "0x38" },
            cpuidLevel = if (saltwell) 10 else 11,
            uarch = when { saltwell -> "saltwell"; cht -> "airmont"; else -> "silvermont" },
            cpufreqDriver = when { bay || cht -> "intel_pstate"; saltwell -> "acpi-cpufreq"; model == 93 -> "acpi-cpufreq"; else -> "sfi-cpufreq" },
            // x5-Z8300 dump (OpenBenchmarking, kernel with PTI): Silvermont / Airmont are Meltdown + MSBDS affected
            bugs = if (dmi != null) "cpu_meltdown spectre_v1 spectre_v2 mds msbds_only" else "",
            vmxFlags = if (dmi != null) "vnmi preemption_timer invvpid ept_x_only flexpriority tsc_offset vtpr mtf vapic ept vpid unrestricted_guest" else "",
            gpuPci = pci, dmi = dmi,
        )
        return SocSpec(
            id = id, label = label, manufacturer = "Intel", model = label.removePrefix("Intel Atom ").trim(), platform = "",
            hardware = "", isa = SocIsa.X86,
            clusters = listOf(SocCluster(threads, 0, 0, 0, 0, maxMhz, 800)),
            x86 = x86, gpuVendor = gpuVendor, gpuRenderer = gpu, note = node, legacyKernel = dmi == null,
        )
    }

    /** a desktop CPU + graphics card (no platform property: the firmware's own stays) */
    private fun desktop(id: String, label: String, brand: String, model: Int, stepping: Int, cacheKb: Int, cores: Int, threads: Int, maxMhz: Int, flags: String,
                        phys: Int, virt: Int, gpuVendor: String, gpu: String, node: String) = SocSpec(
        id = id, label = label, manufacturer = "Intel", model = "i5-10400F", platform = "",
        hardware = "", isa = SocIsa.X86,
        clusters = listOf(SocCluster(threads, 0, 0, 0, 0, maxMhz, 800)),
        x86 = X86Cpu(
            vendorId = "GenuineIntel", family = 6, model = model, stepping = stepping, modelName = brand, cacheKb = cacheKb, physicalCores = cores, flags = flags,
            physBits = phys, virtBits = virt, microcode = "0xf8", cpuidLevel = 22,
            bugs = "spectre_v1 spectre_v2 spec_store_bypass swapgs itlb_multihit srbds mmio_stale_data",
            vmxFlags = "vnmi preemption_timer posted_intr invvpid ept_x_only ept_ad ept_1gb flexpriority apicv tsc_offset vtpr mtf vapic ept vpid unrestricted_guest vapic_reg vid ple shadow_vmcs pml",
            uarch = "desktop", cpufreqDriver = "intel_pstate", gpuPci = "0x10de:0x1f82", dmi = DIY_BOARD_DMI,
        ),
        gpuVendor = gpuVendor, gpuRenderer = gpu, note = node,
    )

    /** a fixed serial per chip, so the guest sees the same one at every boot */
    private fun fakeSerial(id: String): String = ((id.hashCode().toLong() and 0x7fffffffL) * 1_000_003L % 4_000_000_000L).toString()
}
