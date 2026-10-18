package io.github.luqijian.harnessandroid.workspace

import android.content.Context
import io.github.luqijian.harnessandroid.core.AppPaths
import org.json.JSONObject
import java.io.File

data class JournalEntry(
    val docId: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val localSize: Long,
    val localMtime: Long,
)

data class WorkspaceJournal(
    val treeUri: String,
    val rootDocId: String,
    val entries: MutableMap<String, JournalEntry> = mutableMapOf(),
    var lastSyncAt: Long = 0L,
)

object JournalStore {
    private fun file(context: Context, workspaceId: String): File =
        File(File(context.filesDir, "workspace-journals"), "$workspaceId.json").apply {
            parentFile?.mkdirs()
        }

    fun load(context: Context, workspaceId: String): WorkspaceJournal? {
        val f = file(context, workspaceId)
        if (!f.isFile) return null
        return runCatching {
            val obj = JSONObject(f.readText())
            val entries = mutableMapOf<String, JournalEntry>()
            val arr = obj.optJSONObject("entries") ?: JSONObject()
            val keys = arr.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val e = arr.getJSONObject(key)
                entries[key] = JournalEntry(
                    docId = e.getString("docId"),
                    isDir = e.optBoolean("isDir", false),
                    size = e.optLong("size", -1),
                    mtime = e.optLong("mtime", 0),
                    localSize = e.optLong("localSize", -1),
                    localMtime = e.optLong("localMtime", 0),
                )
            }
            WorkspaceJournal(
                treeUri = obj.optString("treeUri", ""),
                rootDocId = obj.optString("rootDocId", ""),
                entries = entries,
                lastSyncAt = obj.optLong("lastSyncAt", 0),
            )
        }.getOrNull()
    }

    fun save(context: Context, workspaceId: String, journal: WorkspaceJournal) {
        val entries = JSONObject()
        journal.entries.forEach { (path, e) ->
            entries.put(
                path,
                JSONObject()
                    .put("docId", e.docId)
                    .put("isDir", e.isDir)
                    .put("size", e.size)
                    .put("mtime", e.mtime)
                    .put("localSize", e.localSize)
                    .put("localMtime", e.localMtime),
            )
        }
        val obj = JSONObject()
            .put("treeUri", journal.treeUri)
            .put("rootDocId", journal.rootDocId)
            .put("entries", entries)
            .put("lastSyncAt", journal.lastSyncAt)
        val target = file(context, workspaceId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(obj.toString())
        tmp.renameTo(target)
    }

    fun delete(context: Context, workspaceId: String) {
        file(context, workspaceId).delete()
    }
}
