package com.rutacomercial.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OwnerIdentityTest {
    @Test fun pairingRequiresConfirmationAndChecksEverySignedTask() {
        val prefs=ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("pairing-test-"+UUID.randomUUID(),Context.MODE_PRIVATE)
        val original=JSONObject().put("kind","task").put("id",UUID.randomUUID().toString())
            .put("worker","Prueba").put("name","Zona").put("note","").put("polygon","[]")
        val signed=OwnerIdentity.sign(original)
        assertTrue(OwnerIdentity.verified(signed))
        assertTrue(OwnerIdentity.code(signed).matches(Regex("[0-9]{4}")))
        assertTrue(runCatching { OwnerIdentity.accept(prefs,signed,false) }.isFailure)
        OwnerIdentity.accept(prefs,signed,true)
        val next=OwnerIdentity.sign(original.put("id",UUID.randomUUID().toString()))
        assertTrue(runCatching { OwnerIdentity.accept(prefs,next,false) }.isFailure)
        assertFalse(OwnerIdentity.matchesQr(next,OwnerIdentity.qr(signed)))
        assertTrue(OwnerIdentity.matchesPin(next,OwnerIdentity.code(next)))
        OwnerIdentity.accept(prefs,next,true)
        for(field in listOf("name","worker","note","polygon","id","signature","ownerKey")) {
            assertFalse(OwnerIdentity.verified(JSONObject(signed.toString()).put(field,"alterado")))
        }
        prefs.edit().putString(OwnerIdentity.binding,"otro owner").commit()
        assertTrue(runCatching { OwnerIdentity.accept(prefs,signed,true) }.isFailure)
        val payload=OwnerIdentity.qr(signed)
        val bitmap=BarcodeEncoder().encodeBitmap(payload,BarcodeFormat.QR_CODE,480,480)
        val pixels=IntArray(480*480)
        bitmap.getPixels(pixels,0,480,0,0,480,480)
        val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(480,480,pixels))))
        assertEquals(payload,decoded.text)
    }
}
