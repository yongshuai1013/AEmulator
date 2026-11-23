package app.aemu.core

import java.security.MessageDigest

/**
 * Subset of AEmulator-Plus's DeltaOta: its incremental-OTA engine as a whole is not carried,
 * only the boot-image matching that importer/RecoveryFromBoot rebuilds a recovery with.
 * The three members below are verbatim from AEmulator-Plus (7019c781).
 */
object DeltaOta {

    private val HEX = "0123456789abcdef".toCharArray()
    private fun sha1(b: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1").digest(b)
        val sb = StringBuilder(40)
        for (x in d) { sb.append(HEX[(x.toInt() shr 4) and 15]); sb.append(HEX[x.toInt() and 15]) }
        return sb.toString()
    }

    /** What [matchBootImage] found: the boot image as the OTA sees it, and whether that is already the updated one. */
    internal class BootMatch(val image: ByteArray, val isTarget: Boolean)

    /**
     * Compares the boot image a VM keeps with what an incremental OTA expects of the boot partition.
     *
     * The OTA names the partition as "EMMC:<device>:<size>:<sha1>[:<size>:<sha1>...]" (MTD: on Android 2.x), and
     * applypatch hashes the first <size> bytes of the partition, not the whole of it. A boot image the VM keeps may be
     * longer than that (a raw partition dump, or an image padded to the partition size) or shorter (the zero padding
     * was dropped), so the image is cut or zero-padded to every size the OTA lists before it is hashed. The image as
     * it is also counts, for OTAs whose partition name carries no sizes.
     */
    internal fun matchBootImage(b: ByteArray, spec: String, srcSha: String, tgtSha: String): BootMatch? {
        fun probe(v: ByteArray): BootMatch? {
            val h = sha1(v)
            return when (h) {
                srcSha -> BootMatch(v, false)
                tgtSha -> BootMatch(v, true)
                else -> null
            }
        }
        probe(b)?.let { return it }
        val sizes = spec.split(':').drop(2).chunked(2).mapNotNull { it.firstOrNull()?.toIntOrNull() }
            .filter { it > 0 && it <= (64 shl 20) && it != b.size }.distinct()
        for (n in sizes) probe(b.copyOf(n))?.let { return it }
        return null
    }
}
