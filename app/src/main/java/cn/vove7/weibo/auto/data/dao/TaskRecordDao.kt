package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.vove7.weibo.auto.data.entity.TaskRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskRecordDao {

    @Query("SELECT * FROM task_records ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<TaskRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TaskRecord): Long

    @Query(
        "SELECT COUNT(*) FROM task_records " +
            "WHERE accountId = :accountId AND taskType = :taskType " +
            "AND status = :status AND updatedAt >= :dayStartMillis"
    )
    suspend fun countByAccountSince(
        accountId: Long,
        taskType: String,
        status: String,
        dayStartMillis: Long,
    ): Int

    @Query("DELETE FROM task_records WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
}
