package io.github.luqijian.harnessandroid.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.luqijian.harnessandroid.core.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val conflicts: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
)

private data class LocalRow(val size: Long, val mtime: Long, val isDir: Boolean)
private data class RemoteRow(
    val docId: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    val name: String,
)

object SafSyncEngine {
    private val excludedDirs = setOf(
        "node_modules",
        ".gradle",
        "build",
        "dist",
        ".next",
        ".nuxt",
        ".cache",
        "__pycache__",
        ".venv",
        "venv",
        "target",
    )

    suspend fun sync(
        context: Context,
        workspace: Workspace,
        onProgress: (String) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        val treeUri = workspace.treeUri?.let(Uri::parse)
            ?: throw IllegalStateException("mirror workspace has no SAF URI")
        val journal = JournalStore.load(context, workspace.id)
            ?: throw IllegalStateException("缺少同步基线，请重新导入工作区")
        val mirror = workspace.backendPath(context)
        if (!mirror.isDirectory) throw IllegalStateException("本地镜像不存在")
        val resolver = context.contentResolver

        onProgress("扫描本地文件…")
        val local = scanLocal(mirror)
        onProgress("扫描远端文件…")
        val remote = scanRemote(context, treeUri, journal.rootDocId)
        LogStore.append("sync", "local=${local.size} remote=${remote.size} journal=${journal.entries.size}")

        val rels = (local.keys + remote.keys + journal.entries.keys).sortedBy { it.count { c -> c == '/' } }
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        var deletedLocal = 0
        var deletedRemote = 0

        val deletionsLocal = mutableListOf<String>()
        val deletionsRemote = mutableListOf<String>()
        val maxDeletes = maxOf(50, journal.entries.size / 10)

        for (rel in rels) {
            currentCoroutineContext().ensureActive()
            val l = local[rel]
            val r = remote[rel]
            val j = journal.entries[rel]
            when {
                l != null && r != null -> {
                    if (l.isDir && r.isDir) {
                        // no content to sync
                    } else if (!l.isDir && !r.isDir) {
                        val localChanged = j == null || l.size != j.localSize || l.mtime != j.localMtime
                        val remoteChanged = j == null || r.size != j.size || r.mtime != j.mtime
                        when {
                            localChanged && remoteChanged -> {
                                val conflict = conflictCopy(mirror, rel)
                                if (conflict) conflicts++
                                pullFile(context, treeUri, mirror, rel, r)
                                pulled++
                                onProgress("冲突已保留副本：$rel")
                            }

                            localChanged -> {
                                pushFile(context, treeUri, journal, mirror, rel, r.docId)
                                pushed++
                                onProgress("上传 $rel")
                            }

                            remoteChanged -> {
                                pullFile(context, treeUri, mirror, rel, r)
                                pulled++
                                onProgress("下载 $rel")
                            }
                        }
                    }
                    journal.entries[rel] = mergeEntry(l, r)
                }

                l != null && r == null -> {
                    val localChanged = j == null || l.isDir || l.size != j.localSize || l.mtime != j.localMtime
                    if (j == null || localChanged) {
                        if (l.isDir) {
                            val id = ensureRemoteDir(context, treeUri, journal, rel)
                            if (id != null) journal.entries[rel] = JournalEntry(id, true, 0, 0, 0, l.mtime)
                        } else {
                            val id = createRemoteFile(context, treeUri, journal, mirror, rel)
                            if (id != null) {
                                journal.entries[rel] = JournalEntry(
                                    id,
                                    false,
                                    l.size,
                                    l.mtime,
                                    l.size,
                                    l.mtime,
                                )
                                pushed++
                                onProgress("上传 $rel")
                            }
                        }
                    } else {
                        deletionsLocal.add(rel)
                    }
                }

                l == null && r != null -> {
                    val remoteChanged = j == null || r.isDir || r.size != j.size || r.mtime != j.mtime
                    if (j == null || remoteChanged) {
                        if (r.isDir) {
                            File(mirror, rel).mkdirs()
                        } else {
                            pullFile(context, treeUri, mirror, rel, r)
                            pulled++
                            onProgress("下载 $rel")
                        }
                        val f = File(mirror, rel)
                        journal.entries[rel] = JournalEntry(
                            r.docId,
                            r.isDir,
                            r.size,
                            r.mtime,
                            if (r.isDir) 0 else f.length(),
                            f.lastModified(),
                        )
                    } else {
                        deletionsRemote.add(rel)
                    }
                }

                else -> {
                    journal.entries.remove(rel)
                }
            }
        }

        if (deletionsLocal.size > maxDeletes || deletionsRemote.size > maxDeletes) {
            throw IllegalStateException(
                "检测到大量删除（本地 ${deletionsLocal.size}，远端 ${deletionsRemote.size}），已中止同步以保护数据",
            )
        }
        for (rel in deletionsLocal) {
            val f = File(mirror, rel)
            if (f.isDirectory) {
                if (f.list()?.isEmpty() != false) f.delete()
            } else {
                f.delete()
            }
            journal.entries.remove(rel)
            deletedLocal++
            onProgress("删除本地 $rel")
        }
        for (rel in deletionsRemote) {
            val id = journal.entries[rel]?.docId ?: continue
            runCatching {
                DocumentsContract.deleteDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                )
            }.onFailure { LogStore.append("sync", "remote delete $rel failed: ${it.message}") }
            journal.entries.remove(rel)
            deletedRemote++
            onProgress("删除远端 $rel")
        }

