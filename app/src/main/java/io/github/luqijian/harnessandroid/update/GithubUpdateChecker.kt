package io.github.luqijian.harnessandroid.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.luqijian.harnessandroid.BuildConfig
import io.github.luqijian.harnessandroid.core.LogStore
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object GithubUpdateChecker {
    private const val TIMEOUT = 15000

    fun check(activity: Activity, onStatus: (String) -> Unit) {
        Thread {
            try {
                val api = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
                val conn = URL(api).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "harness-android-updater")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "")
                val assets = release.optJSONArray("assets") ?: org.json.JSONArray()
                var updateJsonUrl: String? = null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (name == "update.json") updateJsonUrl = a.optString("browser_download_url")
                    if (name.endsWith(".apk")) apkUrl = a.optString("browser_download_url")
                }
                if (updateJsonUrl == null || apkUrl == null) {
                    post(activity, onStatus, "Release $tag 缺少 update.json 或 APK")
                    return@Thread
                }
                val meta = JSONObject(fetch(updateJsonUrl))
                val latestCode = meta.optInt("versionCode", 0)
                if (latestCode <= BuildConfig.VERSION_CODE) {
                    post(activity, onStatus, "当前已是最新版本（$tag）")
                    return@Thread
                }
                post(activity, onStatus, "发现新版本 ${meta.optString("versionName", tag)}，开始下载…")
                val apk = download(activity, apkUrl)
                val expected = meta.optString("apkSha256", "")
                if (expected.isNotBlank() && !sha256(apk).equals(expected, ignoreCase = true)) {
                    apk.delete()
                    post(activity, onStatus, "更新包校验失败，已取消安装")
                    return@Thread
                }
                post(activity, onStatus, "下载完成，正在启动安装器…")
                install(activity, apk, onStatus)
            } catch (t: Throwable) {
                LogStore.append("update", "check failed: ${t.stackTraceToString()}")
                post(activity, onStatus, "更新检查失败：${t.message}")
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File, onStatusRef: (String) -> Unit) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread {
            runCatching { activity.startActivity(intent) }
                .onFailure { post(activity, onStatusRef, "请手动安装已下载的 APK") }
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.setRequestProperty("User-Agent", "harness-android-updater")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "harness-android-updater")
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
                output.fd.sync()
            }
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun post(activity: Activity, onStatus: (String) -> Unit, message: String) {
        activity.runOnUiThread { onStatus(message) }
    }
}
