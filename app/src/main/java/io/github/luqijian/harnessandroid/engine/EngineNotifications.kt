package io.github.luqijian.harnessandroid.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.luqijian.harnessandroid.MainActivity
import io.github.luqijian.harnessandroid.R

object EngineNotifications {
    const val CHANNEL_ID = "harness_engine"
    const val NOTIFICATION_ID = 4101

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_engine_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            11,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restart = PendingIntent.getService(
            context,
            12,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = when (state) {
            is EngineState.Installing -> context.getString(R.string.engine_installing) to
                "${(state.progress * 100).toInt()}%"
            is EngineState.Starting -> context.getString(R.string.notification_title) to
                context.getString(R.string.engine_starting)
            is EngineState.Ready -> context.getString(R.string.notification_title) to
                context.getString(R.string.notification_text, state.port)
            is EngineState.Error -> context.getString(R.string.engine_error) to state.message
            EngineState.Stopped -> context.getString(R.string.engine_stopped) to ""
            EngineState.Idle -> context.getString(R.string.notification_title) to ""
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_restart), restart)
            .addAction(0, context.getString(R.string.notification_stop), stop)
            .build()
    }
}
