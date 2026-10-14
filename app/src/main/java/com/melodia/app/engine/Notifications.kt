package com.melodia.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.melodia.app.MainActivity
import com.melodia.app.R

object Notifications {
    const val CHANNEL_WORK = "generation"
    const val CHANNEL_DONE = "results"
    const val WORK_ID = 4201

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Making songs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a song is being generated"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished songs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a song is ready or failed"
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun progress(context: Context, title: String, text: String, cancel: PendingIntent?) =
        NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent(context))
            .apply { if (cancel != null) addAction(0, "Cancel", cancel) }
            .build()

    fun result(context: Context, songId: String, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(songId.hashCode(), n)
        }
    }
}
