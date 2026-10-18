package io.github.luqijian.harnessandroid.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.luqijian.harnessandroid.workspace.WorkspaceSyncManager

/**
 * Debug-only helper used by adb-based SAF tests:
 * adb shell am start -n <pkg>/.debug.GrantTreeUriActivity -a android.intent.action.VIEW \
 *   -d 'content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTestProject' \
 *   --grant-read-uri-permission --grant-write-uri-permission \
 *   --grant-persistable-uri-permission --grant-prefix-uri-permission
 */
class GrantTreeUriActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent?.data
        if (!BuildConfigCompat.DEBUG) {
            finish()
            return
        }
        if (uri == null) {
            Toast.makeText(this, "missing content:// tree URI", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure {
                Toast.makeText(this, "grant failed: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        val name = Uri.decode(uri.lastPathSegment ?: "test")
            .substringAfterLast('/').substringAfterLast(':')
        WorkspaceSyncManager.importWorkspace(
            this,
            uri,
            name.ifBlank { "test" },
            onProgress = {},
            onDone = { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            },
        )
    }
}

private object BuildConfigCompat {
    val DEBUG: Boolean = io.github.luqijian.harnessandroid.BuildConfig.DEBUG
}
