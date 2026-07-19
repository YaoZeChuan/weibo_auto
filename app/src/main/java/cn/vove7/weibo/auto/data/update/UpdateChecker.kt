package cn.vove7.weibo.auto.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

data class UpdateCheckResult(
    val latestVersion: String,
    val updateAvailable: Boolean,
    val apkDownloadUrl: String?,
    val apkSha256: String?,
)

object UpdateChecker {
    private const val UPDATE_MANIFEST_URL =
        "https://miniapi.o3.yaozc.ccwu.cc/files/yaozechuan/version.json"

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        Timber.i("Update manifest URL: %s", UPDATE_MANIFEST_URL)
        val connection = (URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "XiaomiAssistant-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("更新服务器返回 ${connection.responseCode}")
            }
            val manifest = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val latest = manifest.optString("version").removePrefix("v")
            require(latest.isNotBlank()) { "未读取到最新版本" }
            val apkUrl = manifest.optString("apkUrl").takeIf { it.isNotBlank() }
            val sha256 = manifest.optString("sha256").takeIf { it.isNotBlank() }
            Timber.i("Update manifest parsed: version=%s, apkUrl=%s", latest, apkUrl)
            UpdateCheckResult(
                latestVersion = latest,
                updateAvailable = compareVersions(latest, currentVersion) > 0,
                apkDownloadUrl = apkUrl,
                apkSha256 = sha256,
            )
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
