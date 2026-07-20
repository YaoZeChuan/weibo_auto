package cn.vove7.weibo.auto.data.update

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
)

object TemplateTextUpdater {
    private const val TEMPLATE_TEXT_URL =
        "https://file.qingzhou.link/yaozechuan/comment.json"

    suspend fun download(): TemplateTextUpdate = withContext(Dispatchers.IO) {
        Timber.i("Template text URL: %s", TEMPLATE_TEXT_URL)
        val connection = (URL(TEMPLATE_TEXT_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "XiaomiAssistant-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("文案服务器返回 ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val postTexts = json.requiredStringArray("fatie", "发帖模板")
            val commentTexts = json.requiredStringArray("pinglun", "评论模板")
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
