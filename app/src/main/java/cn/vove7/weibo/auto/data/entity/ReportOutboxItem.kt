package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "report_outbox", indices = [Index(value = ["state"])])
data class ReportOutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val state: String = ReportOutboxState.PENDING,
    val lastError: String? = null,
    val uploadedAt: Long? = null,
)

object ReportOutboxType {
    const val HEARTBEAT = "HEARTBEAT"
    const val TASK_RUN = "TASK_RUN"
    const val ACCOUNT_SNAPSHOT = "ACCOUNT_SNAPSHOT"
}

object ReportOutboxState {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    const val FAILED = "FAILED"
}
