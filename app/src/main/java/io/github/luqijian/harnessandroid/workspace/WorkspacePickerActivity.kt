package io.github.luqijian.harnessandroid.workspace

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.luqijian.harnessandroid.BuildConfig
import io.github.luqijian.harnessandroid.R
import io.github.luqijian.harnessandroid.databinding.ActivityWorkspacePickerBinding
import io.github.luqijian.harnessandroid.engine.EngineService
import java.io.File

class WorkspacePickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE_URI = "tree_uri"
        private const val PREFS = "workspace_picker"
        private const val KEY_LAST_PATH = "last_path"
    }

    private lateinit var binding: ActivityWorkspacePickerBinding
    private lateinit var adapter: FolderAdapter
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var importingDialog: androidx.appcompat.app.AlertDialog? = null
    private var importingText: TextView? = null
    private var importingProgress: ProgressBar? = null

    private val safPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            if (!BuildConfig.DIRECT_STORAGE) finish()
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Toast.makeText(this, "无法持久化文件夹授权", Toast.LENGTH_LONG).show() }
        startImport(uri, displayName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspacePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = FolderAdapter { file -> navigateTo(file) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { navigateUp() }
        binding.select.setOnClickListener { selectCurrent() }
        binding.newFolder.setOnClickListener { createFolder() }
        binding.enterPath.setOnClickListener { enterPath() }

        if (BuildConfig.DIRECT_STORAGE) {
            binding.hint.visibility = View.VISIBLE
            val initial = intent.getStringExtra(EXTRA_TREE_URI)
            if (initial != null) {
                startImport(Uri.parse(initial), displayName(Uri.parse(initial)))
                return
            }
            currentDir = File(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_LAST_PATH, Environment.getExternalStorageDirectory().path)
                    ?: Environment.getExternalStorageDirectory().path,
            )
            ensureDirectPermissionThenShow()
        } else if (intent.getStringExtra(EXTRA_TREE_URI) != null) {
            startImport(
                Uri.parse(intent.getStringExtra(EXTRA_TREE_URI)),
                displayName(Uri.parse(intent.getStringExtra(EXTRA_TREE_URI)!!)),
            )
        } else if (savedInstanceState == null) {
            binding.hint.visibility = View.VISIBLE
            binding.hint.text = getString(R.string.workspace_import_choose)
            safPicker.launch(null)
        }
    }

    private fun ensureDirectPermissionThenShow() {
        if (hasStorageAccess()) {
            if (!currentDir.isDirectory) currentDir = Environment.getExternalStorageDirectory()
            showDirectory(currentDir)
        } else {
            binding.list.visibility = View.GONE
            binding.hint.visibility = View.VISIBLE
            binding.hint.text = getString(R.string.storage_permission_required)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_permission_required)
                .setMessage(R.string.direct_mode_note)
                .setPositiveButton(R.string.storage_permission_button) { _, _ -> requestStorage() }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                42,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DIRECT_STORAGE && hasStorageAccess() && binding.list.visibility != View.VISIBLE) {
            binding.list.visibility = View.VISIBLE
            showDirectory(currentDir)
        }
    }

    private fun showDirectory(dir: File) {
        currentDir = dir
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_PATH, dir.path).apply()
        binding.path.text = dir.absolutePath
        binding.hint.visibility = if (BuildConfig.DIRECT_STORAGE) View.VISIBLE else View.GONE
        val dirs = dir.listFiles()
            ?.filter { it.isDirectory && !isForbidden(it) }
            ?.sortedWith(compareBy({ it.name.lowercase() }))
            ?: emptyList()
        adapter.submit(dirs)
        binding.list.visibility = View.VISIBLE
    }

    private fun isForbidden(file: File): Boolean {
        val path = file.absolutePath
        return path.contains("/Android/data") || path.contains("/Android/obb")
    }

    private fun navigateTo(file: File) {
        if (!isForbidden(file)) showDirectory(file)
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile
        if (parent != null && parent != currentDir) {
            showDirectory(parent)
        } else {
            finish()
        }
    }

    private fun selectCurrent() {
        if (!BuildConfig.DIRECT_STORAGE) return
        if (!hasStorageAccess()) {
            requestStorage()
            return
        }
        if (isForbidden(currentDir)) {
            Toast.makeText(this, R.string.path_forbidden, Toast.LENGTH_LONG).show()
            return
        }
        val probe = File(currentDir, ".harness-write-test")
        val writable = runCatching {
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
        if (!writable) {
            Toast.makeText(this, R.string.path_invalid, Toast.LENGTH_LONG).show()
            return
        }
        WorkspaceSyncManager.activateDirect(this, currentDir)
        Toast.makeText(this, "工作区已切换：${currentDir.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun createFolder() {
        if (!BuildConfig.DIRECT_STORAGE) return
        val input = EditText(this).apply { hint = getString(R.string.new_folder) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_folder)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val dir = File(currentDir, name)
                    if (dir.mkdirs()) showDirectory(dir)
                    else Toast.makeText(this, "无法创建文件夹", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun enterPath() {
        val input = EditText(this).apply { setText(currentDir.absolutePath) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_path)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val dir = File(input.text.toString().trim())
                if (dir.isDirectory && !isForbidden(dir)) showDirectory(dir)
                else Toast.makeText(this, R.string.path_invalid, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startImport(uri: Uri, name: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val text = TextView(this).apply {
            text = getString(R.string.importing)
            textSize = 14f
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        container.addView(progress)
        container.addView(text)
        importingText = text
        importingProgress = progress
        importingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.importing)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .show()

        WorkspaceSyncManager.importWorkspace(
            this,
            uri,
            name,
            onProgress = { p ->
                val mb = p.bytes / 1024.0 / 1024.0
                importingProgress?.isIndeterminate = false
                importingProgress?.progress = (p.files % 1000)
                importingText?.text = "${p.files} 个文件 · ${"%.1f".format(mb)} MB\n${p.current}"
            },
            onDone = { success, message ->
                importingDialog?.dismiss()
                importingDialog = null
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) finish() else {
                    EngineService.start(this)
                    finish()
                }
            },
        )
    }

    private fun displayName(uri: Uri): String {
        val raw = uri.lastPathSegment ?: "workspace"
        val decoded = Uri.decode(raw)
        val tail = decoded.substringAfterLast('/').substringAfterLast(':')
        return tail.ifBlank { "workspace" }
    }
}
