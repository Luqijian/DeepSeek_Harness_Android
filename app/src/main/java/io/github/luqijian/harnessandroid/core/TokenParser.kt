package io.github.luqijian.harnessandroid.core

object TokenParser {
    private val ansi = Regex("\\u001B\\[[;\\d]*m")
    private val urlPattern = Regex(
        """https?://(?:127\.0\.0\.1|localhost):(\d+)/\?token=([A-Za-z0-9._~%+\-]+)""",
        RegexOption.IGNORE_CASE,
    )

    data class LaunchUrl(val url: String, val port: Int, val token: String)

    fun parse(line: String): LaunchUrl? {
        val clean = ansi.replace(line, "")
        val match = urlPattern.find(clean) ?: return null
        val port = match.groupValues[1].toIntOrNull() ?: return null
        return LaunchUrl(match.value, port, match.groupValues[2])
    }
}
