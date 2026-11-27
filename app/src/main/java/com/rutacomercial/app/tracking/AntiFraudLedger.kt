package com.rutacomercial.app.tracking

import android.content.Context

/**
 * Per-journey totals the worker and the Owner both see. vehicle, mock and clock are not paid;
 * outside (zone left without choosing "Seguir fuera") is only informative.
 */
internal class AntiFraudLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("anti_fraud", Context.MODE_PRIVATE)
    fun add(journeyId: String, kind: String, ms: Long) {
        if (ms <= 0) return
        prefs.edit().putLong("$kind:$journeyId", get(journeyId, kind) + ms).apply()
    }
    fun get(journeyId: String, kind: String) = prefs.getLong("$kind:$journeyId", 0L)
    /** Clock jumps net out: set forward then corrected back costs the worker nothing. */
    fun addClock(journeyId: String, jumpMs: Long) { prefs.edit().putLong("$CLOCK:$journeyId", get(journeyId, CLOCK) + jumpMs).apply() }
    fun clock(journeyId: String) = get(journeyId, CLOCK).coerceAtLeast(0)
    /** Time that is not paid. */
    fun unpaid(journeyId: String) = get(journeyId, VEHICLE) + get(journeyId, MOCK) + clock(journeyId)

    companion object {
        const val VEHICLE = "vehicle"; const val MOCK = "mock"; const val CLOCK = "clock"; const val OUTSIDE = "outside"
        val KINDS = listOf(VEHICLE, MOCK, CLOCK, OUTSIDE)
    }
}
