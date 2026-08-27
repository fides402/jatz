package com.jatz.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jatz.app.MainActivity
import com.jatz.app.R

const val CHANNEL_DAILY = "jatz_daily"
const val CHANNEL_PLAYBACK = "jatz_playback"

fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_DAILY, context.getString(R.string.notif_channel_daily),
            NotificationManager.IMPORTANCE_DEFAULT),
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_PLAYBACK, context.getString(R.string.notif_channel_playback),
            NotificationManager.IMPORTANCE_LOW),
    )
}

/** Called from [DailyFetchWorker] once new albums land — the "ready by 8am" moment. */
fun notifyNewDrop(context: Context, vintage: Int, modern: Int) {
    val intent = android.content.Intent(context, MainActivity::class.java)
    val pending = android.app.PendingIntent.getActivity(
        context, 0, intent,
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_DAILY)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("I dischi di oggi sono pronti")
        .setContentText("$vintage dischi vintage + $modern moderni ti aspettano in JATZ")
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()
    runCatching {
        NotificationManagerCompat.from(context).notify(1001, notification)
    }
}
