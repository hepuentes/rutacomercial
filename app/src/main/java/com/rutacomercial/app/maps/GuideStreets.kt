package com.rutacomercial.app.maps

import android.content.Context
import com.rutacomercial.app.domain.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object GuideStreets {
    private const val maxBytes=6_000_000L
    private val client=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
    private val mirrors=listOf("https://maps.mail.ru/osm/tools/overpass/api/interpreter","https://overpass-api.de/api/interpreter")
    private val pedestrianAccess=setOf("yes","designated","permissive","official")
    private val excludedAccess=setOf("no","private","customers","delivery","permit","agricultural","forestry","use_sidepath")
    private fun parse(body:String):List<GuideStreet> {
        val doc=JSONObject(body)
        if(doc.has("remark")) throw IOException("El servicio de calles está ocupado. Volvé a intentar.")
        val ways=doc.getJSONArray("elements")
        return buildList {
            for(i in 0 until ways.length()) {
                val way=ways.getJSONObject(i)
                val tags=way.optJSONObject("tags") ?: continue
                val foot=tags.optString("foot")
                if(foot in excludedAccess || (tags.optString("access") in excludedAccess && foot !in pedestrianAccess)
                    || tags.optString("motorroad")=="yes" || tags.optString("area")=="yes"
                    || tags.optString("oneway:foot") in setOf("yes","1","-1")
                    || tags.has("foot:conditional") || tags.has("access:conditional")) continue
                val ids=way.getJSONArray("nodes")
                val xy=way.getJSONArray("geometry")
                if(ids.length()!=xy.length()) continue
                val name=tags.optString("name").ifBlank { "Calle sin nombre" }
                val nodes=(0 until ids.length()).map { j ->
                    val p=xy.getJSONObject(j)
                    val lat=p.getDouble("lat")
                    val lon=p.getDouble("lon")
                    require(Geo.valid(lat,lon))
                    StreetNode(ids.getLong(j),MapCoordinate(lat,lon))
                }
                add(GuideStreet(name,nodes))
            }
        }
    }

    private fun boundsOf(zone:List<MapCoordinate>):String {
        val area=requireNotNull(DownloadArea.from(zone))
        require(area.widthM*area.heightM<=25_000_000 && maxOf(area.widthM,area.heightM)<=12_000) {
            "Dividí esta zona en sectores más pequeños para preparar la guía."
        }
        return listOf(area.south,area.west,area.north,area.east).joinToString(",")
    }
    /** Same zone, same file: Owner and worker compute the key from the same polygon. */
    private fun cacheFile(context:Context,zone:List<MapCoordinate>):File {
        val key=MessageDigest.getInstance("SHA-256").digest(boundsOf(zone).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir,"guide-streets-v1").apply { mkdirs() },"$key.json")
    }

    /** Owner: the raw street list to ship with the task, so the worker never depends on public servers. */
    suspend fun raw(context:Context,zone:List<MapCoordinate>):String = withContext(Dispatchers.IO) {
        load(context,zone)
        cacheFile(context,zone).readText()
    }

    /** Worker: keep the streets the Owner shipped; refuses anything that does not parse as a street list. */
    fun store(context:Context,zone:List<MapCoordinate>,body:String) {
        require(body.length<=maxBytes) ; parse(body)
        val file=cacheFile(context,zone)
        val temp=File.createTempFile("streets-",null,file.parentFile)
        try { temp.writeText(body); Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
        finally { temp.delete() }
    }

    suspend fun load(context:Context,zone:List<MapCoordinate>):List<GuideStreet> = withContext(Dispatchers.IO) {
        val bounds=boundsOf(zone)
        val file=cacheFile(context,zone)
        val dir=file.parentFile!!
        val cached=file.takeIf { it.isFile && it.length() in 1..maxBytes }?.let { runCatching { parse(it.readText()) }.getOrNull() }
        if(cached!=null && System.currentTimeMillis()-file.lastModified()<14L*86400000) return@withContext cached
        val types="residential|living_street|unclassified|tertiary|secondary|primary|pedestrian|footway"
        val query="""[out:json][timeout:20];way["highway"~"^($types)$"]["footway"!~"^(sidewalk|crossing)$"]($bounds);out body geom;"""
        try {
            // Public Overpass mirrors fail often; try the next one before giving up.
            var failure:IOException?=null
            val body=mirrors.firstNotNullOfOrNull { mirror ->
                try {
                    val url=mirror.toHttpUrl().newBuilder().addQueryParameter("data",query).build()
                    download(Request.Builder().url(url).header("User-Agent","RutaComercial-Android/0.18").header("Accept","application/json").build())
                } catch(e:IOException) { failure=e;null }
            } ?: throw failure ?: IOException("Servicio de calles temporalmente no disponible.")
            val streets=parse(body)
            val temp=File.createTempFile("streets-",null,dir)
            try {
                temp.writeText(body)
                Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
            dir.listFiles().orEmpty().filter { it.extension=="json" }.sortedByDescending { it.lastModified() }.drop(12).forEach { it.delete() }
            streets
        } catch(e:IOException) { cached ?: throw e }
    }

    private suspend fun download(request:Request):String = suspendCancellableCoroutine { result ->
        val call=client.newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { result.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val text=response.use {
                        if(!it.isSuccessful) throw IOException("Servicio de calles temporalmente no disponible.")
                        val source=it.body.source()
                        if(source.request(maxBytes+1)) throw IOException("La zona contiene demasiadas calles. Elegí un sector más pequeño.")
                        source.readUtf8()
                    }
                    result.resume(text)
                } catch(e:Exception) { result.resumeWithException(e) }
            }
        })
    }
}
