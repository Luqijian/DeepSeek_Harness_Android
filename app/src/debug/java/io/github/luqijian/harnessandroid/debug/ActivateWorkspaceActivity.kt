package io.github.luqijian.harnessandroid.debug

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.luqijian.harnessandroid.BuildConfig
import io.github.luqijian.harnessandroid.workspace.WorkspaceSyncManager
import java.io.File

/**
 * Debug-only helper for device automation:
 * adb shell am start -n <pkg>/.debug.ActivateWorkspaceActivity --es path /sdcard/Download/TestProject
 */
class ActivateWorkspaceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        if (!BuildConfig.DIRECT_STORAGE) {
            Toast.makeText(this, "direct storage is only available in the dev flavor", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val path = intent?.getStringExtra("path")
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "missing --es path", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val dir = File(path)
        if (!dir.isDirectory) {
            Toast.makeText(this, "not a directory: $path", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        WorkspaceSyncManager.activateDirect(this, dir)
        Toast.makeText(this, "workspace: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
        finish()
    }
}
