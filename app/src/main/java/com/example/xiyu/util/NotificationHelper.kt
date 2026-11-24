package com.example.xiyu.util

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
import com.example.xiyu.MainActivity
import com.example.xiyu.data.database.AppDatabaseHelper
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {

    private const val CHANNEL_ACTIVITY_ID = "kruzenlab_activity_channel"
    private const val CHANNEL_ALERTS_ID = "kruzenlab_alerts_channel"
    const val EXTRA_NAVIGATE_TO = "navigate_to"

    private val idGenerator = AtomicInteger(9000)

    private fun ensureChannels(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activityChannel = NotificationChannel(
                CHANNEL_ACTIVITY_ID,
                "KruzenLab Aktivitas & Unduhan",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi hasil upload, download, dan operasi repositori"
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "KruzenLab Peringatan & Log Error",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Peringatan API GitHub (401, 404, 409, Rate Limit) beserta solusi"
            }
            manager.createNotificationChannel(activityChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        val enabled = runCatching {
            AppDatabaseHelper.getInstance(context).configState.value.enableSystemNotifications
        }.getOrDefault(true)
        if (!enabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (perm != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun buildContentIntent(context: Context, targetRoute: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO, targetRoute)
        }
        return PendingIntent.getActivity(
            context,
            targetRoute.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notifyActivity(
        context: Context,
        title: String,
        message: String,
        targetRoute: String = "downloads",
        isDownload: Boolean = false
    ) {
        if (!canPostNotifications(context)) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(context, manager)

            val smallIcon = if (isDownload) {
                android.R.drawable.stat_sys_download_done
            } else {
                android.R.drawable.stat_notify_more
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ACTIVITY_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent(context, targetRoute))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(idGenerator.incrementAndGet(), notification)
        }
    }

    fun notifyError(
        context: Context,
        title: String,
        message: String,
        targetRoute: String = "debug_panel"
    ) {
        if (!canPostNotifications(context)) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(context, manager)

            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(buildContentIntent(context, targetRoute))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            manager.notify(idGenerator.incrementAndGet(), notification)
        }
    }
}
