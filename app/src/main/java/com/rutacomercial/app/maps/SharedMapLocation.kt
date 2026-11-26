package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class SharedMapLocation(val point: MapCoordinate, val approximate: Boolean = false)

/** Extracts a point only, never businesses, route geometry or the user's live location. */
object SharedMapLinks {
    private const val NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"
    private val pair = Regex("^($NUMBER)\\s*,\\s*($NUMBER)(?:\\s*\\([^)]*\\))?$")
    private val places = Regex("!3d($NUMBER)!4d($NUMBER)(?=!|/|[?&#]|$)")
    private val camera = Regex("@($NUMBER),($NUMBER)(?:,|/|$)")
    private val hosts = setOf("google.com", "www.google.com", "maps.google.com", "google.com.ar", "www.google.com.ar", "maps.google.com.ar", "google.es", "www.google.es", "maps.google.es")
    const val HELP = "No se pudo obtener un punto de ese enlace. En Google Maps mantené pulsado el lugar y compartí el pin. También podés pegar sus coordenadas (latitud, longitud)."

    fun input(text: String): String {
        require(text.length in 1..8192) { HELP }
        if (coordinates(text.trim()) != null) return text.trim()
        val urls = Regex("(?:https?://|geo:)[^\\s<>]+").findAll(text).map { it.value.trimEnd('.', ';', ']') }.distinct().toList()
        require(urls.size == 1) { HELP }
        return urls.single()
    }

    fun coordinates(value: String): MapCoordinate? {
        val match = pair.matchEntire(value.trim().removePrefix("loc:")) ?: return null
        return point(match.groupValues[1], match.groupValues[2])
    }

    private fun point(lat: String, lon: String): MapCoordinate? {
        val a = lat.toDoubleOrNull() ?: return null
        val b = lon.toDoubleOrNull() ?: return null
        return if (Geo.valid(a, b)) MapCoordinate(a, b) else null
    }

    fun allowed(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        (url.host == "maps.app.goo.gl" || url.host == "goo.gl" && url.encodedPath.startsWith("/maps/") ||
            url.host in hosts && (url.host.startsWith("maps.") || url.encodedPath == "/maps" || url.encodedPath.startsWith("/maps/")))

    fun parse(value: String): SharedMapLocation? {
        coordinates(value)?.let { return SharedMapLocation(it) }
        if (value.startsWith("geo:")) {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            val raw = uri.rawSchemeSpecificPart
            val query = raw.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.substringAfter('=')
            // geo:0,0?q=Address must not silently become the Gulf of Guinea.
            return coordinates(if (query != null) decode(query) else raw.substringBefore('?').substringBefore(';'))?.let { SharedMapLocation(it) }
        }
        val url = value.toHttpUrlOrNull()?.takeIf(::allowed) ?: return null
        if (url.encodedPath.startsWith("/maps/dir") || url.queryParameter("destination") != null || url.queryParameter("origin") != null || url.queryParameter("map_action") == "pano") return null
        val text = decode(url.toString())
        val exact = places.findAll(text).mapNotNull { point(it.groupValues[1], it.groupValues[2]) }.distinct().toList()
        if (exact.size > 1) return null
        if (exact.size == 1) return SharedMapLocation(exact.single())
        listOf("query", "q").forEach { key -> url.queryParameter(key)?.let(::coordinates)?.let { return SharedMapLocation(it) } }
        // Do not substitute the camera for an unresolved named place/search result.
        if (url.encodedPath.contains("/place/") || url.encodedPath.contains("/search/") || url.queryParameter("query_place_id") != null || url.queryParameter("q") != null || url.queryParameter("query") != null) return null
        listOf("center", "ll").forEach { key -> url.queryParameter(key)?.let(::coordinates)?.let { return SharedMapLocation(it, true) } }
        return camera.find(text)?.let { point(it.groupValues[1], it.groupValues[2]) }?.let { SharedMapLocation(it, true) }
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}

/** Only public HTTPS Maps redirects, bounded in time/hops. No HTML scraping or Places API. */
class SharedMapResolver internal constructor(client: OkHttpClient = OkHttpClient(), private val lookup: (String) -> MapCoordinate? = { null }) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    fun resolve(text: String): SharedMapLocation {
        var value = SharedMapLinks.input(text)
        SharedMapLinks.parse(value)?.let { return it }
        fun named(url: HttpUrl?): SharedMapLocation? {
            if (url == null || !SharedMapLinks.allowed(url)) return null
            val parts = url.pathSegments
            if (parts.take(2) !in listOf(listOf("maps","place"), listOf("maps","search"))) return null
            val name = parts.getOrNull(2)?.replace('+', ' ')?.takeIf { it.isNotBlank() } ?: return null
            return lookup(name)?.let { SharedMapLocation(it,true) }
        }
        var url = value.toHttpUrlOrNull()
        named(url)?.let { return it }
        require(url != null && SharedMapLinks.allowed(url) && url.host in setOf("maps.app.goo.gl", "goo.gl")) { SharedMapLinks.HELP }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val seen = hashSetOf<String>()
        repeat(5) {
            val current = requireNotNull(url)
            require(SharedMapLinks.allowed(current) && seen.add(current.toString())) { SharedMapLinks.HELP }
            val remaining = deadline - System.nanoTime()
            require(remaining > 0) { "No se pudo abrir el enlace. Revisá internet o pegá las coordenadas." }
            val call = client.newCall(Request.Builder().url(current).header("User-Agent", "RutaComercial/0.3").build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                require(response.code in setOf(301, 302, 303, 307, 308)) { SharedMapLinks.HELP }
                url = response.header("Location")?.let { current.resolve(it) }
                require(url != null && SharedMapLinks.allowed(url!!)) { SharedMapLinks.HELP }
                value = url.toString()
                SharedMapLinks.parse(value)?.let { return it }
                named(url)?.let { return it }
            }
        }
        throw IllegalArgumentException(SharedMapLinks.HELP)
    }
}
