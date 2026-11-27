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
        assertEquals(LinkInvite(owner, key, "K7MQ2P"), parseLinkQr(linkQr(owner, key, "K7MQ2P")))
    }

    @Test fun rejectsOtherQrs() {
        assertNull(parseLinkQr("rutacomercial:tarea:abc"))
        assertNull(parseLinkQr(linkQr("corto", key, "K7MQ2P")))
        assertNull(parseLinkQr(linkQr(owner, "no-es-una-clave", "K7MQ2P")))
        assertNull(parseLinkQr(linkQr(owner, key, "K7MQ2P") + ":extra"))
        assertNull(parseLinkQr(linkQr(owner, key, "k7m-q2")))
        // QR from 0.19-0.23, without an invite, is no longer accepted.
        assertNull(parseLinkQr("rutacomercial:vincular:1:$owner:$key"))
    }
}
