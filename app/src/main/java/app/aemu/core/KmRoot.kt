/* AEmulator addition, 2026-11-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.RSAPrivateKey as BcRsaPrivateKey
import org.bouncycastle.asn1.sec.ECPrivateKey as BcEcPrivateKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Per-guest keymaster root replacement.
 *
 * The guest's software keymaster (SoftKeymasterDevice) signs attestation
 * chains with a key pair and two certificates compiled into its system
 * library as six fixed-length DER blobs. This replaces those six blobs,
 * in this guest's copy of the library only, with a chain whose names the
 * user fills in themselves — nothing about the chain's content is chosen
 * here. The blobs are length-locked by the library's compiled-in array
 * sizes, so new certificates are padded to the exact slot lengths with a
 * non-critical private filler extension before they are written.
 *
 * Material lives in the guest's own host-side folder (VmPaths.dir/kmroot),
 * the original library is backed up next to it before the first implant,
 * and [restore] puts it back byte for byte.
 */
object KmRoot {
    val BC = BouncyCastleProvider()
    private val RAND = SecureRandom()

    const val SO_REL = "system/lib64/libsoftkeymasterdevice.so"
    private const val DIR_NAME = "kmroot"
    private const val BACKUP_NAME = "libsoftkeymasterdevice.so.bak"

    // The six built-in slots of the library, in file order, with the exact
    // byte lengths the compiled-in arrays fix them at.
    const val LEN_RSA_KEY = 609
    const val LEN_EC_KEY = 121
    const val LEN_RSA_CERT = 698
    const val LEN_RSA_ROOT = 683
    const val LEN_EC_CERT = 636
    const val LEN_EC_ROOT = 655

    private val SLOT_LENS = intArrayOf(
        LEN_RSA_KEY, LEN_EC_KEY, LEN_RSA_CERT, LEN_RSA_ROOT, LEN_EC_CERT, LEN_EC_ROOT)

    // Non-critical private filler extension: zero bytes, added only to pad a
    // certificate to its slot length; it carries no meaning.
    private val FILLER_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.55555.1")
    private val P256_OID = ASN1ObjectIdentifier("1.2.840.10045.3.1.7")

    enum class Part { RSA_ROOT, RSA_ATTEST, EC_ROOT, EC_ATTEST }

    class KmException(val kind: Kind, val part: Part? = null) : Exception(kind.name + (part?.let { ":$it" } ?: "")) {
        enum class Kind {
            NO_SO, NO_MATERIAL, NO_BACKUP, SHAPE, TOO_LONG, LENGTH, YEARS,
            NOT_BUNDLE, PASSWORD, MISSING, CHAIN, KEY_MATCH, KEY_SIZE, ROOT_KEY
        }
    }

    private fun fail(kind: KmException.Kind, part: Part? = null): Nothing = throw KmException(kind, part)

