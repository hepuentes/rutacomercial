package com.rutacomercial.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class WorkerLinkTest {
    private val key = Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val owner = "T4yjzl93YqUoj9wfWGAoxjVI9113"

    @Test fun roundTrip() {
        assertEquals(LinkInvite(owner, key), parseLinkQr(linkQr(owner, key)))
    }

    @Test fun rejectsOtherQrs() {
        assertNull(parseLinkQr("rutacomercial:tarea:abc"))
        assertNull(parseLinkQr(linkQr("corto", key)))
        assertNull(parseLinkQr(linkQr(owner, "no-es-una-clave")))
        assertNull(parseLinkQr(linkQr(owner, key) + ":extra"))
    }
}
