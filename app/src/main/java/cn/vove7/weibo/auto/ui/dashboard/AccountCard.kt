package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import coil.compose.AsyncImage

private val LitBg = Color(0xFFE8F5E9)
private val LitFg = Color(0xFF1B5E20)
private val FailBg = Color(0xFFFFEBEE)
private val FailFg = Color(0xFFC62828)
private val MuteBg = Color(0xFFF0F0F2)
private val MuteFg = Color(0xFF6B6B70)
private val CheckBg = Color(0xFFE8F0FE)
private val CheckFg = Color(0xFF1565C0)
private val TaskCompleteGreen = Color(0xFF34A853)
private val TaskIncompleteRed = Color(0xFFE53935)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountCard(
    account: WeiboAccount,
    waterPostTarget: Int,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDailyTaskTip by remember(account.id) { mutableStateOf(false) }
    val dailyTaskCompletion = account.dailyTaskCompletion(waterPostTarget)
    Card(
        onClick = { showDailyTaskTip = true },
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = account.selected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.size(40.dp),
            )
            Avatar(
                name = account.name,
                url = account.avatarUrl,
                incompleteTaskCount = dailyTaskCompletion.incompleteCount,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (dailyTaskCompletion.incompleteCount) {
                            0 -> "😁"
                            4 -> "😭"
                            else -> "😅"
                        },
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusTag(
                        text = when {
                            account.superLikeExp < 0 -> "超like · —"
                            else -> "超L${account.superLikeExp}分"
                        },
                        bg = when {
                            account.superLikeExp < 0 -> MuteBg
                            account.superLikeLit -> LitBg
                            else -> FailBg
                        },
                        fg = when {
                            account.superLikeExp < 0 -> MuteFg
                            account.superLikeLit -> LitFg
                            else -> FailFg
                        },
                    )
                    StatusTag(
                        text = when {
                            account.checkInDays < 0 -> "签到 · —"
                            else -> "连签 ${account.checkInDays} 天"
                        },
                        bg = if (account.checkInDays < 0) MuteBg else CheckBg,
                        fg = if (account.checkInDays < 0) MuteFg else CheckFg,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showDailyTaskTip) {
        DailyTaskProgressDialog(
            account = account,
            waterPostTarget = waterPostTarget,
            onDismiss = { showDailyTaskTip = false },
        )
    }
}

@Composable
private fun DailyTaskProgressDialog(
    account: WeiboAccount,
    waterPostTarget: Int,
    onDismiss: () -> Unit,
) {
    val detected = account.isDailyTaskDetectedToday()
    val completion = account.dailyTaskCompletion(waterPostTarget)
    val checkIn = when {
        !detected -> "未检测"
        account.dailyCheckInStatus == "COMPLETED" -> "已完成"
        account.dailyCheckInStatus == "INCOMPLETE" -> "未完成"
        else -> "未检测"
    }
    fun progress(done: Int, required: Int): String =
        if (!detected || done < 0 || required < 0) "未检测" else "$done/$required"
    val estimatedScore = if (!detected) {
        null
    } else {
        (if (account.dailyCheckInStatus == "COMPLETED") 8 else 0) +
            account.dailyBrowseCompletedCount.coerceAtLeast(0) +
            account.dailyCommentCompletedCount.coerceAtLeast(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${account.name} · 今日任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("签到：$checkIn", color = taskProgressColor(completion.checkIn))
                Text(
                    "看帖：今日完成次数 ${progress(account.dailyBrowseCompletedCount, account.dailyBrowseRequiredCount)}",
                    color = taskProgressColor(completion.browse),
                )
                Text(
                    "评论：今日完成次数 ${progress(account.dailyCommentCompletedCount, account.dailyCommentRequiredCount)}",
                    color = taskProgressColor(completion.comment),
                )
                val waterPostCount = if (account.isDailyWaterPostRecordedToday()) {
                    account.dailyWaterPostCompletedCount
                } else {
                    0
                }
                Text(
                    "水贴：今日发帖量 $waterPostCount/$waterPostTarget",
                    color = taskProgressColor(completion.waterPost),
                )
                Text("今日预计经验值：${estimatedScore?.let { "+$it" } ?: "未检测"}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun StatusTag(
    text: String,
    bg: Color,
    fg: Color,
) {
    Text(
        text = text,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Avatar(
    name: String,
    url: String?,
    incompleteTaskCount: Int,
) {
    Box(modifier = Modifier.size(44.dp)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val initial = name.firstOrNull()?.toString() ?: "?"
                if (initial.isNotBlank()) {
                    Text(
                        text = initial,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (incompleteTaskCount == 0) TaskCompleteGreen else TaskIncompleteRed)
                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (incompleteTaskCount == 0) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "今日任务已完成",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    text = incompleteTaskCount.toString(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                )
            }
        }
    }
}

private data class DailyTaskCompletion(
    val checkIn: Boolean,
    val browse: Boolean,
    val comment: Boolean,
    val waterPost: Boolean,
) {
    val incompleteCount: Int
        get() = listOf(checkIn, browse, comment, waterPost).count { !it }
}

@Composable
private fun taskProgressColor(completed: Boolean): Color =
    if (completed) MaterialTheme.colorScheme.onSurface else TaskIncompleteRed

private fun WeiboAccount.dailyTaskCompletion(waterPostTarget: Int): DailyTaskCompletion {
    val dailyTaskDetected = isDailyTaskDetectedToday()
    val checkInCompleted = dailyTaskDetected && dailyCheckInStatus == "COMPLETED"
    val browseCompleted = dailyTaskDetected &&
        dailyBrowseCompletedCount >= 0 &&
        dailyBrowseRequiredCount >= 0 &&
        dailyBrowseCompletedCount >= dailyBrowseRequiredCount
    val commentCompleted = dailyTaskDetected &&
        dailyCommentCompletedCount >= 0 &&
        dailyCommentRequiredCount >= 0 &&
        dailyCommentCompletedCount >= dailyCommentRequiredCount
    val waterPostCount = if (isDailyWaterPostRecordedToday()) {
        dailyWaterPostCompletedCount
    } else {
        0
    }
    val waterPostCompleted = waterPostCount >= waterPostTarget
    return DailyTaskCompletion(
        checkIn = checkInCompleted,
        browse = browseCompleted,
        comment = commentCompleted,
        waterPost = waterPostCompleted,
    )
}