    // The library's built-in blobs, byte for byte (the AOSP software
    // attestation test chain, public in the AOSP source tree). They are the
    // search keys that locate the six slots inside a pristine library file;
    // a library whose bytes do not contain them exactly once each, in this
    // order, is not a shape this knows how to touch.
    private val ORIG_RSA_KEY = Base64.getDecoder().decode(
        "MIICXQIBAAKBgQDAgyPcVogbuDAgafWwhWHG7r5/BeL1qEIEir6LR752/q7yXPKbKvoyABQWAUKZiaFfz8aBXrNjWDwv0vIL5Jgy" +
        "g92BSxbX4YVBeuVKvClqOm21wAQIO2jFVsHwIzmRZBmGTVC3TUCuykhMdzVsiVoMJ1q/rEmdXX0jYvKcXgLocQIDAQABAoGBAL6G" +
        "CwuZqAKm+xpZQ4p7txUGWwmjbcbpysxr88AsNNfXnpTGYGQo2Ix7f2V3wc3qZAdKvo5yht8fCBHclygmCGjeldMu/Ja20IT/Jxpf" +
        "YN78xwPno45uKbqaPF/CwoB2tqiWrx0014gozpvdsfNPnJQEQweBKY4gExZyW728mTpBAkEA4cbZJ2RsCRbsNoJtWUmDdAwh8bB0" +
        "xKGlmGfGaXlchdPcRkxbkp6Uv7NODcxQFLEPEzQat/3V9gQU0qMmytQcxQJBANpIWZd4XNVjD7D9jFJU+Y5TjhiYOq6ea35qWntd" +
        "NDdVuSGOvUAyDSg4fXifdvohi8wti2il9kGPu+ylF5qzr70CQFD+/DJklVlhbtZTThVFCTKdk6PYENvlvbmCKSz3i9i624Agro1X" +
        "9LcdBThv/p6dsnHKNHejSZnbdvjl7OnA1J0CQBW3TPJ8zv+Ls2vwTZ2DRrCaL3DS9EObDyasfgP36dH3fUuRX9KbKCPwOstdUgDg" +
        "hX/yqAPpPu6W1iNc6VRCvCECQQCQp0XaiXCyzWSWYDJCKMX4KFb/1mW6moXI1g8bi+5xfs0scurgHa2GunZU1M9FrbXx8rMdn4Ei" +
        "z6XxpVcPmy0l")
    private val ORIG_EC_KEY = Base64.getDecoder().decode(
        "MHcCAQEEICHghkMqFRmEWc82OlD8FMnarfk19SfC39ceTW28QuVEoAoGCCqGSM49AwEHoUQDQgAE6555+EJjWazLKpFMiYbMcK2Q" +
        "ZpOCqXMmE/6sy/ghJ0whdJdKKv6luU1/ZtTgZRBmNbxTt6CjpnFYPts+Ea4QFA==")
    private val ORIG_RSA_CERT = Base64.getDecoder().decode(
        "MIICtjCCAh+gAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwYzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNV" +
        "BAcMDU1vdW50YWluIFZpZXcxFTATBgNVBAoMDEdvb2dsZSwgSW5jLjEQMA4GA1UECwwHQW5kcm9pZDAeFw0xNjAxMDQxMjQwNTNa" +
        "Fw0zNTEyMzAxMjQwNTNaMHYxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRUwEwYDVQQKDAxHb29nbGUsIEluYy4x" +
        "EDAOBgNVBAsMB0FuZHJvaWQxKTAnBgNVBAMMIEFuZHJvaWQgU29mdHdhcmUgQXR0ZXN0YXRpb24gS2V5MIGfMA0GCSqGSIb3DQEB" +
        "AQUAA4GNADCBiQKBgQDAgyPcVogbuDAgafWwhWHG7r5/BeL1qEIEir6LR752/q7yXPKbKvoyABQWAUKZiaFfz8aBXrNjWDwv0vIL" +
        "5Jgyg92BSxbX4YVBeuVKvClqOm21wAQIO2jFVsHwIzmRZBmGTVC3TUCuykhMdzVsiVoMJ1q/rEmdXX0jYvKcXgLocQIDAQABo2Yw" +
        "ZDAdBgNVHQ4EFgQU1AwQG/jNY7n3OVK1DhNcpteZk4YwHwYDVR0jBBgwFoAUKfrxrMxN0kyWQCd1trDpMuUH/i4wEgYDVR0TAQH/" +
        "BAgwBgEB/wIBADAOBgNVHQ8BAf8EBAMCAoQwDQYJKoZIhvcNAQELBQADgYEAni1IX4xnM9waha2Z11Aj6hTsQ7DhnerCI0YecrUZ" +
        "3GAi5KVoMWwLVcTmnKItnzpPk2sxixZ4Fg2Iy9mLzICdhPDCJ+NrOPH90ecXcjFZNX2W88V/q52PlmEmT7K+gbsNSQQiis6f9/VC" +
        "LiVE+iEHElqDtVWtGIL4QBSbnCBjBH8=")
    private val ORIG_RSA_ROOT = Base64.getDecoder().decode(
        "MIICpzCCAhCgAwIBAgIJAP+U2d2fB8gMMA0GCSqGSIb3DQEBCwUAMGMxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlh" +
        "MRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRUwEwYDVQQKDAxHb29nbGUsIEluYy4xEDAOBgNVBAsMB0FuZHJvaWQwHhcNMTYwMTA0" +
        "MTIzMTA4WhcNMzUxMjMwMTIzMTA4WjBjMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRh" +
        "aW4gVmlldzEVMBMGA1UECgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKB" +
        "gQCia63rbi5EYe/VDoLmt5TRdSMfd5tjkWP/96r/C3JHTsAsQ+wzfNes7UA+jCigZtX3hwszl94OuE4TQKuvpSe/lWmgMdsGUmX4" +
        "RFlXYfC78hdLt0GAZMAoDo9Sd47b0ke2RekZyOmLw9vCkT/X11DEHTVm+Vfkl5YLCazOkjWFmwIDAQABo2MwYTAdBgNVHQ4EFgQU" +
        "KfrxrMxN0kyWQCd1trDpMuUH/i4wHwYDVR0jBBgwFoAUKfrxrMxN0kyWQCd1trDpMuUH/i4wDwYDVR0TAQH/BAUwAwEB/zAOBgNV" +
        "HQ8BAf8EBAMCAoQwDQYJKoZIhvcNAQELBQADgYEAT3LzNlmNDsG5dFsxWfbwjSVJMJ6jHBwp0kUtILlNX2S06IDHeHqcOd6os/W/" +
        "L3BfRxBcxebrTQaZYdKumgf/93y4q+ucDyQHXrF/unlx/U1bnt8Uqf7f7XzAiF343ZtkMlbVNZriE/mPzsF83O+kqrJVw4OpLvtc" +
        "9mL1J1IXvmM=")
    private val ORIG_EC_CERT = Base64.getDecoder().decode(
        "MIICeDCCAh6gAwIBAgICEAEwCgYIKoZIzj0EAwIwgZgxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQH" +
        "DA1Nb3VudGFpbiBWaWV3MRUwEwYDVQQKDAxHb29nbGUsIEluYy4xEDAOBgNVBAsMB0FuZHJvaWQxMzAxBgNVBAMMKkFuZHJvaWQg" +
        "S2V5c3RvcmUgU29mdHdhcmUgQXR0ZXN0YXRpb24gUm9vdDAeFw0xNjAxMTEwMDQ2MDlaFw0yNjAxMDgwMDQ2MDlaMIGIMQswCQYD" +
        "VQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEVMBMGA1UECgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMTswOQYD" +
        "VQQDDDJBbmRyb2lkIEtleXN0b3JlIFNvZnR3YXJlIEF0dGVzdGF0aW9uIEludGVybWVkaWF0ZTBZMBMGByqGSM49AgEGCCqGSM49" +
        "AwEHA0IABOueefhCY1msyyqRTImGzHCtkGaTgqlzJhP+rMv4ISdMIXSXSir+pblNf2bU4GUQZjW8U7ego6ZxWD7bPhGuEBSjZjBk" +
        "MB0GA1UdDgQWBBQ//KzWGrE6noEguNUlHMVlux6RqTAfBgNVHSMEGDAWgBTIrel3TEXDo88NFhDkeUM6IVowzzASBgNVHRMBAf8E" +
        "CDAGAQH/AgEAMA4GA1UdDwEB/wQEAwIChDAKBggqhkjOPQQDAgNIADBFAiBLipt77oK8wDOHri/AiZi03cONqycqRZ9pDMfDktQP" +
        "jgIhAO7aAV229DLp1IQ7YkyUBO86fMy9Xvsiu+f+uXc/WT/7")
    private val ORIG_EC_ROOT = Base64.getDecoder().decode(
        "MIICizCCAjKgAwIBAgIJAKIFntEOQ1tXMAoGCCqGSM49BAMCMIGYMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEW" +
        "MBQGA1UEBwwNTW91bnRhaW4gVmlldzEVMBMGA1UECgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMTMwMQYDVQQDDCpB" +
        "bmRyb2lkIEtleXN0b3JlIFNvZnR3YXJlIEF0dGVzdGF0aW9uIFJvb3QwHhcNMTYwMTExMDA0MzUwWhcNMzYwMTA2MDA0MzUwWjCB" +
        "mDELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxFTATBgNVBAoMDEdvb2ds" +
        "ZSwgSW5jLjEQMA4GA1UECwwHQW5kcm9pZDEzMDEGA1UEAwwqQW5kcm9pZCBLZXlzdG9yZSBTb2Z0d2FyZSBBdHRlc3RhdGlvbiBS" +
        "b290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7l1ex+HA220Dpn7mthvsTWpdamguD/9/SQ59dx9EIm29sa/6FsvHrcV30lac" +
        "qrewLVQBXT5DKyqO107sSHVBpKNjMGEwHQYDVR0OBBYEFMit6XdMRcOjzw0WEOR5QzohWjDPMB8GA1UdIwQYMBaAFMit6XdMRcOj" +
        "zw0WEOR5QzohWjDPMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgKEMAoGCCqGSM49BAMCA0cAMEQCIDUho++LNEYenNVg" +
        "8x1YiSBq3KNlQfYNns6KGYxmSGB7AiBNC/NR2TB8fVvaNTQdqEcbY6WFZTytTySn502vQX3xvw==")

