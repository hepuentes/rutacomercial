package com.rutacomercial.app.maps

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single HTTP client also bounds requests made by MapLibre's native offline worker. */
object MapBootstrap {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
    /** Shared so every open map switches at once; saved for the next start. */
    val dark = androidx.compose.runtime.mutableStateOf(false)
    private var prefs: android.content.SharedPreferences? = null
    fun styleUrl(dark: Boolean) = if (dark) DARK_STYLE_URL else STYLE_URL
    fun setDark(value: Boolean) { dark.value = value; prefs?.edit()?.putBoolean("dark", value)?.apply() }
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("map_style", Context.MODE_PRIVATE).also { dark.value = it.getBoolean("dark", false) }
        MapLibre.getInstance(context.applicationContext)
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "RutaComercial-Android/1.0 (com.rutacomercial.app)").build()
                val budget = DownloadBudget.current
                budget?.beforeRequest()
                val response = chain.proceed(request)
                val original = response.body
                if (budget == null) response else response.newBuilder().body(object : ResponseBody() {
                    private val limited: BufferedSource = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val count = super.read(sink, byteCount.coerceAtMost(8192L))
                            if (count > 0) budget.consume(count)
                            return count
                        }
                    }.buffer()
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = limited
                }).build()
            }.build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        initialized = true
    }
}

/** Conservative hard network budget: shared by visible maps and one explicit region download. */
internal class DownloadBudget {
    private var bytes = 0L
    private var requests = 0
    @Synchronized fun beforeRequest() {
        if (++requests > 2500 || bytes >= 45_000_000L) throw IOException("Límite seguro de descarga alcanzado")
    }
    @Synchronized fun consume(count: Long) {
        bytes += count
        if (bytes > 45_000_000L) throw IOException("Límite seguro de descarga alcanzado")
    }
    companion object { @Volatile var current: DownloadBudget? = null }
}
