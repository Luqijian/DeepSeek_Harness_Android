package io.github.luqijian.harnessandroid.core

import android.content.Context
import java.io.File

object AppPaths {
    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime")

    fun runtimeDir(context: Context, version: String): File {
        val safe = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(runtimeRoot(context), safe)
    }

    fun rootfs(context: Context, version: String): File = File(runtimeDir(context, version), "rootfs")

    fun dshHome(context: Context): File = File(context.filesDir, "dsh-home")

    fun documents(context: Context): File = File(context.filesDir, "documents")

    fun workspaces(context: Context): File = File(context.filesDir, "workspaces")

    fun tmp(context: Context): File = File(context.filesDir, "tmp")

    fun bindAliases(context: Context): File = File(context.filesDir, "bind")

    fun logs(context: Context): File = File(context.filesDir, "logs")

    fun engineLog(context: Context): File = File(logs(context), "engine.log")

    fun launcher(context: Context): File = File(context.filesDir, "bin/start-engine.sh")

    fun enginePid(context: Context): File = File(tmp(context), "engine.pid")

    fun ensureBaseDirs(context: Context) {
        listOf(
            runtimeRoot(context),
            dshHome(context),
            documents(context),
            workspaces(context),
            tmp(context),
            bindAliases(context),
            logs(context),
            File(context.filesDir, "bin"),
        ).forEach { it.mkdirs() }
        File(tmp(context), "proot").mkdirs()
    }
}