        journal.lastSyncAt = System.currentTimeMillis()
        JournalStore.save(context, workspace.id, journal)
        val result = SyncResult(pushed, pulled, conflicts, deletedLocal, deletedRemote)
        LogStore.append(
            "sync",
            "done pushed=$pushed pulled=$pulled conflicts=$conflicts deletedLocal=$deletedLocal deletedRemote=$deletedRemote",
        )
        result
    }

    private fun scanLocal(root: File): Map<String, LocalRow> {
        val result = mutableMapOf<String, LocalRow>()
        fun walk(dir: File, rel: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (name.startsWith(".harness-") || name.endsWith(".part")) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (child.isDirectory) {
                    if (name in excludedDirs) continue
                    result[childRel] = LocalRow(0, child.lastModified(), true)
                    walk(child, childRel)
                } else if (child.isFile) {
                    if (name == ".DS_Store" || name == "Thumbs.db") continue
                    if (childRel.contains(".git/") && name.endsWith(".lock")) continue
                    result[childRel] = LocalRow(child.length(), child.lastModified(), false)
                }
            }
        }
        walk(root, "")
        return result
    }

    private fun scanRemote(context: Context, treeUri: Uri, rootDocId: String): Map<String, RemoteRow> {
        val result = mutableMapOf<String, RemoteRow>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootDocId to "")
        val resolver = context.contentResolver
        while (queue.isNotEmpty()) {
            val (parentId, parentRel) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            ) ?: continue
            try {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: "unnamed"
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    val mtime = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
                    if (isDir) {
                        if (name in excludedDirs) continue
                    } else {
                        if (name == ".DS_Store" || name == "Thumbs.db") continue
                        if (rel.contains(".git/") && name.endsWith(".lock")) continue
                    }
                    result[rel] = RemoteRow(id, size, mtime, isDir, name)
                    if (isDir) queue.add(id to rel)
                }
            } finally {
                cursor.close()
            }
        }
        return result
    }

    private fun mergeEntry(local: LocalRow, remote: RemoteRow): JournalEntry = JournalEntry(
        docId = remote.docId,
        isDir = remote.isDir,
        size = remote.size,
        mtime = remote.mtime,
        localSize = local.size,
        localMtime = local.mtime,
    )

    private fun conflictCopy(root: File, rel: String): Boolean {
        val source = File(root, rel)
        if (!source.isFile) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = source.name
        val copy = File(source.parentFile, "$name.conflict-$stamp")
        return runCatching {
            source.copyTo(copy, overwrite = false)
            LogStore.append("sync", "conflict copy ${copy.name}")
            true
        }.getOrDefault(false)
    }

    private fun pushFile(
        context: Context,
        treeUri: Uri,
        journal: WorkspaceJournal,
        mirror: File,
        rel: String,
        docId: String,
    ) {
        val resolver = context.contentResolver
        val source = File(mirror, rel)
        resolver.openOutputStream(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            "wt",
        )?.use { out ->
            source.inputStream().use { it.copyTo(out, 128 * 1024) }
        } ?: run {
            // Some providers do not support "wt"; retry without mode.
            resolver.openOutputStream(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            )?.use { out ->
                source.inputStream().use { it.copyTo(out, 128 * 1024) }
            } ?: throw IllegalStateException("无法写入远端 $rel")
        }
    }

    private fun createRemoteFile(
        context: Context,
        treeUri: Uri,
        journal: WorkspaceJournal,
        mirror: File,
        rel: String,
    ): String? {
        val parentRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val parentId = if (parentRel.isEmpty()) journal.rootDocId else ensureRemoteDir(context, treeUri, journal, parentRel) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            "application/octet-stream",
            name,
        ) ?: return null
        val newId = DocumentsContract.getDocumentId(created)
        pushFile(context, treeUri, journal, mirror, rel, newId)
        return newId
    }

    private fun ensureRemoteDir(
        context: Context,
        treeUri: Uri,
        journal: WorkspaceJournal,
        rel: String,
    ): String? {
        journal.entries[rel]?.let { if (it.isDir) return it.docId }
        val parentRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val parentId = if (parentRel.isEmpty()) journal.rootDocId else ensureRemoteDir(context, treeUri, journal, parentRel) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        val id = DocumentsContract.getDocumentId(created)
        journal.entries[rel] = JournalEntry(id, true, 0, 0, 0, 0)
        return id
    }

    private fun pullFile(
        context: Context,
        treeUri: Uri,
        mirror: File,
        rel: String,
        remote: RemoteRow,
    ) {
        val target = File(mirror, rel)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, ".${target.name}.part")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, remote.docId)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取远端 $rel")
        input.use { source ->
            FileOutputStream(part).use { out ->
                source.copyTo(out, 128 * 1024)
                out.fd.sync()
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("无法写入本地 $rel")
        }
        if (remote.mtime > 0) target.setLastModified(remote.mtime)
    }
}
