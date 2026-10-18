package io.github.luqijian.harnessandroid.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val text: String)

object LogStore {
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private val lines = ArrayDeque<LogLine>()
    private val state = MutableStateFlow<List<String>>(emptyList())
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        AppPaths.ensureBaseDirs(context)
        logFile = AppPaths.engineLog(context)
        if (logFile?.length() ?: 0L > MAX_FILE_BYTES) {
            logFile?.delete()
        }
    }

    val entries: StateFlow<List<String>> = state.asStateFlow()

    fun append(tag: String, message: String) {
        val clean = message.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        val line = "${timeFormat.format(Date())} [$tag] $clean"
        synchronized(lock) {
            lines.addLast(LogLine(line))
            while (lines.size > MAX_LINES) lines.removeFirst()
            state.value = lines.map { it.text }
            runCatching {
                val file = logFile ?: return@runCatching
                if (file.length() > MAX_FILE_BYTES) {
                    val old = File(file.parentFile, "engine.log.1")
                    file.renameTo(old)
                    file.delete()
                }
                file.appendText(line + "\n")
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.map { it.text } }

    fun tail(count: Int = 80): String = synchronized(lock) {
        lines.takeLast(count).joinToString("\n")
    }
}
