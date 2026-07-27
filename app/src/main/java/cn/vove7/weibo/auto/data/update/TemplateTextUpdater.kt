package cn.vove7.weibo.auto.data.update

import android.content.Context
import cn.vove7.weibo.auto.BuildConfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

data class TemplateTextUpdate(
    val postTexts: List<String>,
    val commentTexts: List<String>,
    val unchanged: Boolean = false,
)

object TemplateTextUpdater {
    suspend fun download(context: Context): TemplateTextUpdate = withContext(Dispatchers.IO) {
        require(BuildConfig.REPORT_BASE_URL.isNotBlank() && BuildConfig.REPORT_APP_KEY.isNotBlank()) {
            "Report server is not configured"
        }
        val preferences = context.getSharedPreferences("app_reporting", Context.MODE_PRIVATE)
        val connection = (URL(BuildConfig.REPORT_BASE_URL.trimEnd('/') + "/app/v1/templates").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "XiaomiAssistant-Android")
            setRequestProperty("X-App-Key", BuildConfig.REPORT_APP_KEY)
            preferences.getString("templates_etag", null)?.let { setRequestProperty("If-None-Match", it) }
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@withContext TemplateTextUpdate(emptyList(), emptyList(), unchanged = true)
            }
            if (connection.responseCode !in 200..299) {
                error("文案服务器返回 ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val postTexts = json.requiredStringArray("fatie", "发帖模板")
            val commentTexts = json.requiredStringArray("pinglun", "评论模板")
            connection.getHeaderField("ETag")?.let { preferences.edit().putString("templates_etag", it).apply() }
            TemplateTextUpdate(
                postTexts = postTexts,
                commentTexts = commentTexts,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.requiredStringArray(key: String, label: String): List<String> {
        val array = optJSONArray(key) ?: error("未读取到${label}")
        val values = array.toStringList()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        require(values.isNotEmpty()) { "${label}为空" }
        return values
    }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> optString(index) }
}
