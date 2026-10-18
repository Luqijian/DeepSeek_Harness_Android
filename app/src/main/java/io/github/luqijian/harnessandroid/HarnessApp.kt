package io.github.luqijian.harnessandroid

import android.app.Application
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.engine.EngineNotifications

class HarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        EngineNotifications.createChannels(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogStore.append("CRASH", "Uncaught on ${thread.name}: ${throwable.stackTraceToString()}")
            previous?.uncaughtException(thread, throwable)
        }
    }
}
