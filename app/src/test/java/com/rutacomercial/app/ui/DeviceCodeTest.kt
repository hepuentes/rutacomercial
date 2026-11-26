package com.rutacomercial.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCodeTest {
    @Test fun extractsCodeFromSharedMessage() {
        val code = "T4yjzl93YqUoj9wfWGAoxjVI9113"
        assertEquals(code, deviceCode(code))
        assertEquals(code, deviceCode("Mi código de RutaComercial Repartidor: $code\n"))
        assertEquals("abc", deviceCode("  abc "))
    }
}
