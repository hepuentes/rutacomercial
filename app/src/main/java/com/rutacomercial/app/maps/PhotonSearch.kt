package com.rutacomercial.app.maps

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Free address search over OpenStreetMap (photon.komoot.io), biased toward where the map is. */
internal object PhotonSearch {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    fun search(query: String, near: MapCoordinate?): List<Place> {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", "6")
            .apply { near?.let { addQueryParameter("lat", it.latitude.toString()); addQueryParameter("lon", it.longitude.toString()) } }
            .build()
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", "RutaComercial-Android/0.22").build()).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        return parse(body)
    }

    fun parse(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val title = p.optString("name").ifBlank { street }.ifBlank { return@mapNotNull null }
            val area = listOf(street.takeIf { it != title }, p.optString("district").takeIf { it.isNotBlank() }, p.optString("city").takeIf { it.isNotBlank() })
                .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(if (area.isBlank()) title else "$title · $area", MapCoordinate(c.getDouble(1), c.getDouble(0)))
        }.distinct()
    }
}
