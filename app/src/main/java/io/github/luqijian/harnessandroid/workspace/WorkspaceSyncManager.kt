package io.github.luqijian.harnessandroid.workspace

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.engine.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WorkspaceSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun syncActive(context: Context, onStatus: (String) -> Unit) {
        val workspace = WorkspaceStore.active(context)
        if (workspace == null) {
            onStatus("尚未选择工作区")
            return
        }
        if (workspace.mode != WorkspaceMode.MIRROR) {
            onStatus("Dev 直连模式直接读写原文件夹，无需同步")
            return
        }
        scope.launch {
            try {
                val result = SafSyncEngine.sync(context, workspace) { message ->
                    LogStore.append("sync", message)
                }
                val summary = "上传 ${result.pushed} · 下载 ${result.pulled} · 冲突 ${result.conflicts}"
                WorkspaceStore.save(
                    context,
                    workspace.copy(
                        lastSyncAt = System.currentTimeMillis(),
                        lastSyncResult = summary,
                    ),
                    makeActive = false,
                )
                main.post { onStatus("同步完成：$summary") }
            } catch (t: Throwable) {
                LogStore.append("sync", "failed: ${t.stackTraceToString()}")
                WorkspaceStore.save(
                    context,
                    workspace.copy(lastSyncResult = "失败：${t.message}"),
                    makeActive = false,
                )
                main.post { onStatus("同步失败：${t.message}") }
            }
        }
    }

    fun importWorkspace(
        context: Context,
        treeUri: Uri,
        name: String,
        onProgress: (ImportProgress) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        val workspace = WorkspaceStore.newMirrorWorkspace(context, name, treeUri.toString())
        scope.launch {
            try {
                val result = SafWorkspaceImporter.import(context, workspace) { progress ->
                    main.post { onProgress(progress) }
                }
                val saved = workspace.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncResult = "已导入 ${result.fileCount} 个文件",
                )
                WorkspaceStore.save(context, saved, makeActive = true)
                LogStore.append("import", "workspace ${workspace.name} active")
                main.post {
                    onDone(true, "已导入 ${result.fileCount} 个文件，${result.dirCount} 个目录")
                    EngineService.restart(context)
                }
            } catch (t: Throwable) {
                LogStore.append("import", "failed: ${t.stackTraceToString()}")
                workspace.backendPath(context).deleteRecursively()
                JournalStore.delete(context, workspace.id)
                WorkspaceStore.remove(context, workspace.id)
                main.post { onDone(false, t.message ?: "导入失败") }
            }
        }
    }

    fun activateDirect(context: Context, path: java.io.File): Workspace {
        val workspace = WorkspaceStore.newDirectWorkspace(path)
        WorkspaceStore.save(context, workspace, makeActive = true)
        LogStore.append("workspace", "direct workspace ${path.path}")
        EngineService.restart(context)
        return workspace
    }
}
