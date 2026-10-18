package io.github.luqijian.harnessandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.luqijian.harnessandroid.core.AppPaths
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.databinding.ActivitySettingsBinding
import io.github.luqijian.harnessandroid.engine.EngineController
import io.github.luqijian.harnessandroid.engine.EngineService
import io.github.luqijian.harnessandroid.engine.EngineState
import io.github.luqijian.harnessandroid.update.UpdateManager
import io.github.luqijian.harnessandroid.workspace.WorkspaceActions
import io.github.luqijian.harnessandroid.workspace.WorkspaceMode
import io.github.luqijian.harnessandroid.workspace.WorkspaceStore
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.flavorBadge.text = getString(R.string.dev_badge)
        binding.about.text = getString(
            R.string.version_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.DSH_VERSION,
        )

        binding.startEngine.setOnClickListener { EngineService.start(this) }
        binding.stopEngine.setOnClickListener { EngineService.stop(this) }
        binding.restartEngine.setOnClickListener { EngineService.restart(this) }
        binding.openFolder.setOnClickListener { WorkspaceActions.choose(this) }
        binding.syncNow.setOnClickListener {
            WorkspaceActions.syncNow(this)
            getSharedPreferences("app", MODE_PRIVATE).edit()
                .putLong("last_sync_request", System.currentTimeMillis()).apply()
            Toast.makeText(this, R.string.sync_running, Toast.LENGTH_SHORT).show()
        }
        binding.removeWorkspace.setOnClickListener { confirmRemoveWorkspace() }
        binding.shareLogs.setOnClickListener { shareLogs() }
        binding.reportIssue.setOnClickListener { openUrl("https://github.com/${BuildConfig.GITHUB_REPO}/issues") }
        binding.licenseLink.setOnClickListener {
            openUrl("https://github.com/${BuildConfig.GITHUB_REPO}/blob/main/docs/LICENSES.md")
        }
        binding.privacyLink.setOnClickListener {
            openUrl("https://github.com/${BuildConfig.GITHUB_REPO}/blob/main/docs/PRIVACY.md")
        }
        binding.checkUpdate.setOnClickListener {
            binding.updateStatus.text = getString(R.string.update_downloading)
            UpdateManager.check(this) { status ->
                runOnUiThread { binding.updateStatus.text = status }
            }
        }

        observeEngine()
        renderWorkspace()
    }

    override fun onResume() {
        super.onResume()
        renderWorkspace()
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EngineController.state.collect { state -> renderEngine(state) }
            }
        }
    }

    private fun renderEngine(state: EngineState) {
        binding.engineStatus.text = when (state) {
            is EngineState.Installing -> "安装运行时 ${(state.progress * 100).toInt()}% · ${state.detail}"
            is EngineState.Starting -> "启动中 · ${state.detail}"
            is EngineState.Ready -> "运行中 · 127.0.0.1:${state.port}"
            is EngineState.Error -> "错误 · ${state.message}"
            EngineState.Stopped -> "已停止"
            EngineState.Idle -> "未启动"
        }
        binding.startEngine.isEnabled = state !is EngineState.Starting && state !is EngineState.Ready
        binding.stopEngine.isEnabled = state is EngineState.Starting || state is EngineState.Ready
        binding.restartEngine.isEnabled = true
    }

    private fun renderWorkspace() {
        val workspace = WorkspaceStore.active(this)
        if (workspace == null) {
            binding.currentWorkspace.setText(R.string.no_workspace)
            binding.workspaceModeNote.visibility = android.view.View.GONE
            binding.syncNow.isEnabled = false
            binding.removeWorkspace.isEnabled = false
            return
        }
        binding.currentWorkspace.text = buildString {
            append(workspace.name)
            append("\n")
            append(workspace.backendPath(this@SettingsActivity).absolutePath)
            append("\n")
            append(if (workspace.mode == WorkspaceMode.DIRECT) getString(R.string.direct_mode) else getString(R.string.mirror_mode))
            if (workspace.mode == WorkspaceMode.MIRROR) {
                append(" · ")
                append(getString(R.string.sync_status))
                append(": ")
                append(workspace.lastSyncResult ?: getString(R.string.not_synced))
            }
        }
        binding.workspaceModeNote.apply {
            visibility = android.view.View.VISIBLE
            text = if (workspace.mode == WorkspaceMode.DIRECT) {
                getString(R.string.direct_mode_note)
            } else {
                getString(R.string.security_body)
            }
        }
        binding.syncNow.isEnabled = workspace.mode == WorkspaceMode.MIRROR
        binding.removeWorkspace.isEnabled = true
    }

    private fun confirmRemoveWorkspace() {
        val workspace = WorkspaceStore.active(this) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_workspace)
            .setMessage(R.string.remove_workspace_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val backend = workspace.backendPath(this)
                if (workspace.mode == WorkspaceMode.MIRROR) {
                    backend.deleteRecursively()
                }
                WorkspaceStore.remove(this, workspace.id)
                renderWorkspace()
                Toast.makeText(this, R.string.workspace_removed, Toast.LENGTH_SHORT).show()
                EngineService.restart(this)
            }
            .show()
    }

    private fun shareLogs() {
        val log = AppPaths.engineLog(this)
        if (!log.isFile) {
            Toast.makeText(this, R.string.engine_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", log)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Harness Android logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_logs)))
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
    }
}
