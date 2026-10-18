package io.github.luqijian.harnessandroid.engine

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.luqijian.harnessandroid.BuildConfig
import io.github.luqijian.harnessandroid.core.AppPaths
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.core.TokenParser
import io.github.luqijian.harnessandroid.workspace.Workspace
import io.github.luqijian.harnessandroid.workspace.WorkspaceMode
import io.github.luqijian.harnessandroid.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

object EngineController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var manualStop = false

    @Volatile
    private var launchSpec: LaunchSpec? = null

    @Volatile
    private var lastUrl: String? = null

    private var readerJob: Job? = null
    private var timeoutJob: Job? = null

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (process?.isAlive == true && (_state.value is EngineState.Starting || _state.value is EngineState.Ready)) {
                    LogStore.append("engine", "start ignored; already running")
                    return@withLock
                }
                manualStop = false
                lastUrl = null
                cleanupOrphans(app)
                try {
                    val rootfs = RuntimeInstaller.ensureInstalled(app, BuildConfig.RUNTIME_VERSION) { p, detail ->
                        _state.value = EngineState.Installing(p, detail)
                    }
                    val workspace = ensureWorkspace(app)
                    _state.value = EngineState.Starting("准备启动 ${workspace.name}")
                    DshConfig.apply(app, workspace)
                    val spec = ProotLauncher.build(app, workspace, rootfs)
                    launchSpec = spec
                    LogStore.append("engine", "starting proot for ${workspace.guestPath}")
                    val pb = ProcessBuilder(spec.command)
                        .redirectErrorStream(true)
                    pb.environment().putAll(spec.environment)
                    val p = pb.start()
                    process = p
                    _state.value = EngineState.Starting("Linux 会话启动中…")
                    startReader(app, p)
                    startTimeout(app)
                    scope.launch {
                        val code = runCatching { p.waitFor() }.getOrDefault(-1)
                        onExited(app, code)
                    }
                } catch (t: Throwable) {
                    LogStore.append("engine", "start failed: ${t.stackTraceToString()}")
                    _state.value = EngineState.Error(
                        t.message ?: "启动失败",
                        LogStore.tail(40),
                    )
                    killProcessGroup()
                }
            }
        }
    }

    fun restart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            stopInternal(app)
            delay(700)
            start(app)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        scope.launch { stopInternal(app) }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private suspend fun stopInternal(app: Context) {
        manualStop = true
        timeoutJob?.cancel()
        readerJob?.cancel()
        _state.value = EngineState.Stopped
        killProcessGroup()
        process = null
        lastUrl = null
        LogStore.append("engine", "stopped")
    }

    private fun startReader(app: Context, p: Process) {
        readerJob?.cancel()
        readerJob = scope.launch {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        LogStore.append("dsh", line)
                        val parsed = TokenParser.parse(line)
                        if (parsed != null && parsed.url != lastUrl) {
                            lastUrl = parsed.url
                            timeoutJob?.cancel()
                            _state.value = EngineState.Ready(parsed.url, parsed.port)
                            LogStore.append("engine", "ready on port ${parsed.port}")
                        }
                    }
                }
            }.onFailure {
                if (!manualStop) LogStore.append("dsh", "reader failed: ${it.message}")
            }
        }
    }

    private fun startTimeout(app: Context) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(150_000)
            if (_state.value is EngineState.Starting && !manualStop) {
                val tail = LogStore.tail(60)
                _state.value = EngineState.Error("启动超时（150 秒内未收到 Web UI 地址）", tail)
                LogStore.append("engine", "startup timed out")
                killProcessGroup()
            }
        }
    }

    private fun onExited(app: Context, code: Int) {
        if (manualStop) {
            LogStore.append("engine", "process exited after stop (code=$code)")
            return
        }
        val tail = LogStore.tail(60)
        LogStore.append("engine", "process exited unexpectedly (code=$code)")
        _state.value = EngineState.Error("Harness 进程退出（code=$code）", tail)
        process = null
        timeoutJob?.cancel()
    }

    private fun killProcessGroup() {
        val spec = launchSpec
        val pid = spec?.pidFile?.takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull()
        LogStore.append("engine", "killing process group pid=$pid")
        if (pid != null) {
            runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        }
        runCatching { process?.destroy() }
        if (pid != null) {
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < deadline && process?.isAlive == true) {
                Thread.sleep(100)
            }
            runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        runCatching { process?.destroyForcibly() }
        runCatching { spec?.pidFile?.delete() }
    }

    private fun cleanupOrphans(context: Context) {
        val pidFile = AppPaths.enginePid(context)
        val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (pid != null && pid > 1) {
            LogStore.append("engine", "cleaning orphan process group $pid")
            runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
            Thread.sleep(1200)
            runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        runCatching { pidFile.delete() }
        runCatching {
            ProcessBuilder("/system/bin/pkill", "-TERM", "-f", "files/runtime/local-.*/rootfs")
                .start().waitFor()
            Thread.sleep(300)
            ProcessBuilder("/system/bin/pkill", "-KILL", "-f", "files/runtime/local-.*/rootfs")
                .start().waitFor()
        }
        Thread.sleep(300)
    }

    private fun ensureWorkspace(context: Context): Workspace {        WorkspaceStore.active(context)?.let { return it }
        val mirror = File(AppPaths.workspaces(context), "default")
        mirror.mkdirs()
        val workspace = Workspace(
            id = "default",
            name = "Default",
            mode = WorkspaceMode.MIRROR,
            mirrorPath = mirror.path,
        )
        WorkspaceStore.save(context, workspace, makeActive = true)
        LogStore.append("workspace", "created default workspace at ${mirror.path}")
        return workspace
    }
}
