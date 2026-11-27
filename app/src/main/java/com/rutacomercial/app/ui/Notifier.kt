package com.rutacomercial.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rutacomercial.app.R

/**
 * Notices from sync: new task, zone proposal, worker linked, journey finished. They arrive while the
 * app process is alive (always during a journey, thanks to the tracking service). ponytail: no FCM push,
 * that needs Cloud Functions on Firebase's paid plan; add it if notices must reach a closed app.
 */
internal object Notifier {
    private const val CHANNEL = "avisos"

    fun notify(context: Context, key: String, title: String, text: String, extras: Intent.() -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de tareas y recorridos", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Tareas nuevas, propuestas de zona y recorridos terminados" })
        val open = Intent(context, AssignmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras)
        val pending = PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(key.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tracking).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
