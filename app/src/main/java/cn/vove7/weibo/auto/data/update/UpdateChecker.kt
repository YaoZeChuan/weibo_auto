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
)

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/YaoZeChuan/weibo_auto/releases/latest"

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val acceleratedApiUrl = GitHubAccelerator.accelerate(LATEST_RELEASE_URL)
        Timber.i("Update API URL: %s", acceleratedApiUrl)
        val connection = (URL(acceleratedApiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "XiaomiAssistant-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("更新服务器返回 ${connection.responseCode}")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val latest = release.optString("tag_name").removePrefix("v")
            require(latest.isNotBlank()) { "未读取到最新版本" }
            val apkUrl = release.optJSONArray("assets")
                ?.let { assets ->
                    (0 until assets.length())
                        .asSequence()
                        .map { assets.optJSONObject(it) }
                        .firstOrNull { asset ->
                            asset?.optString("name")?.endsWith(".apk", ignoreCase = true) == true
                        }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.isNotBlank() }
                }
            Timber.i("Update release parsed: version=%s, apkUrl=%s", latest, apkUrl)
            UpdateCheckResult(
                latestVersion = latest,
                updateAvailable = compareVersions(latest, currentVersion) > 0,
                apkDownloadUrl = apkUrl,
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
