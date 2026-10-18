package io.github.luqijian.harnessandroid.update

import android.app.Activity

object UpdateManager {
    fun check(activity: Activity, onStatus: (String) -> Unit) {
        UpdateManagerImpl.check(activity, onStatus)
    }
}
