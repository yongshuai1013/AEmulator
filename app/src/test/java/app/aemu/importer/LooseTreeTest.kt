package app.aemu.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loose-tree prefix detection behind the zip/rar claim in Importer: a fastboot repack
 * (Motorola XT1032 shape) buries the extracted /system tree under the package folder, where
 * the root-only "system/…" claim used to miss it and the import died with
 * "no Android system partition found in file".
 */
class LooseTreeTest {

    /** the claim predicate of Importer.importZipEntries, mirrored on the detected prefix */
    private fun claims(prefix: String, n: String): Boolean =
        (prefix.isEmpty() || n.startsWith(prefix)) && n.removePrefix(prefix).startsWith("system/")

    @Test fun fastbootRepackTreeIsFoundUnderThePackageFolder() {
        val root = "Motorola_XT1032_O2SL-GB_4.4.4_KXB21.14-L1.40_37_CID7/Firmware + Tool/"
        val names = listOf(
            "Motorola_XT1032_O2SL-GB_4.4.4_KXB21.14-L1.40_37_CID7/Credits.txt",
            root + "boot.img",
            root + "recovery.img",
            root + "motoboot.img",
            root + "system/build.prop",
            root + "system/app/Settings.apk",
            root + "system/framework/framework.jar",
            root + "system/bin/sh",
        )
        val prefix = looseTreePrefix(names)
        assertEquals(root, prefix)
        // the tree lands at the guest root, the images keep their base-name claims untouched
        assertTrue(claims(prefix, root + "system/build.prop"))
        assertEquals("system/app/Settings.apk", (root + "system/app/Settings.apk").removePrefix(prefix))
        assertFalse(claims(prefix, root + "boot.img"))
        assertFalse(claims(prefix, "Motorola_XT1032_O2SL-GB_4.4.4_KXB21.14-L1.40_37_CID7/Credits.txt"))
    }

    @Test fun rootTreeKeepsTheEmptyPrefix() {
        val names = listOf("system/build.prop", "system/app/x.apk", "boot.img")
        assertEquals("", looseTreePrefix(names))
        assertTrue(claims("", "system/build.prop"))
    }

    @Test fun otaShapeAfterWrapperStripIsUnchanged() {
        // the caller strips the one-folder OTA wrapper before detection; what remains is a root tree
        val names = listOf("META-INF/com/google/android/updater-script", "system/build.prop", "system/bin/sh", "boot.img")
        assertEquals("", looseTreePrefix(names))
    }

    @Test fun imagesAndSparseChunksDoNotAnchor() {
        val names = listOf("boot.img", "system.img", "system.img_sparsechunk.0", "system.img_sparsechunk.1", "vendor/system/build.prop.bak")
        assertEquals("", looseTreePrefix(names))
    }

    @Test fun theBiggestTreeWinsOverAStrayAnchor() {
        val names = listOf(
            "docs/system/build.prop",
            "fw/system/build.prop", "fw/system/app/a.apk", "fw/system/bin/sh", "fw/boot.img",
        )
        assertEquals("fw/", looseTreePrefix(names))
    }
}
