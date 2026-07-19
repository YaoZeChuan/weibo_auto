package cn.vove7.weibo.auto.data.repo

import cn.vove7.weibo.auto.data.dao.TaskRecordDao
import cn.vove7.weibo.auto.data.entity.TaskRecord
import cn.vove7.weibo.auto.data.entity.TaskRecordType
import cn.vove7.weibo.auto.data.entity.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class TaskRepository(
    private val taskRecordDao: TaskRecordDao,
) {
    fun observeRecent(limit: Int = 50): Flow<List<TaskRecord>> =
        taskRecordDao.observeRecent(limit)

    suspend fun insert(record: TaskRecord): Long = taskRecordDao.insert(record)

    suspend fun countSuccessfulCommentsToday(
        accountId: Long,
        now: Long = System.currentTimeMillis(),
    ): Int = taskRecordDao.countByAccountSince(
        accountId = accountId,
        taskType = TaskRecordType.COMMENT,
        status = TaskStatus.SUCCESS,
        dayStartMillis = localDayStartMillis(now),
    )

    suspend fun deleteByAccount(accountId: Long) = taskRecordDao.deleteByAccount(accountId)

    private fun localDayStartMillis(now: Long): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}
