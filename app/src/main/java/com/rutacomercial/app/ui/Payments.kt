package com.rutacomercial.app.ui

import android.content.SharedPreferences
import java.util.Locale

/**
 * Pay per active hour. The rate is copied onto each task when it is assigned, so changing the
 * default later never rewrites what was already agreed. Pauses do not count: duration is active time.
 */
internal fun fee(activeMs: Long, ratePerHour: Long): Long = Math.round(activeMs * ratePerHour / 3_600_000.0)

internal fun money(value: Long): String = "$ " + String.format(Locale.forLanguageTag("es-AR"), "%,d", value)

/** What is owed for one task. [finished] when its latest report is final. */
internal data class Due(val taskId: String, val worker: String, val zone: String, val activeMs: Long,
    val rate: Long, val finished: Boolean, val paidAt: Long?, val vehicleMs: Long = 0, val mockMs: Long = 0,
    val clockMs: Long = 0, val outsideMs: Long = 0) {
    val unpaidMs get() = vehicleMs + mockMs + clockMs
    /** Active time minus riding, simulated location and clock jumps. */
    val payableMs get() = (activeMs - unpaidMs).coerceAtLeast(0)
    val amount get() = fee(payableMs, rate)
}

internal fun dues(tasks: List<WorkTask>, reports: List<WorkReport>, rateOf: (String) -> Long, paidOf: (String) -> Long?): List<Due> =
    tasks.mapNotNull { task ->
        val mine = reports.filter { it.taskId == task.id }
        val rate = rateOf(task.id)
        if (mine.isEmpty() || rate <= 0) return@mapNotNull null
        Due(task.id, task.worker, task.name, mine.sumOf { it.duration }, rate, mine.maxBy { it.updated }.state == "FINISHED", paidOf(task.id),
            mine.sumOf { it.vehicleMs }, mine.sumOf { it.mockMs }, mine.sumOf { it.clockMs }, mine.sumOf { it.outsideMs })
    }

/** Owner settings and paid marks live with the assignments, so the encrypted backup carries them. */
internal class PayBook(private val prefs: SharedPreferences) {
    var hourlyRate: Long
        get() = prefs.getLong("hourlyRate", 0L)
        set(v) { prefs.edit().putLong("hourlyRate", v.coerceAtLeast(0)).apply() }
    fun rate(taskId: String) = prefs.getLong("rate:$taskId", 0L)
    fun setRate(taskId: String, v: Long) { prefs.edit().putLong("rate:$taskId", v.coerceAtLeast(0)).apply() }
    /** Copies the current default onto a new task. */
    fun stamp(taskId: String) { if (hourlyRate > 0) setRate(taskId, hourlyRate) }
    fun paidAt(taskId: String): Long? = prefs.getLong("paid:$taskId", 0L).takeIf { it > 0 }
    fun setPaid(taskId: String, paid: Boolean) {
        prefs.edit().apply { if (paid) putLong("paid:$taskId", System.currentTimeMillis()) else remove("paid:$taskId") }.apply()
    }
}

internal fun parseRate(text: String): Long = text.filter { it.isDigit() }.take(9).toLongOrNull() ?: 0L

internal fun minutes(ms: Long): String = "${(ms + 30_000) / 60_000} min"
