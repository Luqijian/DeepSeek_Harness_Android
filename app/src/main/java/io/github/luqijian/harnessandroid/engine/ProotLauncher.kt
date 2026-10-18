package io.github.luqijian.harnessandroid.engine

import android.content.Context
import android.system.Os
import io.github.luqijian.harnessandroid.core.AppPaths
import io.github.luqijian.harnessandroid.core.LogStore
import io.github.luqijian.harnessandroid.workspace.Workspace
import io.github.luqijian.harnessandroid.workspace.WorkspaceMode
import java.io.File

data class LaunchSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workspaceGuestPath: String,
    val pidFile: File,
)

object ProotLauncher {
    private const val GUEST_PORT = 3080

    fun build(context: Context, workspace: Workspace, rootfs: File): LaunchSpec {
        AppPaths.ensureBaseDirs(context)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot_loader.so")
        if (!proot.isFile) {
            throw IllegalStateException("缺少 proot 运行时（$proot）")
        }
        if (!loader.isFile) {
            throw IllegalStateException("缺少 proot loader（$loader）")
        }

        val backend = resolveBackend(context, workspace)
        val dshHome = AppPaths.dshHome(context).apply { mkdirs() }
        val docs = DshConfig.hostDocuments(context, workspace).apply { mkdirs() }
        val tmp = AppPaths.tmp(context).apply { mkdirs() }
        val prootTmp = File(tmp, "proot").apply { mkdirs() }
        val resolv = File(tmp, "resolv.conf")
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n")
        val gitConfig = File(dshHome, "gitconfig")
        if (!gitConfig.isFile) {
            gitConfig.writeText(
                """
                [safe]
                    directory = *
                [core]
                    fileMode = false
                """.trimIndent() + "\n",
            )
        }
        val pidFile = AppPaths.enginePid(context)

        val launcher = AppPaths.launcher(context)
        launcher.parentFile?.mkdirs()
        launcher.writeText(
            """
            #!/system/bin/sh
            echo ${'$'}${'$'} > "${'$'}1"
            shift
            exec setsid "${'$'}@"
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, true)
        LogStore.append("launch", "launcher=$launcher")

        val args = mutableListOf<String>()
        args += proot.absolutePath
        args += "--link2symlink"
        args += "-r"
        args += rootfs.absolutePath
        args += "-b"
        args += "/dev"
        args += "-b"
        args += "/proc"
        args += "-b"
        args += "/sys"
        args += "-b"
        args += "${dshHome.absolutePath}:/root/.dsh"
        args += "-b"
        args += "${docs.absolutePath}:${DshConfig.guestDocuments(workspace)}"
        args += "-b"
        args += "${backend.absolutePath}:${workspace.guestPath}"
        // dsh persists its first workspace as /workspaces/default. Keep that path
        // pointing at the active backend too, so the existing UI session and the
        // native workspace picker always agree.
        args += "-b"
        args += "${backend.absolutePath}:/workspaces/default"
        args += "-b"
        args += "${tmp.absolutePath}:/tmp"
        args += "-b"
        args += "${resolv.absolutePath}:/etc/resolv.conf"
        args += "-0"
        args += "-w"
        args += workspace.guestPath
        args += "/usr/bin/env"
        args += "HOME=/root"
        args += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        args += "SHELL=/bin/bash"
        args += "TERM=xterm-256color"
        args += "LANG=C.UTF-8"
        args += "TMPDIR=/tmp"
        args += "DSH_HOME=/root/.dsh"
        args += "DSH_PERMISSION_MODE=danger-full-access"
        args += "GIT_CONFIG_GLOBAL=/root/.dsh/gitconfig"
        args += "npm_config_cache=/root/.npm"
        args += "npm_config_fund=false"
        args += "npm_config_audit=false"
        args += "/usr/local/bin/node"
        args += "--expose-internals"
        args += "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        args += "web"
        args += "--no-open"
        args += "--host"
        args += "127.0.0.1"
        args += "--port"
        args += GUEST_PORT.toString()

        val command = mutableListOf("/system/bin/sh", launcher.absolutePath, pidFile.absolutePath)
        command += args

        val env = mapOf(
            "LD_LIBRARY_PATH" to nativeDir,
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to prootTmp.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to dshHome.absolutePath,
            "TMPDIR" to tmp.absolutePath,
        )
        return LaunchSpec(command, env, workspace.guestPath, pidFile)
    }

    private fun resolveBackend(context: Context, workspace: Workspace): File {
        val direct = workspace.mode == WorkspaceMode.DIRECT
        val raw = workspace.backendPath(context)
        if (!raw.isDirectory) {
            raw.mkdirs()
        }
        if (!direct) return raw
        // proot binds use ':' as separator; alias paths containing one.
        if (!raw.absolutePath.contains(':')) return raw
        val alias = File(AppPaths.bindAliases(context), workspace.id)
        if (alias.exists() || alias.isFile) alias.delete()
        runCatching {
            Os.symlink(raw.absolutePath, alias.absolutePath)
        }.onFailure {
            throw IllegalStateException("工作区路径包含 ':' 且无法创建绑定别名", it)
        }
        return alias
    }
}
