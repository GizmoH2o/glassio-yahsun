package com.glasslauncher.tv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup's lock, apart from Android (so the tests run on the computer): the contents gzipped, then AES-256-GCM
 * under a key made from the code (PBKDF2-HMAC-SHA256 with a random salt). GCM checks the whole file, so a wrong code
 * and a damaged file both end in [WrongCode] and nothing half-read is ever used.
 */
object BackupCrypto {
    const val ITERATIONS = 150_000

    /** A wrong code, or a file changed or cut short since it was made. */
    class WrongCode : Exception("Wrong code")

    class Sealed(val salt: ByteArray, val iv: ByteArray, val data: ByteArray)

    fun seal(payload: ByteArray, code: String, iterations: Int = ITERATIONS): Sealed {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(code, salt, iterations), GCMParameterSpec(128, iv)) }
        return Sealed(salt, iv, c.doFinal(gz))
    }

    fun open(sealed: Sealed, code: String, iterations: Int = ITERATIONS): ByteArray {
        val gz = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(code, sealed.salt, iterations), GCMParameterSpec(128, sealed.iv))
            }.doFinal(sealed.data)
        } catch (e: AEADBadTagException) {
            throw WrongCode()
        }
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    }

    private fun key(code: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
