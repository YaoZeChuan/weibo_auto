package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountCard(
    account: WeiboAccount,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
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
            Avatar(name = account.name, url = account.avatarUrl)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusTag(
                        text = when {
                            account.superLikeExp < 0 -> "超like · —"
                            account.superLikeLit -> "已点亮 · ${account.superLikeExp}"
                            else -> "未达标 · ${account.superLikeExp}"
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
                    // 当天是否做过检测
                    StatusTag(
                        text = if (account.isCheckedToday()) "今日已检测" else "今日未检测",
                        bg = if (account.isCheckedToday()) LitBg else FailBg,
                        fg = if (account.isCheckedToday()) LitFg else FailFg,
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
private fun Avatar(name: String, url: String?) {
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
}
