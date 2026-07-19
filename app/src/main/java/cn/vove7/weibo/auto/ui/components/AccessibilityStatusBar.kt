package cn.vove7.weibo.auto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AccessibilityStatusBar(
    granted: Boolean,
    connected: Boolean,
    summary: String,
    onOpenSettings: () -> Unit,
    onRefreshStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = granted && connected
    val reconnecting = granted && !connected

    val container = when {
        ready -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        reconnecting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
    }
    val content = when {
        ready -> MaterialTheme.colorScheme.onSecondaryContainer
        reconnecting -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when {
        ready -> Icons.Default.CheckCircle
        reconnecting -> Icons.Default.HourglassTop
        else -> Icons.Default.ErrorOutline
    }
    val title = when {
        ready -> "无障碍可用"
        reconnecting -> "无障碍连接中"
        else -> "无障碍未开启"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (summary.isNotBlank() && summary != title) "$title · $summary" else title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
            )
            when {
                !granted -> {
                    TextButton(
                        onClick = onOpenSettings,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text("去开启", fontSize = 13.sp)
                    }
                }
                else -> {
                    TextButton(
                        onClick = onRefreshStatus,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text("刷新", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
