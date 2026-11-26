package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class FootRoute(val steps:List<GuideStep>,val distance:Double)
internal object FootRouter {
    private val http=OkHttpClient.Builder().callTimeout(18,TimeUnit.SECONDS).build()
    private val gate=Mutex()
    private var lastCall=0L

    suspend fun route(from:MapCoordinate,to:MapCoordinate):FootRoute=withContext(Dispatchers.IO) {
        val points=listOf(from,to).joinToString(";") { it.longitude.toString()+","+it.latitude }
        val req=Request.Builder().url("https://routing.openstreetmap.de/routed-foot/route/v1/driving/"+points+
            "?overview=false&steps=true&geometries=geojson&radiuses=100;120").header("User-Agent","RutaComercial-Android/0.17").build()
        gate.withLock {
            delay((1050L-(android.os.SystemClock.elapsedRealtime()-lastCall)).coerceAtLeast(0))
            lastCall=android.os.SystemClock.elapsedRealtime()
        }
        val raw=suspendCancellableCoroutine<String> { cont->
            val call=http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) { cont.resumeWithException(e) }
                override fun onResponse(call:Call,response:Response) {
                    try { val body=response.use {
                        if(!it.isSuccessful || it.body.source().request(2_000_001)) throw IOException("Ruta no disponible")
                        it.body.string()
                    };cont.resume(body) } catch(e:Exception) { cont.resumeWithException(e) }
                }
            })
        }
        val result=JSONObject(raw)
        require(result.optString("code")=="Ok")
        val route=result.getJSONArray("routes").getJSONObject(0)
        val legs=route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val steps=buildList {
            for(i in 0 until legs.length()) {
                val s=legs.getJSONObject(i)
                val coords=s.getJSONObject("geometry").getJSONArray("coordinates")
                val path=(0 until coords.length()).map { j->
                    val xy=coords.getJSONArray(j)
                    MapCoordinate(xy.getDouble(1),xy.getDouble(0)).also { require(Geo.valid(it.latitude,it.longitude)) }
                }.fold(mutableListOf<MapCoordinate>()) { acc,p->acc.apply { if(lastOrNull()!=p) add(p) } }
                if(path.size>=2) add(GuideStep(s.optString("name").ifBlank { "Tramo peatonal" },path,1))
            }
        }
        require(steps.isNotEmpty())
        FootRoute(steps,route.getDouble("distance"))
    }
}
