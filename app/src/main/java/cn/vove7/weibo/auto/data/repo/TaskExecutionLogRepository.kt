package cn.vove7.weibo.auto.data.repo

import cn.vove7.weibo.auto.data.dao.TaskExecutionLogDao
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import kotlinx.coroutines.flow.Flow

class TaskExecutionLogRepository(
    private val dao: TaskExecutionLogDao,
) {
    fun observeRecent(): Flow<List<TaskExecutionLog>> = dao.observeRecent(retentionStartMillis())

    suspend fun start(accountsSummary: String, tasksSummary: String): Long {
        dao.deleteBefore(retentionStartMillis())
        return dao.insert(
            TaskExecutionLog(
                accountsSummary = accountsSummary,
                tasksSummary = tasksSummary,
            )
        )
    }

    suspend fun finish(id: Long, result: String, detail: String?) {
        dao.finish(id, System.currentTimeMillis(), result, detail)
    }

    private fun retentionStartMillis(now: Long = System.currentTimeMillis()): Long =
        now - RETENTION_MILLIS

    private companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}
