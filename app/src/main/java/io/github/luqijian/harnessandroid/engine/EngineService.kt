package io.github.luqijian.harnessandroid.engine

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.luqijian.harnessandroid.core.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EngineService : Service() {
    companion object {
        const val ACTION_START = "io.github.luqijian.harnessandroid.START_ENGINE"
        const val ACTION_STOP = "io.github.luqijian.harnessandroid.STOP_ENGINE"
        const val ACTION_RESTART = "io.github.luqijian.harnessandroid.RESTART_ENGINE"

        fun start(context: Context) {
            val intent = Intent(context, EngineService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }

        fun restart(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_RESTART))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        EngineNotifications.createChannels(this)
        stateJob = scope.launch {
            EngineController.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(EngineNotifications.NOTIFICATION_ID, EngineNotifications.build(this@EngineService, state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                EngineController.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_RESTART -> {
                acquireWakeLock()
                EngineController.restart(this)
            }

            else -> {
                acquireWakeLock()
                EngineController.start(this)
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = EngineNotifications.build(this, EngineController.state.value)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, EngineNotifications.NOTIFICATION_ID, notification, type)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:engine").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the local server and Linux session alive when the task is swiped away.
        LogStore.append("service", "task removed; keeping engine alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LogStore.append("service", "service destroyed")
        stateJob?.cancel()
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        EngineController.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
