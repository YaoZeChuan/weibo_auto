package cn.vove7.weibo.auto.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateCheckResult(
    val latestVersion: String,
    val updateAvailable: Boolean,
)

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/YaoZeChuan/weibo_auto/releases/latest"

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("更新服务器返回 ${connection.responseCode}")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val latest = release.optString("tag_name").removePrefix("v")
            require(latest.isNotBlank()) { "未读取到最新版本" }
            UpdateCheckResult(latest, compareVersions(latest, currentVersion) > 0)
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val b = right.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
