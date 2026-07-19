package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_records")
data class TaskRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val taskType: String,
    val status: String,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

object TaskStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
}

object TaskRecordType {
    const val COMMENT = "COMMENT"
}
