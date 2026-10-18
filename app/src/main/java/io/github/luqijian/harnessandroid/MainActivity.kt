package io.github.luqijian.harnessandroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.databinding.ActivityMainBinding
import io.github.luqijian.harnessandroid.engine.EngineController
import io.github.luqijian.harnessandroid.engine.EngineService
import io.github.luqijian.harnessandroid.engine.EngineState
import io.github.luqijian.harnessandroid.workspace.WorkspaceActions
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var lastLoadedUrl: String? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { startEngine() }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (granted) request.grant(request.resources) else request.deny()
        }
    }

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        val uris: Array<Uri>? = when {
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()
        setupToolbar()
        setupWebView()
        binding.retry.setOnClickListener { EngineService.restart(this) }
        binding.openSettings.setOnClickListener { openSettings() }

        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_workspace -> {
                    WorkspaceActions.choose(this)
                    true
                }

                R.id.action_reload -> {
                    binding.webView.reload()
                    true
                }

                R.id.action_settings -> {
                    openSettings()
                    true
                }

                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        if (!consumedFirstRun()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_run_title)
                .setMessage(R.string.first_run_body)
                .setCancelable(false)
                .setPositiveButton(R.string.agree_continue) { _, _ ->
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putBoolean("onboarding_accepted", true).apply()
                    beginStartup()
                }
                .show()
        } else {
            beginStartup()
        }

        observeEngine()
    }

    private fun consumedFirstRun(): Boolean =
        getSharedPreferences("app", MODE_PRIVATE).getBoolean("onboarding_accepted", false)

    private fun beginStartup() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startEngine()
        }
    }

    private fun startEngine() {
        LogStore.append("ui", "starting engine service")
        EngineService.start(this)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.root.updatePadding(top = bars.top)
            binding.webContainer.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbar.subtitle = getString(R.string.engine_starting)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        binding.webView.apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
                useWideViewPort = true
                loadWithOverviewMode = false
                textZoom = 100
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val host = request.url.host ?: return false
                    if (host == "127.0.0.1" || host == "localhost") return false
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    LogStore.append("webview", "loaded $url")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    LogStore.append(
                        "webview",
                        "error ${error.errorCode} ${error.description} for ${request.url}",
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = fileChooserParams.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { fileChooser.launch(intent) }.onFailure {
                        pendingFileCallback = null
                        filePathCallback.onReceiveValue(null)
                    }
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    if (request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(request.resources)
                        } else {
                            pendingPermissionRequest = request
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        request.deny()
                    }
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    LogStore.append("download", "unsupported blob/data download: $url")
                    Toast.makeText(this@MainActivity, "该下载类型暂不支持", Toast.LENGTH_SHORT).show()
                    return@setDownloadListener
                }
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(
                            this@MainActivity,
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimeType),
                        )
                    getSystemService(DownloadManager::class.java).enqueue(request)
                }.onFailure {
                    LogStore.append("download", "failed: ${it.message}")
                }
            }
        }
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EngineController.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: EngineState) {
        when (state) {
            is EngineState.Installing -> {
                showOverlay()
                binding.status.setText(R.string.engine_installing)
                binding.progress.isIndeterminate = false
                binding.progress.progress = (state.progress * 100).toInt()
                binding.detail.text = state.detail
                binding.detail.visibility = View.VISIBLE
                binding.overlayActions.visibility = View.GONE
                binding.toolbar.subtitle = getString(R.string.engine_installing)
            }

            is EngineState.Starting -> {
                showOverlay()
                binding.status.setText(R.string.engine_starting)
                binding.progress.isIndeterminate = true
                binding.detail.text = state.detail
                binding.detail.visibility = if (state.detail.isBlank()) View.GONE else View.VISIBLE
                binding.overlayActions.visibility = View.GONE
                binding.toolbar.subtitle = getString(R.string.engine_starting)
            }

            is EngineState.Ready -> {
                hideOverlay()
                binding.toolbar.subtitle = getString(R.string.engine_ready)
                if (lastLoadedUrl != state.url) {
                    lastLoadedUrl = state.url
                    binding.webView.loadUrl(state.url)
                }
            }

            is EngineState.Error -> {
                showOverlay()
                binding.status.text = state.message
                binding.progress.isIndeterminate = false
                binding.progress.progress = 0
                binding.detail.text = state.detail
                binding.detail.visibility = if (state.detail.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.overlayActions.visibility = View.VISIBLE
                binding.toolbar.subtitle = getString(R.string.engine_error)
            }

            EngineState.Stopped, EngineState.Idle -> {
                showOverlay()
                binding.status.setText(R.string.engine_stopped)
                binding.progress.isIndeterminate = false
                binding.progress.progress = 0
                binding.detail.visibility = View.GONE
                binding.overlayActions.visibility = View.VISIBLE
                binding.toolbar.subtitle = getString(R.string.engine_stopped)
            }
        }
    }

    private fun showOverlay() {
        binding.overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        binding.overlay.visibility = View.GONE
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
