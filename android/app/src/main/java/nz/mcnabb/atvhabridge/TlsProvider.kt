package nz.mcnabb.atvhabridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * Self-signed TLS for the bridge. An RSA key + certificate are generated once in the
 * AndroidKeyStore, so the private key never leaves secure hardware/storage. There's no
 * CA (this is a LAN device with no domain) — instead Home Assistant pins the cert's
 * SHA-256 fingerprint at pairing, so every connection is encrypted AND MITM-proof.
 *
 * Connections land on TLS 1.3 (Conscrypt's default; NanoHTTPD gives no reliable way to
 * force a version). TLS 1.3 is all-ECDHE (no static-RSA decrypt) and needs an RSA-PSS
 * signature for CertificateVerify — hence PSS padding on the key.
 */
object TlsProvider {
    // v3: PSS + raw-RSA-capable key. The name bump forces a fresh key over any earlier
    // alias (which the purge in ensureKey() then deletes).
    private const val ALIAS = "atvhabridge_tls_v3"

    fun serverSocketFactory(): SSLServerSocketFactory {
        ensureKey()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx.serverSocketFactory
    }

    /** Lowercase hex SHA-256 of the DER certificate — what HA pins (aiohttp.Fingerprint). */
    fun fingerprintHex(): String {
        ensureKey()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun ensureKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // Purge any stale alias (the earlier EC key, the PKCS1-only RSA key). The TLS
        // KeyManager is handed the whole keystore, so a leftover cert could make Conscrypt
        // pick a suite this key can't satisfy. This app owns exactly one key.
        // ponytail: nuke-all-but-ALIAS is fine while there's a single key; revisit if more are added.
        ks.aliases().toList().filter { it != ALIAS }.forEach { runCatching { ks.deleteEntry(it) } }
        if (ks.containsAlias(ALIAS)) return
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        // Conscrypt treats an AndroidKeyStore RSA key as opaque: it pads the handshake
        // signature in software (PKCS1 or PSS) then does the RAW private-key op via
        // `Cipher RSA/ECB/NoPadding` in DECRYPT_MODE. So the key MUST allow PURPOSE_DECRYPT
        // + ENCRYPTION_PADDING_NONE, not just signing — this is what makes it usable as a
        // TLS server key at all. Signature paddings/digests kept for the direct-sign path.
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
            )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setCertificateSubject(X500Principal("CN=Android TV HA Bridge"))
            .setCertificateNotBefore(Calendar.getInstance().time)
            .setCertificateNotAfter(notAfter.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
