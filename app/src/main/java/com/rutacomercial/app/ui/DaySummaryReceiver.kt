package com.rutacomercial.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Owner: one notice around 20 h with the day per worker. Inexact on purpose, it costs no battery. */
class DaySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val since = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val workers = workersToday(ZoneWork.read(File(context.filesDir, "assignments")).reports, since)
        if (workers.isEmpty()) return
        Notifier.notify(context, "summary:" + LocalDate.now(), "Resumen del día",
            daySummary(workers).removePrefix("Resumen de hoy\n")) { putExtra("openToday", true) }
    }

    companion object {
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var at = now.toLocalDate().atTime(LocalTime.of(20, 0))
            if (!at.isAfter(now)) at = at.plusDays(1)
            val pending = PendingIntent.getBroadcast(context, 20, Intent(context, DaySummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC,
                at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pending)
        }
    }
}
