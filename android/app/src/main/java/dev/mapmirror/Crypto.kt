package dev.mapmirror

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The display's key checks, matching firmware/src/main.cpp. */
object Crypto {
    private val random = SecureRandom()

    fun nonce(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    /** HMAC-SHA256(key, label | parts...), with a 4-character label. */
    fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** The 6-digit code the display shows the first time this phone sees its key. */
    fun confirmCode(key: ByteArray, nonce: ByteArray): String {
        val m = hmac(key, "MMCF", nonce)
        val n = ((m[0].toLong() and 0xFF) shl 24) or ((m[1].toLong() and 0xFF) shl 16) or
            ((m[2].toLong() and 0xFF) shl 8) or (m[3].toLong() and 0xFF)
        val code = (n % 1_000_000).toInt()
        return "%03d %03d".format(code / 1000, code % 1000)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