    private val ORIGINALS: List<ByteArray> by lazy {
        listOf(ORIG_RSA_KEY, ORIG_EC_KEY, ORIG_RSA_CERT, ORIG_RSA_ROOT, ORIG_EC_CERT, ORIG_EC_ROOT)
    }

    /** The certificate content, exactly as the user typed it. Empty fields are omitted. */
    class SubjectForm(
        val cn: String = "", val o: String = "", val ou: String = "",
        val c: String = "", val st: String = "", val l: String = "",
    ) {
        fun isBlank(): Boolean = listOf(cn, o, ou, c, st, l).all { it.isBlank() }
        fun x500(): X500Name {
            val b = X500NameBuilder(BCStyle.INSTANCE)
            fun add(style: ASN1ObjectIdentifier, v: String) {
                if (v.isNotBlank()) b.addRDN(style, v.trim())
            }
            add(BCStyle.C, c); add(BCStyle.ST, st); add(BCStyle.L, l)
            add(BCStyle.O, o); add(BCStyle.OU, ou); add(BCStyle.CN, cn)
            return b.build()
        }
    }

    /** One full chain: an RSA family and an EC family, each root + attestation pair. */
    class Material(
        val rsaRootKey: KeyPair, val rsaRootCert: X509Certificate,
        val rsaAttestKey: KeyPair, val rsaAttestCert: X509Certificate,
        val ecRootKey: KeyPair, val ecRootCert: X509Certificate,
        val ecAttestKey: KeyPair, val ecAttestCert: X509Certificate,
    ) {
        /** The six slot blobs in file order, each already at its slot length. */
        fun blobs(): List<ByteArray> = listOf(
            rsaPkcs1Der(rsaAttestKey.private), ecSec1Der(ecAttestKey),
            rsaAttestCert.encoded, rsaRootCert.encoded,
            ecAttestCert.encoded, ecRootCert.encoded)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** PKCS#1 DER of an RSA private key (the library's RSA slot format). */
    fun rsaPkcs1Der(key: PrivateKey): ByteArray {
        val k = key as RSAPrivateCrtKey
        return BcRsaPrivateKey(
            k.modulus, k.publicExponent, k.privateExponent,
            k.primeP, k.primeQ, k.primeExponentP, k.primeExponentQ, k.crtCoefficient).encoded
    }

    private fun big32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** SEC1 DER of a P-256 private key with named-curve parameters and public key (the EC slot format). */
    fun ecSec1Der(pair: KeyPair): ByteArray {
        val priv = pair.private as ECPrivateKey
        val w = (pair.public as ECPublicKey).w
        val point = byteArrayOf(4) + big32(w.affineX) + big32(w.affineY)
        return BcEcPrivateKey(256, priv.s,
            org.bouncycastle.asn1.DERBitString(point), P256_OID).encoded
    }

    private fun rsaPair(bits: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA", BC).apply { initialize(bits, RAND) }.generateKeyPair()

    private fun ecPair(): KeyPair =
        KeyPairGenerator.getInstance("EC", BC).apply {
            initialize(ECGenParameterSpec("secp256r1"), RAND)
        }.generateKeyPair()

    /** The RSA attestation slot only fits a 1024-bit key whose PKCS#1 form is exactly the slot length. */
    private fun rsaAttestPairExact(): KeyPair {
        repeat(5000) {
            val kp = rsaPair(1024)
            if (rsaPkcs1Der(kp.private).size == LEN_RSA_KEY) return kp
        }
        fail(KmException.Kind.LENGTH, Part.RSA_ATTEST)
    }

    private fun serial(): BigInteger {
        val b = ByteArray(8)
        RAND.nextBytes(b)
        b[0] = (b[0].toInt() and 0x7f).toByte()
        val v = BigInteger(1, b)
        return if (v.signum() == 0) BigInteger.ONE else v
    }

    /**
     * One certificate of the chain, with the extension profile the library's
     * originals carry: roots are CAs without a path-length limit, attestation
     * certificates are CAs with pathlen 0, both sign and certify. [filler] is
     * the padding knob [certExact] turns to land on the slot length.
     */
    private fun buildCert(
        issuer: X500Name, issuerKey: PrivateKey, issuerPub: PublicKey,
        subject: X500Name, subjectPub: PublicKey, serial: BigInteger,
        notBefore: Date, notAfter: Date, attest: Boolean, alg: String, filler: Int,
    ): X509Certificate {
        val b = JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, subjectPub)
        val ext = JcaX509ExtensionUtils()
        b.addExtension(Extension.basicConstraints, true,
            if (attest) BasicConstraints(0) else BasicConstraints(true))
        b.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign))
        b.addExtension(Extension.subjectKeyIdentifier, false,
            ext.createSubjectKeyIdentifier(subjectPub))
        b.addExtension(Extension.authorityKeyIdentifier, false,
            ext.createAuthorityKeyIdentifier(issuerPub))
        if (filler > 0) b.addExtension(FILLER_OID, false, DEROctetString(ByteArray(filler)))
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(alg)
            .setProvider(BC).build(issuerKey)
        return JcaX509CertificateConverter().setProvider(BC).getCertificate(b.build(signer))
    }

    /** Rebuilds [build] with growing filler until the DER is exactly [target] bytes. */
    private fun certExact(target: Int, part: Part, build: (Int) -> X509Certificate): X509Certificate {
        var f = 0
        repeat(100) {
            val cert = build(f)
            val len = cert.encoded.size
            if (len == target) return cert
            val next = f + (target - len)
            if (next < 0) fail(KmException.Kind.TOO_LONG, part)
            f = next
        }
        fail(KmException.Kind.LENGTH, part)
    }

    /**
     * Builds a whole chain from the user's own certificate content. The
     * attestation certificates carry the same name the user gave the root;
     * the two are told apart by serial and by the path-length constraint,
     * never by an added word.
     */
    fun generate(form: SubjectForm, years: Int): Material {
        if (form.isBlank()) fail(KmException.Kind.MISSING)
        if (years !in 1..50) fail(KmException.Kind.YEARS)
        val name = form.x500()
        val now = System.currentTimeMillis()
        val nb = Date(now - 86_400_000L)
        val na = Date(now + years * 365L * 86_400_000L)

        val rsaRootKp = rsaPair(1024)
        val rsaRootSerial = serial()
        val rsaRootCert = certExact(LEN_RSA_ROOT, Part.RSA_ROOT) { f ->
            buildCert(name, rsaRootKp.private, rsaRootKp.public, name, rsaRootKp.public,
                rsaRootSerial, nb, na, false, "SHA256withRSA", f)
        }
        val rsaAttestKp = rsaAttestPairExact()
        val rsaAttestSerial = serial()
        val rsaAttestCert = certExact(LEN_RSA_CERT, Part.RSA_ATTEST) { f ->
            buildCert(name, rsaRootKp.private, rsaRootKp.public, name, rsaAttestKp.public,
                rsaAttestSerial, nb, na, true, "SHA256withRSA", f)
        }
        val ecRootKp = ecPair()
        val ecRootSerial = serial()
        val ecRootCert = certExact(LEN_EC_ROOT, Part.EC_ROOT) { f ->
            buildCert(name, ecRootKp.private, ecRootKp.public, name, ecRootKp.public,
                ecRootSerial, nb, na, false, "SHA256withECDSA", f)
        }
        val ecAttestKp = ecPair()
        if (ecSec1Der(ecAttestKp).size != LEN_EC_KEY) fail(KmException.Kind.LENGTH, Part.EC_ATTEST)
        val ecAttestSerial = serial()
        val ecAttestCert = certExact(LEN_EC_CERT, Part.EC_ATTEST) { f ->
            buildCert(name, ecRootKp.private, ecRootKp.public, name, ecAttestKp.public,
                ecAttestSerial, nb, na, true, "SHA256withECDSA", f)
        }
        return Material(rsaRootKp, rsaRootCert, rsaAttestKp, rsaAttestCert,
            ecRootKp, ecRootCert, ecAttestKp, ecAttestCert)
    }

    // ---- material store (the guest's own folder on the host) ----

    fun storeDir(paths: VmPaths): File = File(paths.dir, DIR_NAME).apply { mkdirs() }

    fun soFile(paths: VmPaths): File = File(paths.root, SO_REL)

    private fun backupFile(paths: VmPaths): File = File(storeDir(paths), BACKUP_NAME)

    class StoredMeta(
        val rootSubject: String, val rsaRootFp: String, val ecRootFp: String,
        val createdAt: Long, val source: String,
        var implantedSoSha: String? = null, var implantedAt: Long = 0,
    )

    private fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    /** The export bundle: the whole chain and every private key, as PEM. */
    fun bundlePem(m: Material): String = buildString {
        append(pem("CERTIFICATE", m.rsaRootCert.encoded))
        append(pem("CERTIFICATE", m.rsaAttestCert.encoded))
        append(pem("CERTIFICATE", m.ecRootCert.encoded))
        append(pem("CERTIFICATE", m.ecAttestCert.encoded))
        append(pem("PRIVATE KEY", m.rsaRootKey.private.encoded))
        append(pem("PRIVATE KEY", m.rsaAttestKey.private.encoded))
        append(pem("PRIVATE KEY", m.ecRootKey.private.encoded))
        append(pem("PRIVATE KEY", m.ecAttestKey.private.encoded))
    }

    private fun metaFile(paths: VmPaths): File = File(storeDir(paths), "meta.txt")

    private fun writeMeta(paths: VmPaths, meta: StoredMeta) {
        fun enc(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        metaFile(paths).writeText(buildString {
            append("subject=${enc(meta.rootSubject)}\n")
            append("rsafp=${meta.rsaRootFp}\n")
            append("ecfp=${meta.ecRootFp}\n")
            append("created=${meta.createdAt}\n")
            append("source=${meta.source}\n")
            append("implanted=${meta.implantedSoSha ?: ""}\n")
            append("implantedat=${meta.implantedAt}\n")
        })
    }

    fun readMeta(paths: VmPaths): StoredMeta? {
        val f = metaFile(paths)
        if (!f.isFile) return null
        val kv = HashMap<String, String>()
        for (line in f.readLines()) {
            val i = line.indexOf('=')
            if (i > 0) kv[line.substring(0, i)] = line.substring(i + 1)
        }
        fun dec(s: String?): String = runCatching {
            String(Base64.getDecoder().decode(s ?: ""), Charsets.UTF_8)
        }.getOrDefault("")
        return StoredMeta(
            rootSubject = dec(kv["subject"]),
            rsaRootFp = kv["rsafp"] ?: return null,
            ecRootFp = kv["ecfp"] ?: return null,
            createdAt = kv["created"]?.toLongOrNull() ?: 0,
            source = kv["source"] ?: "",
            implantedSoSha = kv["implanted"]?.ifBlank { null },
            implantedAt = kv["implantedat"]?.toLongOrNull() ?: 0,
        )
    }

    /** Persists [m] as this guest's chain material and returns its meta record. */
    fun saveMaterial(paths: VmPaths, m: Material, source: String): StoredMeta {
        val dir = storeDir(paths)
        fun w(name: String, bytes: ByteArray) = File(dir, name).writeBytes(bytes)
        w("rsa_root_key.der", m.rsaRootKey.private.encoded)
        w("rsa_attest_key.der", m.rsaAttestKey.private.encoded)
        w("ec_root_key.der", m.ecRootKey.private.encoded)
        w("ec_attest_key.der", m.ecAttestKey.private.encoded)
        w("rsa_root_cert.der", m.rsaRootCert.encoded)
        w("rsa_attest_cert.der", m.rsaAttestCert.encoded)
        w("ec_root_cert.der", m.ecRootCert.encoded)
        w("ec_attest_cert.der", m.ecAttestCert.encoded)
        File(dir, "bundle.pem").writeText(bundlePem(m))
        val old = readMeta(paths)
        val meta = StoredMeta(
            rootSubject = m.rsaRootCert.subjectX500Principal.name,
            rsaRootFp = sha256Hex(m.rsaRootCert.encoded),
            ecRootFp = sha256Hex(m.ecRootCert.encoded),
            createdAt = System.currentTimeMillis(),
            source = source,
            implantedSoSha = old?.implantedSoSha, implantedAt = old?.implantedAt ?: 0,
        )
        writeMeta(paths, meta)
        return meta
    }

    private fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509", BC)
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun parseKey(alg: String, pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance(alg, BC).generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun loadMaterial(paths: VmPaths): Material? {
        val dir = storeDir(paths)
        fun r(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()
        val rsaRootCert = r("rsa_root_cert.der")?.let { runCatching { parseCert(it) }.getOrNull() } ?: return null
        val rsaAttestCert = r("rsa_attest_cert.der")?.let { runCatching { parseCert(it) }.getOrNull() } ?: return null
        val ecRootCert = r("ec_root_cert.der")?.let { runCatching { parseCert(it) }.getOrNull() } ?: return null
        val ecAttestCert = r("ec_attest_cert.der")?.let { runCatching { parseCert(it) }.getOrNull() } ?: return null
        val rsaRootKey = r("rsa_root_key.der")?.let { runCatching { parseKey("RSA", it) }.getOrNull() } ?: return null
        val rsaAttestKey = r("rsa_attest_key.der")?.let { runCatching { parseKey("RSA", it) }.getOrNull() } ?: return null
        val ecRootKey = r("ec_root_key.der")?.let { runCatching { parseKey("EC", it) }.getOrNull() } ?: return null
        val ecAttestKey = r("ec_attest_key.der")?.let { runCatching { parseKey("EC", it) }.getOrNull() } ?: return null
        return Material(
            KeyPair(rsaRootCert.publicKey, rsaRootKey), rsaRootCert,
            KeyPair(rsaAttestCert.publicKey, rsaAttestKey), rsaAttestCert,
            KeyPair(ecRootCert.publicKey, ecRootKey), ecRootCert,
            KeyPair(ecAttestCert.publicKey, ecAttestKey), ecAttestCert)
    }

    fun exportPem(paths: VmPaths): ByteArray? {
        val f = File(storeDir(paths), "bundle.pem")
        return if (f.isFile) f.readBytes() else null
    }

    // ---- implant / restore / status ----

    /** Offsets of the six original blobs inside [so], or null when the shape is not the known one. */
    fun locateOriginals(so: ByteArray): IntArray? {
        val out = IntArray(ORIGINALS.size)
        var from = 0
        for (i in ORIGINALS.indices) {
            val needle = ORIGINALS[i]
            val at = indexOf(so, needle, from)
            if (at < 0) return null
            if (indexOf(so, needle, at + 1) >= 0) return null // must appear exactly once
            out[i] = at
            from = at + needle.size
        }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        var i = maxOf(from, 0)
        while (i <= last) {
            if (haystack[i] == needle[0]) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun spansEqual(a: ByteArray, spans: IntArray, blobs: List<ByteArray>): Boolean {
        for (i in spans.indices) {
            val b = blobs[i]
            if (spans[i] + b.size > a.size) return false
            for (j in b.indices) if (a[spans[i] + j] != b[j]) return false
        }
        return true
    }

    private fun equalOutside(a: ByteArray, b: ByteArray, spans: IntArray): Boolean {
        if (a.size != b.size) return false
        val inside = BooleanArray(a.size)
        for (i in spans.indices) for (j in 0 until SLOT_LENS[i]) inside[spans[i] + j] = true
        for (i in a.indices) if (!inside[i] && a[i] != b[i]) return false
        return true
    }

    private fun certSpansParse(so: ByteArray, spans: IntArray): Boolean {
        for (i in 2..5) {
            val der = so.copyOfRange(spans[i], spans[i] + SLOT_LENS[i])
            if (runCatching { parseCert(der) }.getOrNull() == null) return false
        }
        return true
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    enum class ImplantResult { DONE, ALREADY }

    /**
     * Writes the stored chain into this guest's library, at the six slots
     * only. The pristine library is backed up before the first write; a
     * library that does not match the known shape — originals not findable
     * in it or in the backup, or bytes outside the slots differing — is
     * refused untouched.
     */
    fun implant(paths: VmPaths): ImplantResult {
        val so = soFile(paths)
        if (!so.isFile) fail(KmException.Kind.NO_SO)
        val material = loadMaterial(paths) ?: fail(KmException.Kind.NO_MATERIAL)
        val blobs = material.blobs()
        for (i in blobs.indices) if (blobs[i].size != SLOT_LENS[i]) fail(KmException.Kind.LENGTH)
        val cur = so.readBytes()
        val backup = backupFile(paths)
        val spans: IntArray
        if (backup.isFile) {
            val bak = backup.readBytes()
            spans = locateOriginals(bak) ?: fail(KmException.Kind.SHAPE)
            if (cur.size != bak.size || !equalOutside(cur, bak, spans)) fail(KmException.Kind.SHAPE)
            if (!certSpansParse(cur, spans)) fail(KmException.Kind.SHAPE)
            if (spansEqual(cur, spans, blobs)) return ImplantResult.ALREADY
        } else {
            spans = locateOriginals(cur) ?: fail(KmException.Kind.SHAPE)
            backup.writeBytes(cur)
        }
        val out = cur.copyOf()
        for (i in spans.indices) blobs[i].copyInto(out, spans[i])
        writeAtomic(so, out)
        val meta = readMeta(paths)
        if (meta != null) {
            meta.implantedSoSha = sha256Hex(out)
            meta.implantedAt = System.currentTimeMillis()
            writeMeta(paths, meta)
        }
        return ImplantResult.DONE
    }

    /** Puts the backed-up original library back, byte for byte. */
    fun restore(paths: VmPaths) {
        val so = soFile(paths)
        if (!so.isFile) fail(KmException.Kind.NO_SO)
        val backup = backupFile(paths)
        if (!backup.isFile) fail(KmException.Kind.NO_BACKUP)
        writeAtomic(so, backup.readBytes())
        val meta = readMeta(paths)
        if (meta != null) {
            meta.implantedSoSha = null
            meta.implantedAt = 0
            writeMeta(paths, meta)
        }
    }

    enum class SoState { NO_SO, PRISTINE, IMPLANTED_THIS, IMPLANTED_OTHER, CHANGED }

    class Status(val meta: StoredMeta?, val soState: SoState, val hasBackup: Boolean)

    fun status(paths: VmPaths): Status {
        val meta = readMeta(paths)
        val so = soFile(paths)
        val backup = backupFile(paths)
        if (!so.isFile) return Status(meta, SoState.NO_SO, backup.isFile)
        val cur = so.readBytes()
        val curSha = sha256Hex(cur)
        val state = when {
            backup.isFile && curSha == sha256Hex(backup.readBytes()) -> SoState.PRISTINE
            meta?.implantedSoSha != null && curSha == meta.implantedSoSha -> {
                val m = loadMaterial(paths)
                val spans = if (backup.isFile) locateOriginals(backup.readBytes()) else null
                if (m != null && spans != null && spansEqual(cur, spans, m.blobs()))
                    SoState.IMPLANTED_THIS else SoState.IMPLANTED_OTHER
            }
            !backup.isFile && locateOriginals(cur) != null -> SoState.PRISTINE
            else -> SoState.CHANGED
        }
        return Status(meta, state, backup.isFile)
    }

    // ---- import ----

    private class Parsed(val certs: List<X509Certificate>, val keys: List<PrivateKey>)

    private fun parsePem(bytes: ByteArray, password: CharArray?): Parsed {
        val certs = ArrayList<X509Certificate>()
        val keys = ArrayList<PrivateKey>()
        val converter = JcaPEMKeyConverter().setProvider(BC)
        val text = String(bytes, Charsets.UTF_8)
        PEMParser(StringReader(text)).use { parser ->
            while (true) {
                val obj = parser.readObject() ?: break
                when (obj) {
                    is org.bouncycastle.cert.X509CertificateHolder ->
                        certs += JcaX509CertificateConverter().setProvider(BC).getCertificate(obj)
                    is PEMKeyPair -> keys += converter.getKeyPair(obj).private
                    is org.bouncycastle.asn1.pkcs.PrivateKeyInfo ->
                        keys += converter.getPrivateKey(obj)
                    is PEMEncryptedKeyPair -> {
                        if (password == null) fail(KmException.Kind.PASSWORD)
                        val dec = JcePEMDecryptorProviderBuilder().setProvider(BC).build(password)
                        keys += runCatching { converter.getKeyPair(obj.decryptKeyPair(dec)).private }
                            .getOrElse { fail(KmException.Kind.PASSWORD) }
                    }
                    is PKCS8EncryptedPrivateKeyInfo -> {
                        if (password == null) fail(KmException.Kind.PASSWORD)
                        val inp = JcePKCSPBEInputDecryptorProviderBuilder().setProvider(BC)
                            .build(password)
                        keys += runCatching {
                            converter.getPrivateKey(obj.decryptPrivateKeyInfo(inp))
                        }.getOrElse { fail(KmException.Kind.PASSWORD) }
                    }
                }
            }
        }
        return Parsed(certs, keys)
    }

    private fun parsePkcs12(bytes: ByteArray, password: CharArray?): Parsed {
        val ks = KeyStore.getInstance("PKCS12", BC)
        try {
            ks.load(ByteArrayInputStream(bytes), password ?: CharArray(0))
        } catch (e: Exception) {
            fail(KmException.Kind.PASSWORD)
        }
        val certs = ArrayList<X509Certificate>()
        val keys = ArrayList<PrivateKey>()
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            runCatching { ks.getKey(alias, password ?: CharArray(0)) as? PrivateKey }
                .getOrNull()?.let { keys += it }
            runCatching { ks.getCertificate(alias) as? X509Certificate }
                .getOrNull()?.let { certs += it }
            runCatching { ks.getCertificateChain(alias) }
                .getOrNull()?.forEach { (it as? X509Certificate)?.let { c -> certs += c } }
        }
        return Parsed(certs, keys)
    }

    private fun parseBundle(bytes: ByteArray, password: CharArray?): Parsed {
        val looksPem = runCatching {
            String(bytes, Charsets.UTF_8).contains("-----BEGIN")
        }.getOrDefault(false)
        if (looksPem) {
            val p = parsePem(bytes, password)
            if (p.certs.isEmpty() && p.keys.isEmpty()) fail(KmException.Kind.NOT_BUNDLE)
            return p
        }
        val p = parsePkcs12(bytes, password)
        if (p.certs.isEmpty() && p.keys.isEmpty()) fail(KmException.Kind.NOT_BUNDLE)
        return p
    }

    /** True when [key] signs for [pub]: a sign/verify round trip, no key-format assumptions. */
    private fun keyMatches(key: PrivateKey, pub: PublicKey): Boolean = runCatching {
        val alg = when (pub) {
            is RSAPublicKey -> "SHA256withRSA"
            is ECPublicKey -> "SHA256withECDSA"
            else -> return false
        }
        val probe = byteArrayOf(0x4B, 0x6D, 0x52, 0x6F, 0x6F, 0x74)
        val s = Signature.getInstance(alg, BC)
        s.initSign(key); s.update(probe)
        val sig = s.sign()
        val v = Signature.getInstance(alg, BC)
        v.initVerify(pub); v.update(probe)
        v.verify(sig)
    }.getOrDefault(false)

    private class Family(
        val rootKey: PrivateKey, val rootCert: X509Certificate,
        val attestKey: PrivateKey, val attestCert: X509Certificate,
    )

    /**
     * Sorts one family's four pieces out of a parsed bundle and checks them:
     * the root must be self-signed, the attestation certificate must verify
     * against it, and both private keys must match their certificates. Any
     * break is reported with the piece it belongs to.
     */
    private fun assembleFamily(rsa: Boolean, certs: List<X509Certificate>, keys: List<PrivateKey>): Family {
        val partRoot = if (rsa) Part.RSA_ROOT else Part.EC_ROOT
        val partAttest = if (rsa) Part.RSA_ATTEST else Part.EC_ATTEST
        val famCerts = certs.filter {
            if (rsa) it.publicKey is RSAPublicKey else it.publicKey is ECPublicKey
        }
        // The root is the self-signed certificate that actually signs the
        // attestation certificate. Names alone cannot tell the two apart —
        // a chain made here gives both the user's one name — so the pieces
        // are sorted by signature: self-verifying candidates are tried as
        // roots, and the attestation certificate is the other one, with the
        // root's name as issuer, that verifies against the root's key.
        var root: X509Certificate? = null
        var attest: X509Certificate? = null
        var namedButBadSig = false
        for (cand in famCerts) {
            if (runCatching { cand.verify(cand.publicKey) }.isFailure) continue
            val a = famCerts.firstOrNull {
                !it.encoded.contentEquals(cand.encoded) &&
                    it.issuerX500Principal == cand.subjectX500Principal &&
                    runCatching { it.verify(cand.publicKey) }.isSuccess
            }
            if (a != null) { root = cand; attest = a; break }
            if (famCerts.any {
                    !it.encoded.contentEquals(cand.encoded) &&
                        it.issuerX500Principal == cand.subjectX500Principal
                }) namedButBadSig = true
        }
        if (root == null) {
            if (namedButBadSig) fail(KmException.Kind.CHAIN, partAttest)
            if (famCerts.none { runCatching { it.verify(it.publicKey) }.isSuccess })
                fail(KmException.Kind.MISSING, partRoot)
            fail(KmException.Kind.MISSING, partAttest)
        }
        val rootCert = root!!
        val attestCert = attest!!
        // (BC reports its EC private keys' algorithm as "ECDSA", the JCA name is "EC".)
        val famKeys = keys.filter {
            if (rsa) it.algorithm == "RSA" else it.algorithm == "EC" || it.algorithm == "ECDSA"
        }
        val attestKey = famKeys.firstOrNull { keyMatches(it, attestCert.publicKey) }
            ?: fail(KmException.Kind.KEY_MATCH, partAttest)
        val rootKey = famKeys.firstOrNull { keyMatches(it, rootCert.publicKey) }
            ?: fail(KmException.Kind.ROOT_KEY, partRoot)
        if (rsa) {
            val rk = rootKey as? RSAPrivateCrtKey ?: fail(KmException.Kind.KEY_SIZE, partRoot)
            if (rk.modulus.bitLength() > 1024) fail(KmException.Kind.KEY_SIZE, partRoot)
            val ak = attestKey as? RSAPrivateCrtKey ?: fail(KmException.Kind.KEY_SIZE, partAttest)
            if (ak.modulus.bitLength() != 1024 || rsaPkcs1Der(ak).size != LEN_RSA_KEY)
                fail(KmException.Kind.KEY_SIZE, partAttest)
        } else {
            if (!isP256(rootCert.publicKey) || !isP256(attestCert.publicKey))
                fail(KmException.Kind.KEY_SIZE, partAttest)
            val sec1 = runCatching { ecSec1Der(KeyPair(attestCert.publicKey, attestKey)) }.getOrNull()
            if (sec1 == null || sec1.size != LEN_EC_KEY) fail(KmException.Kind.KEY_SIZE, partAttest)
        }
        return Family(rootKey, rootCert, attestKey, attestCert)
    }

    private fun isP256(pub: PublicKey): Boolean {
        val ec = pub as? ECPublicKey ?: return false
        return ec.params.curve.field.fieldSize == 256 && ec.params.order.bitLength() == 256
    }

    private class Rebuilt(
        val rootKey: KeyPair, val rootCert: X509Certificate,
        val attestKey: KeyPair, val attestCert: X509Certificate,
    )

    /**
     * Re-issues an imported family's certificates from its own keys and
     * names, at the library's fixed slot lengths — an imported certificate
     * cannot be padded after the fact without breaking its signature, so
     * the imported root key re-signs both certificates with the filler in
     * place. Serials and validity are the imported ones.
     */
    private fun rebuild(rsa: Boolean, fam: Family): Rebuilt {
        val alg = if (rsa) "SHA256withRSA" else "SHA256withECDSA"
        val rootName = X500Name.getInstance(fam.rootCert.subjectX500Principal.encoded)
        val rootCert = certExact(
            if (rsa) LEN_RSA_ROOT else LEN_EC_ROOT,
            if (rsa) Part.RSA_ROOT else Part.EC_ROOT) { f ->
            buildCert(rootName, fam.rootKey, fam.rootCert.publicKey,
                rootName, fam.rootCert.publicKey,
                fam.rootCert.serialNumber, fam.rootCert.notBefore, fam.rootCert.notAfter,
                false, alg, f)
        }
        val attestName = X500Name.getInstance(fam.attestCert.subjectX500Principal.encoded)
        val attestCert = certExact(
            if (rsa) LEN_RSA_CERT else LEN_EC_CERT,
            if (rsa) Part.RSA_ATTEST else Part.EC_ATTEST) { f ->
            buildCert(rootName, fam.rootKey, fam.rootCert.publicKey,
                attestName, fam.attestCert.publicKey,
                fam.attestCert.serialNumber, fam.attestCert.notBefore, fam.attestCert.notAfter,
                true, alg, f)
        }
        return Rebuilt(
            KeyPair(fam.rootCert.publicKey, fam.rootKey), rootCert,
            KeyPair(fam.attestCert.publicKey, fam.attestKey), attestCert)
    }

    /**
     * Imports a bundle (PEM or PKCS#12) as this guest's chain material.
     * Both families must be complete: root certificate, root private key,
     * attestation certificate and attestation private key for RSA and EC.
     */
    fun importBundle(paths: VmPaths, bytes: ByteArray, password: CharArray?): StoredMeta {
        val parsed = parseBundle(bytes, password)
        val r = rebuild(true, assembleFamily(true, parsed.certs, parsed.keys))
        val e = rebuild(false, assembleFamily(false, parsed.certs, parsed.keys))
        val m = Material(r.rootKey, r.rootCert, r.attestKey, r.attestCert,
            e.rootKey, e.rootCert, e.attestKey, e.attestCert)
        return saveMaterial(paths, m, "imported")
    }
}
