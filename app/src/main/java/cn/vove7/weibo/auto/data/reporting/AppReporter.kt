package cn.vove7.weibo.auto.data.reporting

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.vove7.weibo.auto.BuildConfig
import cn.vove7.weibo.auto.data.dao.ReportOutboxDao
import cn.vove7.weibo.auto.data.entity.DailyTaskCheckInStatus
import cn.vove7.weibo.auto.data.entity.ReportOutboxItem
import cn.vove7.weibo.auto.data.entity.ReportOutboxType
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import timber.log.Timber

/** Creates durable reports; it never performs network I/O on the caller's path. */
class AppReporter(
    private val context: Context,
    private val outboxDao: ReportOutboxDao,
) {
    private val preferences = context.getSharedPreferences("app_reporting", Context.MODE_PRIVATE)

    suspend fun enqueueHeartbeat(eventType: String, accountCount: Int) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        enqueue(
            ReportOutboxType.HEARTBEAT,
            JSONObject().apply {
                put("installationId", installationId())
                put("eventType", eventType)
                put("occurredAt", System.currentTimeMillis())
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("manufacturer", Build.MANUFACTURER)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("appVersion", packageInfo.versionName ?: "")
                put("appVersionCode", packageInfo.longVersionCode)
                put("accountCount", accountCount.coerceIn(0, 100))
            }.toString(),
        )
    }

    suspend fun enqueueTaskRun(
        startedAt: Long,
        completedAt: Long,
        accountsSummary: String,
        tasksSummary: String,
        result: String,
        detail: String,
        accountCount: Int,
        failedAccountCount: Int,
    ) {
        enqueue(ReportOutboxType.TASK_RUN, JSONObject().apply {
            put("installationId", installationId())
            put("clientRunId", UUID.randomUUID().toString())
            put("startedAt", startedAt)
            put("completedAt", completedAt.coerceAtLeast(startedAt))
            put("accountsSummary", accountsSummary.take(2048))
            put("tasksSummary", tasksSummary.take(2048))
            put("result", result)
            // Keep UTF-8 JSON safely below the endpoint's 128 KiB request limit.
            put("detail", redact(detail).take(30_000))
            put("accountCount", accountCount.coerceIn(0, 100))
            put("failedAccountCount", failedAccountCount.coerceIn(0, accountCount.coerceIn(0, 100)))
            put("appVersion", BuildConfig.VERSION_NAME)
        }.toString())
    }

    suspend fun enqueueAccountSnapshot(accounts: List<WeiboAccount>, isComplete: Boolean) {
        val observedAt = System.currentTimeMillis()
        val localDate = DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(observedAt).atZone(ZoneId.of("Asia/Shanghai"))
        )
        val values = JSONArray()
        accounts.take(100).forEach { account ->
            values.put(JSONObject().apply {
                put("clientAccountKey", account.uid.take(128))
                put("accountName", account.name.take(64))
                put("checkInStatus", account.dailyCheckInStatus.takeIf {
                    it in setOf(DailyTaskCheckInStatus.COMPLETED, DailyTaskCheckInStatus.INCOMPLETE, DailyTaskCheckInStatus.UNKNOWN)
                } ?: DailyTaskCheckInStatus.UNKNOWN)
                putNullable("browseCompleted", account.dailyBrowseCompletedCount)
                putNullable("browseRequired", account.dailyBrowseRequiredCount)
                putNullable("commentCompleted", account.dailyCommentCompletedCount)
                putNullable("commentRequired", account.dailyCommentRequiredCount)
                put("waterPostCompleted", account.dailyWaterPostCompletedCount)
                putNullable("superLikeLit", if (account.superLikeExp >= 0) account.superLikeLit else null)
                putNullable("superLikeExp", account.superLikeExp)
            })
        }
        enqueue(ReportOutboxType.ACCOUNT_SNAPSHOT, JSONObject().apply {
            put("installationId", installationId())
            put("snapshotId", UUID.randomUUID().toString())
            put("observedAt", observedAt)
            put("localDate", localDate)
            put("isComplete", isComplete)
            put("accounts", values)
        }.toString())
    }

    private suspend fun enqueue(type: String, payload: String) {
        val id = outboxDao.insert(ReportOutboxItem(type = type, payload = payload))
        Timber.tag(TAG).i("enqueued type=%s id=%d", type, id)
        scheduleUpload(context)
    }

    private fun installationId(): String = preferences.getString("installation_id", null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString("installation_id", it).apply() }

    private fun JSONObject.putNullable(name: String, value: Int?) = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.putNullable(name: String, value: Boolean?) = put(name, value ?: JSONObject.NULL)

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(password|cookie)\\s*[:=]\\s*[^\\s]+"), "$1=[REDACTED]")

    companion object {
        fun scheduleUpload(context: Context) {
            if (BuildConfig.REPORT_BASE_URL.isBlank() || BuildConfig.REPORT_APP_KEY.isBlank()) {
                Timber.tag(TAG).w("upload skipped: reporting is not configured")
                return
            }
            val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "app-report-upload", ExistingWorkPolicy.KEEP, request
            )
            Timber.tag(TAG).i("upload scheduled")
        }

        private const val TAG = "AppReporting"
    }
}
