package io.github.luqijian.harnessandroid.workspace

import android.content.Context
import io.github.luqijian.harnessandroid.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class WorkspaceMode {
    MIRROR,
    DIRECT,
}

data class Workspace(
    val id: String,
    val name: String,
    val mode: WorkspaceMode,
    val treeUri: String? = null,
    val directPath: String? = null,
    val mirrorPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0L,
    val lastSyncResult: String? = null,
) {
    val guestPath: String get() = "/workspaces/$id"

    fun backendPath(context: Context): File =
        if (mode == WorkspaceMode.DIRECT) {
            File(directPath ?: error("direct workspace has no path"))
        } else {
            File(mirrorPath ?: File(AppPaths.workspaces(context), id).path)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mode", mode.name)
        put("treeUri", treeUri)
        put("directPath", directPath)
        put("mirrorPath", mirrorPath)
        put("createdAt", createdAt)
        put("lastSyncAt", lastSyncAt)
        put("lastSyncResult", lastSyncResult)
    }

    companion object {
        fun fromJson(obj: JSONObject): Workspace = Workspace(
            id = obj.getString("id"),
            name = obj.optString("name", "workspace"),
            mode = runCatching { WorkspaceMode.valueOf(obj.optString("mode", "MIRROR")) }
                .getOrDefault(WorkspaceMode.MIRROR),
            treeUri = obj.optString("treeUri", "").ifBlank { null },
            directPath = obj.optString("directPath", "").ifBlank { null },
            mirrorPath = obj.optString("mirrorPath", "").ifBlank { null },
            createdAt = obj.optLong("createdAt", 0L),
            lastSyncAt = obj.optLong("lastSyncAt", 0L),
            lastSyncResult = obj.optString("lastSyncResult", "").ifBlank { null },
        )
    }
}

object WorkspaceStore {
    private const val PREFS = "workspace_store"
    private const val KEY_LIST = "workspaces"
    private const val KEY_ACTIVE = "active_id"

    @Synchronized
    fun list(context: Context): List<Workspace> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(Workspace.fromJson(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun active(context: Context): Workspace? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)
            ?: return list(context).firstOrNull()
        return list(context).firstOrNull { it.id == id } ?: list(context).firstOrNull()
    }

    @Synchronized
    fun save(context: Context, workspace: Workspace, makeActive: Boolean = true) {
        val all = list(context).toMutableList()
        val index = all.indexOfFirst { it.id == workspace.id }
        if (index >= 0) all[index] = workspace else all.add(workspace)
        writeAll(context, all)
        if (makeActive) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE, workspace.id).apply()
        }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val all = list(context).filterNot { it.id == id }
        writeAll(context, all)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            prefs.edit().putString(KEY_ACTIVE, all.firstOrNull()?.id).apply()
        }
    }

    @Synchronized
    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun newMirrorWorkspace(context: Context, name: String, treeUri: String): Workspace {
        val id = UUID.randomUUID().toString()
        val mirror = File(AppPaths.workspaces(context), id)
        mirror.mkdirs()
        return Workspace(
            id = id,
            name = name,
            mode = WorkspaceMode.MIRROR,
            treeUri = treeUri,
            mirrorPath = mirror.path,
        )
    }

    fun newDirectWorkspace(path: File): Workspace = Workspace(
        id = UUID.randomUUID().toString(),
        name = path.name.ifBlank { "workspace" },
        mode = WorkspaceMode.DIRECT,
        directPath = path.path,
    )

    private fun writeAll(context: Context, all: List<Workspace>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST, arr.toString()).apply()
    }
}
