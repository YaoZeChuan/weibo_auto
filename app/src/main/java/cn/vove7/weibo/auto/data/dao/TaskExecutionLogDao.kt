package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskExecutionLogDao {

    @Query("SELECT * FROM task_execution_logs WHERE startedAt >= :since ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(since: Long, limit: Int = 100): Flow<List<TaskExecutionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: TaskExecutionLog): Long

    @Query(
        "UPDATE task_execution_logs SET completedAt = :completedAt, result = :result, " +
            "detail = :detail WHERE id = :id"
    )
    suspend fun finish(id: Long, completedAt: Long, result: String, detail: String?)

    @Query("DELETE FROM task_execution_logs WHERE startedAt < :before")
    suspend fun deleteBefore(before: Long)
}
