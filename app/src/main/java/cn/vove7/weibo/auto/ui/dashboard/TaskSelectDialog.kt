package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import cn.vove7.weibo.auto.domain.model.TaskType

@Composable
fun TaskSelectDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<TaskType>) -> Unit,
) {
    val checked = remember {
        mutableStateMapOf<TaskType, Boolean>().apply {
            TaskType.entries.forEach { put(it, false) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要执行的任务") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("可多选。顺序：浏览 → 发帖；进超话若未签到会自动签到；结束后自动检测超like")
                val allSelected = TaskType.entries.all { checked[it] == true }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            TaskType.entries.forEach { type ->
                                checked[type] = !allSelected
                            }
                        },
                    ) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }
                TaskType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked[type] == true,
                            onCheckedChange = { checked[type] = it },
                        )
                        Text(text = type.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = checked.filterValues { it }.keys.toList()
                    onConfirm(selected)
                }
            ) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
