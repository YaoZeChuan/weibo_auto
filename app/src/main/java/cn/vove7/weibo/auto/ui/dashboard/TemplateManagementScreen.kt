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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vove7.weibo.auto.data.entity.CommentTemplate
import cn.vove7.weibo.auto.data.entity.PostTemplate

private const val COMMENT_TAB = 0
private const val POST_TAB = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateManagementScreen(
    postTemplates: List<PostTemplate>,
    commentTemplates: List<CommentTemplate>,
    onBack: () -> Unit,
    onAddPostTemplate: (String) -> Unit,
    onAddCommentTemplate: (String) -> Unit,
    onDeletePostTemplate: (Long) -> Unit,
    onDeleteCommentTemplate: (Long) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(COMMENT_TAB) }
    var showAddDialog by remember { mutableStateOf(false) }
    val isCommentTab = selectedTab == COMMENT_TAB

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模板管理", fontWeight = FontWeight.Bold) },
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
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (isCommentTab) "新增评论模板" else "新增发帖模板") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = isCommentTab,
                    onClick = { selectedTab = COMMENT_TAB },
                    text = { Text("评论模板") },
                )
                Tab(
                    selected = !isCommentTab,
                    onClick = { selectedTab = POST_TAB },
                    text = { Text("发帖模板") },
                )
            }
            Text(
                text = if (isCommentTab) {
                    "浏览任务会从评论模板中随机选择一条发送。"
                } else {
                    "发帖任务会从发帖模板中随机选择一条发送。"
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isCommentTab) {
                TemplateList(
                    contents = commentTemplates.map { it.id to it.content },
                    emptyText = "还没有评论模板",
                    onDelete = onDeleteCommentTemplate,
                )
            } else {
                TemplateList(
                    contents = postTemplates.map { it.id to it.content },
                    emptyText = "还没有发帖模板",
                    onDelete = onDeletePostTemplate,
                )
            }
        }
    }

    if (showAddDialog) {
        AddTemplateDialog(
            title = if (isCommentTab) "新增评论模板" else "新增发帖模板",
            onDismiss = { showAddDialog = false },
            onConfirm = { content ->
                if (isCommentTab) onAddCommentTemplate(content) else onAddPostTemplate(content)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun TemplateList(
    contents: List<Pair<Long, String>>,
    emptyText: String,
    onDelete: (Long) -> Unit,
) {
    if (contents.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(emptyText, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "点击右下角按钮添加内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(contents, key = { _, item -> item.first }) { index, item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.width(28.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.second,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onDelete(item.first) }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "删除模板",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTemplateDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入模板内容") },
                minLines = 4,
                maxLines = 8,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content.trim()) },
                enabled = content.isNotBlank(),
            ) { Text("保存") }
        },
    )
}
