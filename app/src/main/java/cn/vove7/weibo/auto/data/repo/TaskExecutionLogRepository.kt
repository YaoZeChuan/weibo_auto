package cn.vove7.weibo.auto.data.repo

import cn.vove7.weibo.auto.data.dao.TaskExecutionLogDao
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import kotlinx.coroutines.flow.Flow

class TaskExecutionLogRepository(
    private val dao: TaskExecutionLogDao,
) {
    fun observeRecent(): Flow<List<TaskExecutionLog>> = dao.observeRecent()

    suspend fun start(accountsSummary: String, tasksSummary: String): Long = dao.insert(
        TaskExecutionLog(
            accountsSummary = accountsSummary,
            tasksSummary = tasksSummary,
        )
    )

    suspend fun finish(id: Long, result: String, detail: String?) {
        dao.finish(id, System.currentTimeMillis(), result, detail)
    }
}
