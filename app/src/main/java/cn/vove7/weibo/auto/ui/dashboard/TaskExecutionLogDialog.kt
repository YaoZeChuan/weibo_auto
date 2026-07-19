package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TaskExecutionLogDialog(logs: List<TaskExecutionLog>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务日志") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无任务日志")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(logs, key = { it.id }) { log ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${formatLogTime(log.startedAt)} · ${log.result}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("账号：${log.accountsSummary}")
                            Text("任务：${log.tasksSummary}")
                            Text("完成：${log.completedAt?.let(::formatLogTime) ?: "进行中"}")
                            log.detail?.let { Text("结果：$it") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
