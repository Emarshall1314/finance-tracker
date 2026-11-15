package ca.emarshall.finance.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Password encryption for backup files, so a backup can sit in Drive or email without exposing anything.
 *
 * File layout: the 6 bytes "FTBK01", a 16-byte salt, a 12-byte nonce, then AES-256-GCM ciphertext (with
 * its tag). The key is PBKDF2-HMAC-SHA256 of the password with [ITERATIONS] rounds. GCM authenticates the
 * whole file: a wrong password or a damaged file fails cleanly instead of producing garbage.
 */
object BackupCrypto {
    private val MAGIC = "FTBK01".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val ITERATIONS = 200_000
    const val MIN_PASSWORD = 8

    fun looksLikeBackup(bytes: ByteArray): Boolean = bytes.size > MAGIC.size + SALT_BYTES + NONCE_BYTES && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        if (password.size < MIN_PASSWORD) throw BackupException("Use a password of at least $MIN_PASSWORD characters.")
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + nonce + cipher.doFinal(plain)
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        if (!looksLikeBackup(file)) throw BackupException("This isn't a Finance Tracker backup file.")
        val salt = file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val nonce = file.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(file, MAGIC.size + SALT_BYTES + NONCE_BYTES, file.size - MAGIC.size - SALT_BYTES - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw BackupException("Wrong password, or the file is damaged.")
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
