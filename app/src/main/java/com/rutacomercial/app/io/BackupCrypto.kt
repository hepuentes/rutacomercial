package com.rutacomercial.app.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated backups. The password is never stored with the application. */
object BackupCrypto {
    const val MAX_PLAIN_BYTES = 32 * 1024 * 1024
    const val MAX_FILE_BYTES = 40 * 1024 * 1024
    private val magic = byteArrayOf(82, 67, 66, 49)
    private const val ITERATIONS = 600_000

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Usá una contraseña de al menos 12 caracteres." }
        require(plain.size <= MAX_PLAIN_BYTES) { "El respaldo supera el máximo de 32 MB de datos." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(plain) }
        }.toByteArray()
        val key = derive(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            return magic + salt + nonce + cipher.doFinal(compressed)
        } finally { key.fill(0); compressed.fill(0) }
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in 49..MAX_FILE_BYTES && file.copyOfRange(0, 4).contentEquals(magic)) {
            "El archivo no es un respaldo RutaComercial compatible."
        }
        val key = derive(password, file.copyOfRange(4, 20))
        val compressed: ByteArray
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, file.copyOfRange(20, 32)))
            cipher.updateAAD(magic)
            compressed = try { cipher.doFinal(file, 32, file.size - 32) }
            catch (_: Exception) { throw IllegalArgumentException("Contraseña incorrecta o respaldo alterado. No se modificó ningún dato.") }
        } finally { key.fill(0) }
        try {
            return GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_PLAIN_BYTES) { "El respaldo excede el tamaño permitido." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { compressed.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
