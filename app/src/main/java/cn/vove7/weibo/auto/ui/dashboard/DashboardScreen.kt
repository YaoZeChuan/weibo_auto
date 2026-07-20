package cn.vove7.weibo.auto.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vove7.weibo.auto.ui.components.AccessibilityStatusBar
import cn.vove7.weibo.auto.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val postTemplates by viewModel.postTemplates.collectAsStateWithLifecycle()
    val commentTemplates by viewModel.commentTemplates.collectAsStateWithLifecycle()
    val automationSettings by viewModel.automationSettings.collectAsStateWithLifecycle()
    val taskExecutionLogs by viewModel.taskExecutionLogs.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (uiState.showTemplateManagementPage) {
        TemplateManagementScreen(
            postTemplates = postTemplates,
            commentTemplates = commentTemplates,
            onBack = viewModel::closeTemplateManagementPage,
            onAddPostTemplate = viewModel::addPostTemplate,
            onAddCommentTemplate = viewModel::addCommentTemplate,
            onDeletePostTemplate = viewModel::deletePostTemplate,
            onDeleteCommentTemplate = viewModel::deleteCommentTemplate,
            isUpdatingTemplateTexts = uiState.isUpdatingTemplateTexts,
            onUpdateTemplateTexts = viewModel::updateTemplateTexts,
            snackbarHostState = snackbarHostState,
        )
        return
    }

    if (uiState.showTaskExecutionLogsPage) {
        TaskLogsScreen(
            logs = taskExecutionLogs,
            selectedLogId = uiState.selectedTaskExecutionLogId,
            onBack = viewModel::closeTaskExecutionLogsPage,
            onLogClick = viewModel::openTaskExecutionLogDetail,
        )
        return
    }

    if (uiState.showTaskDialog) {
        TaskSelectDialog(
            onDismiss = viewModel::dismissTaskDialog,
            onConfirm = viewModel::confirmTasks,
        )
    }
    if (uiState.showAutomationSettingsDialog) {
        AutomationSettingsDialog(
            current = automationSettings,
            onDismiss = viewModel::dismissAutomationSettingsDialog,
            onConfirm = viewModel::updateAutomationSettings,
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "小麦助手 v${BuildConfig.VERSION_NAME}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                },
                actions = {
                    TextButton(onClick = viewModel::openTaskExecutionLogsPage) {
                        Text("日志", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = viewModel::checkForUpdate) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "检查更新",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    IconButton(onClick = viewModel::openTaskOverlay) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "任务悬浮窗",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                .padding(horizontal = 12.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                uiState.availableUpdate?.let { update ->
                    NewVersionCard(
                        update = update,
                        onUpdate = viewModel::startAvailableUpdate,
                        onIgnore = viewModel::ignoreAvailableUpdate,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                uiState.updateDownload?.let { update ->
                    UpdateDownloadProgressCard(update)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                AccessibilityStatusBar(
                    granted = uiState.accessibilityGranted,
                    connected = uiState.accessibilityConnected,
                    summary = uiState.accessibilitySummary,
                    onOpenSettings = viewModel::openAccessibilitySettings,
                    onRefreshStatus = viewModel::refreshAccessibilityState,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionRow(
                    enabled = !uiState.isBusy,
                    onRefresh = viewModel::refreshAccounts,
                    onSuperLike = viewModel::checkSuperLike,
                    onStart = viewModel::onStartClicked,
                    onTemplateManagement = viewModel::openTemplateManagementPage,
                    onAutomationSettings = viewModel::openAutomationSettingsDialog,
                )
                Spacer(modifier = Modifier.height(10.dp))
                val uncheckedToday = accounts.count { !it.isCheckedToday() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "账号",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "  ${accounts.size} 个 · 已选 ${accounts.count { it.selected }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (accounts.isNotEmpty()) {
                        val allSelected = accounts.all { it.selected }
                        TextButton(
                            onClick = viewModel::toggleAllAccounts,
                            enabled = !uiState.isBusy,
                        ) {
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                    }
                }
                if (accounts.isNotEmpty() && uncheckedToday > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "有 $uncheckedToday 个账号今日尚未检测超like",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (accounts.isEmpty()) {
                    EmptyAccounts(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onRefresh = viewModel::refreshAccounts,
                        enabled = !uiState.isBusy,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(accounts, key = { it.id }) { account ->
                            AccountCard(
                                account = account,
                                onToggleSelect = { viewModel.toggleSelect(account) },
                                onDelete = { viewModel.deleteAccount(account) },
                            )
                        }
                    }
                }
            }

            if (uiState.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = uiState.busyMessage ?: "处理中…",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewVersionCard(
    update: AvailableUpdateUiState,
    onUpdate: () -> Unit,
    onIgnore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "检测到新版本 v${update.version}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "是否立即更新？",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onIgnore) { Text("忽略") }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onUpdate) { Text("更新") }
            }
        }
    }
}

@Composable
private fun UpdateDownloadProgressCard(update: UpdateDownloadUiState) {
    val progress = update.progress.coerceIn(0, 100) / 100f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "正在下载 v${update.version}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${update.progress}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun ActionRow(
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSuperLike: () -> Unit,
    onStart: () -> Unit,
    onTemplateManagement: () -> Unit,
    onAutomationSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "刷新",
                    modifier = Modifier.padding(start = 4.dp),
                    fontSize = 13.sp,
                )
            }
            FilledTonalButton(
                onClick = onSuperLike,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "超like",
                    modifier = Modifier.padding(start = 4.dp),
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "启动",
                    modifier = Modifier.padding(start = 4.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onTemplateManagement,
                enabled = enabled,
                modifier = Modifier
                    .weight(2f)
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "模板管理",
                    modifier = Modifier.padding(start = 4.dp),
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                onClick = onAutomationSettings,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "参数配置",
                    modifier = Modifier.padding(start = 4.dp),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyAccounts(
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "暂无账号",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "点击刷新获取已登录的微博账号",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onRefresh,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text("刷新账号", modifier = Modifier.padding(start = 6.dp))
        }
    }
}
