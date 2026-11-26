package com.rutacomercial.app.ui

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

internal object OwnerIdentity {
    private const val alias = "rutacomercial-owner-v1"
    const val binding = "verifiedOwnerKey"
    private val fields = listOf("id","name","worker","polygon","note")
    private fun payload(task: JSONObject): ByteArray {
        require(task.getInt("version") in 2..3 && task.getString("kind")=="task")
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("RutaComercial/task/"+task.getInt("version"))
            for(field in if(task.getInt("version")==3) fields+listOf("qrCheck","pinCheck") else fields) {
                val value=task.getString(field).toByteArray(Charsets.UTF_8)
                out.writeInt(value.size)
                out.write(value)
            }
            for (field in listOf("sectorId", "previousSegments", "previousEvents", "previousGroups")) {
                if (task.has(field)) {
                    out.writeUTF(field)
                    val raw = task.getString(field).toByteArray(Charsets.UTF_8)
                    out.writeInt(raw.size)
                    out.write(raw)
                }
            }
        }
        return bytes.toByteArray()
    }

    @Synchronized fun sign(source: JSONObject): JSONObject {
        check(!com.rutacomercial.app.BuildConfig.WORKER_EDITION)
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
        }
        val task=JSONObject(source.toString()).put("version",3)
        task.put("qrCheck",digest(qr(task))).put("pinCheck",digest(task.getString("id")+":"+code(task)))
        val signer=Signature.getInstance("SHA256withECDSA")
        signer.initSign(store.getKey(alias,null) as PrivateKey)
        signer.update(payload(task))
        return task.put("ownerKey",Base64.getEncoder().encodeToString(store.getCertificate(alias).publicKey.encoded))
            .put("signature",Base64.getEncoder().encodeToString(signer.sign()))
    }

    fun verified(task: JSONObject): Boolean = runCatching {
        val key=Base64.getDecoder().decode(task.getString("ownerKey"))
        val verifier=Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
        verifier.update(payload(task))
        verifier.verify(Base64.getDecoder().decode(task.getString("signature")))
    }.getOrDefault(false)

    private fun digest(value:String)=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun secret(task:JSONObject,purpose:String):ByteArray {
        check(!com.rutacomercial.app.BuildConfig.WORKER_EDITION)
        val keyName="rutacomercial-task-approval"
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(keyName)) {
            javax.crypto.KeyGenerator.getInstance("HmacSHA256","AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyName,KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
        }
        return javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(store.getKey(keyName,null))
            doFinal((purpose+":"+task.getString("id")).toByteArray())
        }
    }
    fun qr(task:JSONObject)="rutacomercial:tarea:"+Base64.getEncoder().encodeToString(secret(task,"qr"))
    fun code(task:JSONObject):String {
        val bytes=secret(task,"pin")
        val number=((bytes[0].toInt() and 255) shl 16) or ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
        return String.format(Locale.ROOT,"%04d",number%10000)
    }
    fun matchesQr(task:JSONObject,value:String)=digest(value)==task.optString("qrCheck")
    fun matchesPin(task:JSONObject,value:String)=digest(task.getString("id")+":"+value)==task.optString("pinCheck")
    @Synchronized fun accept(prefs: SharedPreferences, task: JSONObject, confirmed:Boolean) {
        require(verified(task)) { "La firma de la tarea es inválida. Pedí el archivo original." }
        val current=prefs.getString(binding,null)
        val key=task.getString("ownerKey")
        require(confirmed) { "Confirmá esta tarea con su QR o código." }
        require(current==key || current==null) { "Esta tarea pertenece a otro Owner." }
        if(current==null) check(prefs.edit().putString(binding,key).commit()) { "No se pudo guardar la vinculación." }
    }
}
