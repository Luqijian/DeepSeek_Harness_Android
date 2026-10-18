package io.github.luqijian.harnessandroid.engine

import android.content.Context
import android.system.Os
import io.github.luqijian.harnessandroid.core.AppPaths
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.workspace.Workspace
import java.io.File

object DshConfig {
    private const val START = "# >>> harness-android managed >>>"
    private const val END = "# <<< harness-android managed <<<"

    fun apply(context: Context, workspace: Workspace): File {
        val profileDir = File(AppPaths.dshHome(context), "profiles/web")
        profileDir.mkdirs()
        val file = File(profileDir, "cordis.patch.yml")
        val block = buildString {
            appendLine(START)
            appendLine("- id: fs-sandbox")
            appendLine("  config:")
            appendLine("    cwd: '${yaml(workspace.guestPath)}'")
            appendLine("- id: sandbox-policy")
            appendLine("  config:")
            appendLine("    mode: danger-full-access")
            appendLine("- id: workspace-controller")
            appendLine("  config:")
            appendLine("    documentsDirectory: '${yaml(guestDocuments(workspace))}'")
            appendLine("- id: permission")
            appendLine("  config:")
            appendLine("    defaultPreset: danger-full-access")
            appendLine(END)
        }

        val existing = if (file.isFile) file.readText() else ""
        val withoutManaged = removeManaged(existing)
        val merged = when {
            withoutManaged.isBlank() || withoutManaged.trim() == "[]" -> block
            else -> withoutManaged.trimEnd() + "\n" + block
        }
        file.writeText(merged)
        LogStore.append("config", "wrote ${file.path}")

        val docs = hostDocuments(context, workspace)
        val harnessDir = File(docs, "deepseek-harness")
        harnessDir.mkdirs()
        val defaultWorkspace = File(harnessDir, "default-workspace")
        val isLink = java.nio.file.Files.isSymbolicLink(defaultWorkspace.toPath())
        if (isLink || defaultWorkspace.exists() || defaultWorkspace.isFile) {
            if (!isLink) {
                // A real directory created by an earlier dsh boot can contain session data.
                // Keep it and rely on the workspace picker rather than deleting user data.
                LogStore.append(
                    "config",
                    "warning: ${defaultWorkspace.path} is not a symlink; leaving it untouched",
                )
                return file
            }
            defaultWorkspace.delete()
        }
        runCatching {
            Os.symlink(workspace.guestPath, defaultWorkspace.absolutePath)
            LogStore.append("config", "linked default workspace -> ${workspace.guestPath}")
        }.onFailure {
            LogStore.append("config", "failed to link default workspace: ${it.message}")
        }
        return file
    }

    fun hostDocuments(context: Context, workspace: Workspace): File =
        File(File(AppPaths.documents(context), "by-workspace"), workspace.id)

    fun guestDocuments(workspace: Workspace): String = "/root/documents/${workspace.id}"

    private fun removeManaged(text: String): String {
        val start = text.indexOf(START)
        if (start < 0) return text
        val end = text.indexOf(END, start)
        if (end < 0) return text.substring(0, start)
        return text.substring(0, start) + text.substring(end + END.length)
    }

    private fun yaml(value: String): String = value.replace("'", "''")
}
