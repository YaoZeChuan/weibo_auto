package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.vove7.weibo.auto.data.repo.AutomationSettings
import cn.vove7.weibo.auto.data.repo.AutomationSettingsRepository

@Composable
fun AutomationSettingsDialog(
    current: AutomationSettings,
    onDismiss: () -> Unit,
    onConfirm: (AutomationSettings) -> Unit,
) {
    var commentLimit by remember(current) { mutableStateOf(current.dailyCommentLimit.toString()) }
    var staySeconds by remember(current) { mutableStateOf(current.browseStaySeconds.toString()) }
    var swipeCount by remember(current) { mutableStateOf(current.browseSwipeCount.toString()) }
    val settings = AutomationSettings(
        dailyCommentLimit = commentLimit.toIntOrNull() ?: -1,
        browseStaySeconds = staySeconds.toIntOrNull() ?: -1,
        browseSwipeCount = swipeCount.toIntOrNull() ?: -1,
    )
    val valid = settings.dailyCommentLimit in
        AutomationSettingsRepository.MIN_DAILY_COMMENT_LIMIT..AutomationSettingsRepository.MAX_DAILY_COMMENT_LIMIT &&
        settings.browseStaySeconds in
        AutomationSettingsRepository.MIN_BROWSE_STAY_SECONDS..AutomationSettingsRepository.MAX_BROWSE_STAY_SECONDS &&
        settings.browseSwipeCount in
        AutomationSettingsRepository.MIN_BROWSE_SWIPE_COUNT..AutomationSettingsRepository.MAX_BROWSE_SWIPE_COUNT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参数配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "评论按账号和本地自然日分别统计。模板会在每次任务中随机抽取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField(
                    value = commentLimit,
                    onValueChange = { commentLimit = it },
                    label = "每日评论上限（0-99）",
                )
                NumberField(
                    value = staySeconds,
                    onValueChange = { staySeconds = it },
                    label = "每次滑动后停留秒数（1-30）",
                )
                NumberField(
                    value = swipeCount,
                    onValueChange = { swipeCount = it },
                    label = "浏览滑动次数（1-200）",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(settings) }, enabled = valid) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all(Char::isDigit)) onValueChange(input) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
