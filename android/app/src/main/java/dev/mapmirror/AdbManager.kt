package dev.mapmirror

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import kotlin.random.Random

/**
 * Connects to this phone's own wireless debugging. Once paired, this key pair gives
 * shell access, so it's kept in the app's private storage and never leaves the phone.
 */
class AdbManager(ctx: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val keyFile = File(ctx.noBackupFilesDir, "adb.key")
        val certFile = File(ctx.noBackupFilesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            key = pair.private
            cert = selfSigned(pair.public, key)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "MapMirror"

    private fun selfSigned(public: java.security.PublicKey, private: PrivateKey): Certificate {
        val algorithm = "SHA512withRSA"
        val name = X500Name("CN=MapMirror")
        val from = Date()
        val to = Date(from.time + VALID_MS)
        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(public).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(from, to))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random.nextInt(Int.MAX_VALUE)))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(public))
            set("validity", CertificateValidity(from, to))
            set("issuer", CertificateIssuerName(name))
            set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(private, algorithm) }
    }

    private companion object {
        const val VALID_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
