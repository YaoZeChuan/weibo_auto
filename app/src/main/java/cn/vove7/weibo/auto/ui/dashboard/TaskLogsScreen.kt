package cn.vove7.weibo.auto.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import cn.vove7.weibo.auto.data.entity.TaskExecutionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TaskLogsScreen(
    logs: List<TaskExecutionLog>,
    selectedLogId: Long?,
    onBack: () -> Unit,
    onLogClick: (Long) -> Unit,
) {
    val selectedLog = logs.firstOrNull { it.id == selectedLogId }
    BackHandler(onBack = onBack)
    if (selectedLog != null) {
        TaskLogDetailScreen(log = selectedLog, onBack = onBack)
    } else {
        TaskLogListScreen(logs = logs, onBack = onBack, onLogClick = onLogClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskLogListScreen(
    logs: List<TaskExecutionLog>,
    onBack: () -> Unit,
    onLogClick: (Long) -> Unit,
) {
    Scaffold(
        topBar = { LogTopBar(title = "任务日志", onBack = onBack) },
    ) { padding ->
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无任务日志", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "启动任务后会在这里保留执行记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(logs, key = { it.id }) { log ->
                    TaskLogCard(log = log, onClick = { onLogClick(log.id) })
                }
            }
        }
    }
}

@Composable
private fun TaskLogCard(log: TaskExecutionLog, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        log.completedAt?.let(::formatLogTime) ?: "进行中",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(8.dp))
                    ResultChip(log.result)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "账号：${log.accountsSummary}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "任务：${log.tasksSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskLogDetailScreen(log: TaskExecutionLog, onBack: () -> Unit) {
    Scaffold(topBar = { LogTopBar(title = "日志详情", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("执行结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            ResultChip(log.result)
                        }
                        Spacer(Modifier.height(18.dp))
                        LogDetailRow("启动时间", formatLogTime(log.startedAt))
                        LogDetailRow("完成时间", log.completedAt?.let(::formatLogTime) ?: "进行中")
                        LogDetailRow("执行账号", log.accountsSummary)
                        LogDetailRow("任务内容", log.tasksSummary)
                        LogDetailRow("结果说明", log.detail ?: resultLabel(log.result))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
private fun ResultChip(result: String) {
    val color = when (result) {
        TaskExecutionResult.SUCCESS -> Color(0xFF2F7D52)
        TaskExecutionResult.PARTIAL -> Color(0xFFB36B00)
        TaskExecutionResult.FAILED -> MaterialTheme.colorScheme.error
        TaskExecutionResult.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    AssistChip(
        onClick = {},
        label = { Text(resultLabel(result)) },
        enabled = false,
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = color.copy(alpha = 0.12f),
            disabledLabelColor = color,
        ),
    )
}

@Composable
private fun LogDetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun resultLabel(result: String): String = when (result) {
    TaskExecutionResult.SUCCESS -> "已完成"
    TaskExecutionResult.PARTIAL -> "部分完成"
    TaskExecutionResult.FAILED -> "执行失败"
    TaskExecutionResult.CANCELLED -> "已取消"
    else -> "执行中"
}

private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
