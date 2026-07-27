package cn.vove7.weibo.auto.data.reporting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.vove7.weibo.auto.BuildConfig
import cn.vove7.weibo.auto.WeiboApp
import cn.vove7.weibo.auto.data.entity.ReportOutboxState
import cn.vove7.weibo.auto.data.entity.ReportOutboxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

class ReportUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (BuildConfig.REPORT_BASE_URL.isBlank() || BuildConfig.REPORT_APP_KEY.isBlank()) {
            Timber.tag(TAG).w("worker skipped: reporting is not configured")
            return@withContext Result.success()
        }
        val outbox = (applicationContext as WeiboApp).database.reportOutboxDao()
        val pending = outbox.pending()
        Timber.tag(TAG).i("worker started: pending=%d", pending.size)
        for (item in pending) {
            val path = when (item.type) {
                ReportOutboxType.HEARTBEAT -> "/app/v1/heartbeat"
                ReportOutboxType.TASK_RUN -> "/app/v1/task-runs"
                ReportOutboxType.ACCOUNT_SNAPSHOT -> "/app/v1/account-snapshots"
                else -> {
                    outbox.markFailed(item.id, "unknown report type")
                    Timber.tag(TAG).e("outbox failed: id=%d unknown type=%s", item.id, item.type)
                    continue
                }
            }
            Timber.tag(TAG).i("sending id=%d type=%s path=%s", item.id, item.type, path)
            val code = try { post(path, item.payload) } catch (e: Exception) {
                outbox.recordRetry(item.id, e.javaClass.simpleName)
                Timber.tag(TAG).w(e, "send retry: id=%d type=%s", item.id, item.type)
                return@withContext Result.retry()
            }
            when {
                code in 200..299 -> {
                    outbox.markUploaded(item.id)
                    Timber.tag(TAG).i("send success: id=%d type=%s http=%d", item.id, item.type, code)
                }
                code == 429 || code >= 500 -> {
                    outbox.recordRetry(item.id, "HTTP $code")
                    Timber.tag(TAG).w("send retry: id=%d type=%s http=%d", item.id, item.type, code)
                    return@withContext Result.retry()
                }
                else -> {
                    outbox.markFailed(item.id, "HTTP $code")
                    Timber.tag(TAG).e("send failed: id=%d type=%s http=%d", item.id, item.type, code)
                }
            }
        }
        Result.success()
    }

    private fun post(path: String, payload: String): Int {
        val connection = (URL(BuildConfig.REPORT_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-App-Key", BuildConfig.REPORT_APP_KEY)
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            connection.responseCode
        } finally { connection.disconnect() }
    }

    private companion object {
        const val TAG = "AppReporting"
    }
}
