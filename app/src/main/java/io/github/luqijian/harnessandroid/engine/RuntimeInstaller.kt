package io.github.luqijian.harnessandroid.engine

import android.content.Context
import android.os.StatFs
import android.system.Os
import io.github.luqijian.harnessandroid.BuildConfig
import io.github.luqijian.harnessandroid.core.AppPaths
import io.github.luqijian.harnessandroid.core.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class RuntimeManifest(
    val version: String = BuildConfig.RUNTIME_VERSION,
    val dshVersion: String = BuildConfig.DSH_VERSION,
    val sha256: String = "",
    val size: Long = 0L,
    val installedSize: Long = 0L,
)

object RuntimeInstaller {
    private const val ASSET_ROOTFS = "runtime/rootfs.bin"
    private const val ASSET_MANIFEST = "runtime/manifest.json"
    private const val MIN_FREE_BYTES = 1_400L * 1024L * 1024L

    suspend fun ensureInstalled(
        context: Context,
        version: String,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        AppPaths.ensureBaseDirs(context)
        val manifest = readManifest(context, version)
        val target = AppPaths.runtimeDir(context, manifest.version)
        val rootfs = File(target, "rootfs")
        val marker = File(target, ".complete")
        if (marker.isFile && rootfs.isDirectory) {
            LogStore.append("runtime", "runtime ${manifest.version} already installed at $target")
            return@withContext rootfs
        }

        val stat = StatFs(context.filesDir.path)
        if (stat.availableBytes < MIN_FREE_BYTES) {
            throw IllegalStateException(
                "设备空间不足：需要至少 ${MIN_FREE_BYTES / 1024 / 1024} MB 可用空间，当前 ${stat.availableBytes / 1024 / 1024} MB",
            )
        }

        // openFd only works for uncompressed assets; the runtime blob is stored
        // compressed, so fall back to the size recorded in manifest.json.
        val assetSize = maxOf(
            manifest.size,
            runCatching {
                context.assets.openFd(ASSET_ROOTFS).use { it.length }
            }.getOrDefault(0L),
        )

        val tmp = File(AppPaths.runtimeRoot(context), "extract-${System.currentTimeMillis()}")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpRootfs = File(tmp, "rootfs")
        tmpRootfs.mkdirs()

        LogStore.append("runtime", "installing runtime ${manifest.version} (asset=${assetSize}B) to $tmp")
        onProgress(0f, "准备解压运行时")

        try {
            openAsset(context) { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val counted = object : java.io.FilterInputStream(input) {
                    var total = 0L
                    override fun read(): Int {
                        val b = super.read()
                        if (b >= 0) {
                            total++
                            digest.update(b.toByte())
                        }
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            total += n
                            digest.update(b, off, n)
                        }
                        return n
                    }
                }
                val buffered = BufferedInputStream(counted, 128 * 1024)
                val gzip = GzipCompressorInputStream(buffered, true)
                TarArchiveInputStream(gzip).use { tar ->
                    var next: TarArchiveEntry? = tar.nextEntry
                    var lastReport = 0L
                    var files = 0
                    while (next != null) {
                        currentCoroutineContext().ensureActive()
                        val entry = next
                        extractEntry(tar, entry, tmpRootfs)
                        files++
                        val now = counted.total
                        if (assetSize > 0 && now - lastReport > 2L * 1024L * 1024L) {
                            lastReport = now
                            val p = (now.toDouble() / assetSize.toDouble()).coerceIn(0.0, 0.99).toFloat()
                            onProgress(p, "正在解压运行时（$files 个文件）")
                        }
                        next = tar.nextEntry
                    }
                    LogStore.append("runtime", "extracted $files entries")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (manifest.sha256.isNotBlank() && !actual.equals(manifest.sha256, ignoreCase = true)) {
                    throw IllegalStateException("运行时校验失败：SHA-256 不匹配")
                }
                LogStore.append("runtime", "rootfs sha256=$actual")
            }

            val node = File(tmpRootfs, "usr/local/bin/node")
            val dshBin = File(tmpRootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            if (!node.isFile || !dshBin.isFile) {
                throw IllegalStateException(
                    "运行时内容不完整：缺少 node 或 dsh（node=${node.isFile}, dsh=${dshBin.isFile}）",
                )
            }

            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            if (!tmpRootfs.renameTo(File(target, "rootfs"))) {
                throw IllegalStateException("无法移动解压后的运行时目录")
            }
            File(target, ".complete").writeText(
                JSONObject()
                    .put("version", manifest.version)
                    .put("dshVersion", manifest.dshVersion)
                    .put("sha256", manifest.sha256)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(),
            )
            onProgress(1f, "运行时安装完成")
            LogStore.append("runtime", "runtime ${manifest.version} installed")
            File(target, "rootfs")
        } catch (t: Throwable) {
            LogStore.append("runtime", "install failed: ${t.stackTraceToString()}")
            tmp.deleteRecursively()
            throw t
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    private suspend fun openAsset(context: Context, block: suspend (java.io.InputStream) -> Unit) {
        val stream = try {
            context.assets.open(ASSET_ROOTFS)
        } catch (e: Exception) {
            throw IllegalStateException(
                "运行时资源缺失（$ASSET_ROOTFS）。请安装由 CI 构建的 APK，或在本地运行 runtime 构建脚本。",
                e,
            )
        }
        try {
            block(stream)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun readManifest(context: Context, fallbackVersion: String): RuntimeManifest {
        val json = runCatching {
            context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return RuntimeManifest(version = fallbackVersion)
        return runCatching {
            val obj = JSONObject(json)
            RuntimeManifest(
                version = obj.optString("version", fallbackVersion),
                dshVersion = obj.optString("dshVersion", BuildConfig.DSH_VERSION),
                sha256 = obj.optString("sha256", ""),
                size = obj.optLong("size", 0L),
                installedSize = obj.optLong("installedSize", 0L),
            )
        }.getOrElse {
            LogStore.append("runtime", "manifest parse failed: ${it.message}")
            RuntimeManifest(version = fallbackVersion)
        }
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, root: File) {
        val name = entry.name.replace('\\', '/').trimStart('/').removePrefix("./")
        if (name.isEmpty() || name == ".") return
        if (name.contains("../") || name == "..") {
            throw IllegalStateException("运行时归档包含非法路径: ${entry.name}")
        }
        val dest = File(root, name)
        val canonicalRoot = root.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(canonicalRoot)) {
            throw IllegalStateException("运行时归档路径越界: ${entry.name}")
        }

        when {
            entry.isDirectory -> {
                dest.mkdirs()
                dest.setReadable(true, true)
                dest.setWritable(true, true)
                dest.setExecutable(true, true)
            }

            entry.isSymbolicLink -> {
                dest.parentFile?.mkdirs()
                if (dest.exists() || dest.isFile) dest.delete()
                runCatching {
                    Os.symlink(entry.linkName, dest.absolutePath)
                }.onFailure {
                    throw IllegalStateException("无法创建符号链接 ${entry.name} -> ${entry.linkName}", it)
                }
            }

            entry.isLink -> {
                dest.parentFile?.mkdirs()
                val target = File(root, entry.linkName.trimStart('/'))
                if (target.isFile) {
                    dest.delete()
                    target.copyTo(dest, overwrite = true)
                    applyMode(dest, entry.mode)
                } else {
                    dest.createNewFile()
                    applyMode(dest, entry.mode)
                }
            }

            entry.isFile -> {
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { out ->
                    tar.copyTo(out, 128 * 1024)
                }
                applyMode(dest, entry.mode)
                runCatching { dest.setLastModified(entry.modTime.time) }
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (mode and 0b001_001_001 != 0) {
            file.setExecutable(true, true)
        }
    }
}
