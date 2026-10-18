package io.github.luqijian.harnessandroid.workspace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast

object WorkspaceActions {
    fun choose(activity: Activity) {
        activity.startActivity(Intent(activity, WorkspacePickerActivity::class.java))
    }

    fun syncNow(context: Context) {
        WorkspaceSyncManager.syncActive(context) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
