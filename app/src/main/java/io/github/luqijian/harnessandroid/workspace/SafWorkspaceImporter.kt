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

data class ImportProgress(
    val files: Int,
    val dirs: Int,
    val bytes: Long,
    val current: String,
)

data class ImportResult(
    val fileCount: Int,
    val dirCount: Int,
    val bytes: Long,
    val journal: WorkspaceJournal,
)

object SafWorkspaceImporter {
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

    private data class Row(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val mtime: Long,
    )

    private fun isExcluded(relPath: String, isDir: Boolean): Boolean {
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (isDir && segments.any { it in excludedDirs }) return true
        val name = segments.last()
        if (name == ".DS_Store" || name == "Thumbs.db") return true
        if (relPath.endsWith("/.git/index.lock") || relPath == ".git/index.lock") return true
        if (relPath.contains("/.git/") && name.endsWith(".lock")) return true
        return false
    }

    suspend fun import(
        context: Context,
        workspace: Workspace,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        val treeUri = workspace.treeUri?.let(Uri::parse)
            ?: throw IllegalArgumentException("workspace has no SAF tree URI")
        val resolver = context.contentResolver
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IllegalStateException("无法读取文件夹授权，请重新选择", it) }
        val mirror = workspace.backendPath(context)
        if (mirror.exists()) {
            // New workspace import: keep the mirror clean so the journal matches the remote.
            mirror.listFiles()?.forEach { it.deleteRecursively() }
        }
        mirror.mkdirs()
        val journal = WorkspaceJournal(treeUri.toString(), rootDocId)

        var files = 0
        var dirs = 0
        var bytes = 0L
        val queue = ArrayDeque<Pair<String, String>>() // docId to relative path
        queue.add(rootDocId to "")
        onProgress(ImportProgress(0, 0, 0, ""))

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
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
                    currentCoroutineContext().ensureActive()
                    val row = Row(
                        docId = cursor.getString(0),
                        name = cursor.getString(1) ?: "unnamed",
                        mime = cursor.getString(2) ?: "",
                        size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                        mtime = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    )
                    val safeName = sanitizeName(row.name)
                    val rel = if (parentRel.isEmpty()) safeName else "$parentRel/$safeName"
                    val isDir = row.mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isExcluded(rel, isDir)) {
                        LogStore.append("import", "skip $rel")
                        continue
                    }
                    val local = File(mirror, rel)
                    if (!local.canonicalPath.startsWith(mirror.canonicalPath + File.separator)) {
                        LogStore.append("import", "reject escaping path $rel")
                        continue
                    }
                    if (isDir) {
                        local.mkdirs()
                        dirs++
                        journal.entries[rel] = JournalEntry(
                            docId = row.docId,
                            isDir = true,
                            size = row.size,
                            mtime = row.mtime,
                            localSize = 0,
                            localMtime = local.lastModified(),
                        )
                        queue.add(row.docId to rel)
                    } else {
                        local.parentFile?.mkdirs()
                        val part = File(local.parentFile, ".${local.name}.part")
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.docId)
                        val copied = copyFile(resolver, uri, part)
                        if (part.renameTo(local)) {
                            local.setLastModified(if (row.mtime > 0) row.mtime else System.currentTimeMillis())
                            files++
                            bytes += copied
                            journal.entries[rel] = JournalEntry(
                                docId = row.docId,
                                isDir = false,
                                size = row.size,
                                mtime = row.mtime,
                                localSize = copied,
                                localMtime = local.lastModified(),
                            )
                            onProgress(ImportProgress(files, dirs, bytes, rel))
                        } else {
                            part.delete()
                            throw IllegalStateException("无法写入 $rel")
                        }
                    }
                }
            } finally {
                cursor.close()
            }
        }
        journal.lastSyncAt = System.currentTimeMillis()
        JournalStore.save(context, workspace.id, journal)
        LogStore.append("import", "imported $files files/$dirs dirs ($bytes bytes) into ${mirror.path}")
        ImportResult(files, dirs, bytes, journal)
    }

    private fun copyFile(resolver: ContentResolver, uri: Uri, target: File): Long {
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取 $uri")
        input.use { source ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                out.fd.sync()
                return total
            }
        }
    }

    private fun sanitizeName(name: String): String {
        var s = name.replace('\u0000', '_').replace('/', '_').replace('\\', '_').trim()
        if (s.isEmpty()) s = "unnamed"
        if (s == "." || s == "..") s = "_$s"
        return s
    }
}
