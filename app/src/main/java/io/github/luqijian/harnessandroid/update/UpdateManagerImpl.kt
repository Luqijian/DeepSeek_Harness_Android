package io.github.luqijian.harnessandroid.update

import android.app.Activity

object UpdateManagerImpl {
    fun check(activity: Activity, onStatus: (String) -> Unit) {
        GithubUpdateChecker.check(activity, onStatus)
    }
}
