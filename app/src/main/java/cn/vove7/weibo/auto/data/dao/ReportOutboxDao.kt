package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.vove7.weibo.auto.data.entity.ReportOutboxItem

@Dao
interface ReportOutboxDao {
    @Insert
    suspend fun insert(item: ReportOutboxItem): Long

    @Query("SELECT * FROM report_outbox WHERE state = 'PENDING' ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int = 20): List<ReportOutboxItem>

    @Query("UPDATE report_outbox SET state = 'UPLOADED', uploadedAt = :at, lastError = NULL WHERE id = :id")
    suspend fun markUploaded(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE report_outbox SET state = 'FAILED', lastError = :error, attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE report_outbox SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordRetry(id: Long, error: String)
}
