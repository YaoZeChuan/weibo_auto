package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_execution_logs")
data class TaskExecutionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val accountsSummary: String,
    val tasksSummary: String,
    val result: String = TaskExecutionResult.RUNNING,
    val detail: String? = null,
)

object TaskExecutionResult {
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
