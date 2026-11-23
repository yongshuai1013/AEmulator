/* From AEmulator-Plus (SimCards' SIM elementary files, 2026-10-11), GPL-3.0; see LICENSE and NOTICE.md.
 * Only the file this fork's modem answers beyond EF_ICCID lives here; operator and ICCID
 * resolution stays in RilStub, whose answers are the field-verified ones. */
package app.aemu.core

/** Elementary files of the fake SIM, in the on-card byte form SIM_IO answers with. */
object SimFiles {
    const val MSISDN_RECORD = 28                          // 14 bytes alpha id + 14 bytes number

    /** EF_MSISDN (6F40) record: alpha id "Mobile", BCD number (international when it starts with +). */
    fun efMsisdn(number: String): ByteArray {
        val r = ByteArray(MSISDN_RECORD) { 0xff.toByte() }
        "Mobile".forEachIndexed { i, c -> r[i] = c.code.toByte() }
        val digits = number.filter(Char::isDigit).take(20)
        if (digits.isEmpty()) return r
        val bcd = bcd(digits)
        r[14] = (1 + bcd.size).toByte()                   // TON/NPI byte + number bytes
        r[15] = if (number.startsWith("+")) 0x91.toByte() else 0x81.toByte()
        bcd.copyInto(r, 16)
        return r
    }

    /** semi-octets, nibbles swapped, F padded */
    private fun bcd(d: String): ByteArray {
        val s = if (d.length % 2 == 1) d + "F" else d
        return ByteArray(s.length / 2) { i ->
            val lo = s[2 * i].digitToInt(16); val hi = s[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
