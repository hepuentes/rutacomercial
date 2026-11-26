package com.rutacomercial.app.io

import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun authenticatedRoundTripAndRandomizedCiphertext() {
        val plain = "Datos privados de ubicación y visitas áéñ".toByteArray()
        val password = "frase larga de prueba".toCharArray()
        val first = BackupCrypto.encrypt(plain, password)
        val second = BackupCrypto.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, BackupCrypto.decrypt(first, password))
        assertFalse(String(first).contains("privados"))
    }
    @Test fun rejectsTamperingAndWrongPassword() {
        val password = "frase larga de prueba".toCharArray()
        val file = BackupCrypto.encrypt("datos".toByteArray(), password)
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(file, "otra contraseña".toCharArray()) }
        file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(file, password) }
    }
    @Test fun rejectsShortPasswordAndUnknownFormat() {
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt(byteArrayOf(1), "corta".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(ByteArray(80), "contraseña válida".toCharArray()) }
    }
    @Test fun neutralizesFormulaCsvAndQuotesSeparators() {
        assertEquals("\"'=SUM(A1)\"", Exports.csvCell("=SUM(A1)"))
        assertEquals("\"'  @SUM(A1)\"", Exports.csvCell("  @SUM(A1)"))
        assertEquals("\"Un; negocio \"\"A\"\"\"", Exports.csvCell("Un; negocio \"A\""))
    }
}
