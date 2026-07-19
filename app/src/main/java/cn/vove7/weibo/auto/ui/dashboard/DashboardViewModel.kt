package cn.vove7.weibo.auto.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.vove7.weibo.auto.WeiboApp
import cn.vove7.weibo.auto.BuildConfig
import cn.vove7.weibo.auto.data.entity.CommentTemplate
import cn.vove7.weibo.auto.data.entity.PostTemplate
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import cn.vove7.weibo.auto.data.repo.AccountRepository
import cn.vove7.weibo.auto.data.repo.AutomationSettings
import cn.vove7.weibo.auto.data.repo.AutomationSettingsRepository
import cn.vove7.weibo.auto.data.repo.CommentTemplateRepository
import cn.vove7.weibo.auto.data.repo.PostTemplateRepository
import cn.vove7.weibo.auto.data.repo.TaskExecutionLogRepository
import cn.vove7.weibo.auto.data.update.UpdateChecker
import cn.vove7.weibo.auto.domain.model.TaskType
import cn.vove7.weibo.auto.domain.task.TaskRunner
import cn.vove7.weibo.auto.domain.weibo.WeiboAppController
import cn.vove7.weibo.auto.domain.weibo.WeiboNavigator
import cn.vove7.weibo.auto.overlay.TaskControlHub
import cn.vove7.weibo.auto.util.AccessibilityHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

data class DashboardUiState(
    /** 系统设置是否已授权 */
    val accessibilityGranted: Boolean = false,
    /** 服务是否已连接到当前进程 */
    val accessibilityConnected: Boolean = false,
    val accessibilitySummary: String = "检测中…",
    /** 能否真正执行自动化 */
    val accessibilityReady: Boolean = false,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val showTaskDialog: Boolean = false,
    val showPostTemplateDialog: Boolean = false,
    val showCommentTemplateDialog: Boolean = false,
    val showAutomationSettingsDialog: Boolean = false,
    val showTaskExecutionLogsDialog: Boolean = false,
)

class DashboardViewModel(
    application: Application,
    private val accountRepository: AccountRepository,
    private val taskRunner: TaskRunner,
    private val postTemplateRepository: PostTemplateRepository,
    private val commentTemplateRepository: CommentTemplateRepository,
    private val automationSettingsRepository: AutomationSettingsRepository,
    private val taskExecutionLogRepository: TaskExecutionLogRepository,
) : AndroidViewModel(application) {

    val accounts: StateFlow<List<WeiboAccount>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val postTemplates: StateFlow<List<PostTemplate>> =
        postTemplateRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val commentTemplates: StateFlow<List<CommentTemplate>> =
        commentTemplateRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val automationSettings: StateFlow<AutomationSettings> = automationSettingsRepository.settings

    val taskExecutionLogs = taskExecutionLogRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var reconnectPollJob: Job? = null
    private var busyJob: Job? = null
    private val overlay get() = (getApplication() as WeiboApp).taskOverlay

    init {
        refreshAccessibilityState()
        viewModelScope.launch {
            runCatching { postTemplateRepository.ensureDefault() }
            runCatching { commentTemplateRepository.ensureDefault() }
        }
        // 悬浮窗：停止任务 / dump / 清日志
        viewModelScope.launch {
            TaskControlHub.commands.collect { cmd ->
                when (cmd) {
                    TaskControlHub.Command.StopTask -> stopBusyTask()
                    TaskControlHub.Command.DumpNodes -> dumpAccessibilityTree()
                    TaskControlHub.Command.ClearLogs -> { /* 已在 Hub 内清空 */ }
                    TaskControlHub.Command.ShowOverlay -> overlay.show()
                    TaskControlHub.Command.HideOverlay -> overlay.hide()
                }
            }
        }
    }

    fun openTaskOverlay() {
        if (!overlay.canDrawOverlays()) {
            viewModelScope.launch {
                _events.emit("请先授予「显示在其他应用上层」权限")
            }
            overlay.openOverlayPermissionSettings()
            return
        }
        overlay.show()
    }

    fun stopBusyTask() {
        TaskControlHub.appendLog("正在停止任务…")
        busyJob?.cancel(CancellationException("user_stop"))
        busyJob = null
        TaskControlHub.setTaskRunning(false)
        _uiState.update { it.copy(isBusy = false, busyMessage = null) }
        viewModelScope.launch {
            _events.emit("任务已停止")
            runCatching {
                WeiboAppController.finishAndReturn(getApplication()) {
                    TaskControlHub.appendLog(it)
                }
            }
        }
    }

    private fun dumpAccessibilityTree() {
        viewModelScope.launch {
            try {
                TaskControlHub.appendLog("开始 dump 节点…")
                WeiboNavigator().dumpLayout("overlay_manual_dump")
                TaskControlHub.appendLog("dump 完成，见 logcat WeiboDump")
                _events.emit("节点已 dump 到 logcat (WeiboDump)")
            } catch (e: Exception) {
                TaskControlHub.appendLog("dump 失败: ${e.message}")
                _events.emit("dump 失败: ${e.message}")
            }
        }
    }

    fun refreshAccessibilityState() {
        val status = AccessibilityHelper.status(getApplication())
        _uiState.update {
            it.copy(
                accessibilityGranted = status.grantedInSettings,
                accessibilityConnected = status.serviceConnected,
                accessibilitySummary = status.summary,
                accessibilityReady = status.canOperate,
            )
        }
        // 系统已授权但服务未连上时，短时轮询等待系统自动 bind
        if (status.grantedInSettings && !status.serviceConnected) {
            startReconnectPolling()
        } else {
            reconnectPollJob?.cancel()
            reconnectPollJob = null
        }
    }

    private fun startReconnectPolling() {
        if (reconnectPollJob?.isActive == true) return
        reconnectPollJob = viewModelScope.launch {
            repeat(20) {
                delay(500)
                if (!isActive) return@launch
                val status = AccessibilityHelper.status(getApplication())
                _uiState.update {
                    it.copy(
                        accessibilityGranted = status.grantedInSettings,
                        accessibilityConnected = status.serviceConnected,
                        accessibilitySummary = status.summary,
                        accessibilityReady = status.canOperate,
                    )
                }
                if (status.canOperate || !status.grantedInSettings) return@launch
            }
        }
    }

    fun openAccessibilitySettings() {
        AccessibilityHelper.jumpToSettings(getApplication())
    }

    fun refreshAccounts() {
        refreshAccessibilityState()
        val state = _uiState.value
        when {
            !state.accessibilityGranted -> {
                viewModelScope.launch { _events.emit("请先开启无障碍服务") }
                return
            }
            !state.accessibilityConnected -> {
                viewModelScope.launch {
                    _events.emit("系统已授权，但服务尚未连接。请等待几秒点「刷新状态」；仍不行则在设置中关掉再打开开关")
                }
                return
            }
        }
        runBusy("准备刷新账号…") {
            accountRepository.refreshAccounts { progress ->
                _uiState.update { s -> s.copy(busyMessage = progress) }
            }
            _events.emit("账号刷新完成")
        }
    }

    fun checkSuperLike() {
        refreshAccessibilityState()
        val state = _uiState.value
        when {
            !state.accessibilityGranted -> {
                viewModelScope.launch { _events.emit("请先开启无障碍服务") }
                return
            }
            !state.accessibilityConnected -> {
                viewModelScope.launch {
                    _events.emit("服务未连接，请稍候点「刷新状态」或重新开关无障碍")
                }
                return
            }
        }
        if (accounts.value.isEmpty()) {
            viewModelScope.launch { _events.emit("请先点「刷新」获取账号") }
            return
        }
        runBusy("准备检测超LIKE…") {
            val results = accountRepository.checkSuperLikeStatus(
                onlySelected = true,
                onProgress = { progress ->
                    _uiState.update { s -> s.copy(busyMessage = progress) }
                },
            )
            val litCount = results.count { it.lit }
            val okCount = results.count { it.exp != null }
            val failCount = results.size - okCount
            _events.emit(
                "超LIKE 检测完成：成功 $okCount/${results.size}，" +
                    "已点亮 $litCount，未达标 ${okCount - litCount}" +
                    if (failCount > 0) "，失败 $failCount" else ""
            )
        }
    }

    /**
     * 测试按钮：仅 Intent 跳转超LIKE 页，不做重试/页面校验/切号。
     */
    fun testOpenSuperLikePage() {
        viewModelScope.launch {
            try {
                WeiboNavigator().openSuperLikePage(getApplication())
                _events.emit("已打开超LIKE 页（测试）")
            } catch (e: Exception) {
                Timber.e(e, "testOpenSuperLikePage failed")
                _events.emit("打开失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 测试按钮：模拟点击进入超话 → 赵今麦 → 我的头衔 → 超LIKE，并读经验值。
     * 路径：首页 → 我 → 超话社区 → 全部关注 → 赵今麦 → 展开更多 → 我的头衔 → 超LIKE
     */
    fun testOpenSuperTopicPage() {
        refreshAccessibilityState()
        val state = _uiState.value
        when {
            !state.accessibilityGranted -> {
                viewModelScope.launch { _events.emit("请先开启无障碍服务") }
                return
            }
            !state.accessibilityConnected -> {
                viewModelScope.launch {
                    _events.emit("服务未连接，请稍候点「刷新状态」或重新开关无障碍")
                }
                return
            }
        }
        runBusy("测试：模拟点击进入超话…") {
            val exp = WeiboNavigator().testNavigateSuperTopicToExp(
                context = getApplication(),
            ) { progress ->
                _uiState.update { s -> s.copy(busyMessage = progress) }
            }
            if (exp != null) {
                _events.emit("超话路径成功，经验值 $exp")
            } else {
                _events.emit("已走完超话路径，但未读到经验值（见 logcat WeiboNav）")
            }
            runCatching {
                WeiboAppController.finishAndReturn(getApplication()) { p ->
                    _uiState.update { s -> s.copy(busyMessage = p) }
                }
            }
        }
    }

    fun toggleSelect(account: WeiboAccount) {
        viewModelScope.launch {
            accountRepository.setSelected(account.id, !account.selected)
        }
    }

    fun toggleAllAccounts() {
        val current = accounts.value
        if (current.isEmpty()) return
        val selectAll = current.any { !it.selected }
        viewModelScope.launch {
            accountRepository.setAllSelected(selectAll)
        }
    }

    fun deleteAccount(account: WeiboAccount) {
        viewModelScope.launch {
            accountRepository.deleteAccount(account.id)
            _events.emit("已删除 ${account.name}")
        }
    }

    fun onStartClicked() {
        refreshAccessibilityState()
        val selected = accounts.value.filter { it.selected }
        val state = _uiState.value
        when {
            !state.accessibilityGranted -> {
                viewModelScope.launch { _events.emit("请先开启无障碍服务") }
            }
            !state.accessibilityConnected -> {
                viewModelScope.launch {
                    _events.emit("服务未连接，请稍候点「刷新状态」或重新开关无障碍")
                }
            }
            selected.isEmpty() -> {
                viewModelScope.launch { _events.emit("请先勾选要执行的账号") }
            }
            state.isBusy -> {
                viewModelScope.launch { _events.emit("有任务正在执行") }
            }
            else -> _uiState.update { it.copy(showTaskDialog = true) }
        }
    }

    fun dismissTaskDialog() {
        _uiState.update { it.copy(showTaskDialog = false) }
    }

    fun openPostTemplateDialog() {
        _uiState.update { it.copy(showPostTemplateDialog = true) }
    }

    fun dismissPostTemplateDialog() {
        _uiState.update { it.copy(showPostTemplateDialog = false) }
    }

    fun openCommentTemplateDialog() {
        _uiState.update { it.copy(showCommentTemplateDialog = true) }
    }

    fun dismissCommentTemplateDialog() {
        _uiState.update { it.copy(showCommentTemplateDialog = false) }
    }

    fun openAutomationSettingsDialog() {
        _uiState.update { it.copy(showAutomationSettingsDialog = true) }
    }

    fun dismissAutomationSettingsDialog() {
        _uiState.update { it.copy(showAutomationSettingsDialog = false) }
    }

    fun openTaskExecutionLogsDialog() {
        _uiState.update { it.copy(showTaskExecutionLogsDialog = true) }
    }

    fun dismissTaskExecutionLogsDialog() {
        _uiState.update { it.copy(showTaskExecutionLogsDialog = false) }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _events.emit("正在检查更新…")
            try {
                val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
                _events.emit(
                    if (result.updateAvailable) {
                        "发现新版本 v${result.latestVersion}，请到项目 Releases 页面更新"
                    } else {
                        "当前已是最新版本 v${BuildConfig.VERSION_NAME}"
                    }
                )
            } catch (e: Exception) {
                _events.emit("检查更新失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun updateAutomationSettings(settings: AutomationSettings) {
        try {
            automationSettingsRepository.update(settings)
            _uiState.update { it.copy(showAutomationSettingsDialog = false) }
            viewModelScope.launch { _events.emit("参数配置已保存") }
        } catch (e: IllegalArgumentException) {
            viewModelScope.launch { _events.emit("参数范围无效，请检查输入") }
        }
    }

    fun addPostTemplate(content: String) {
        viewModelScope.launch {
            try {
                postTemplateRepository.add(content)
                _events.emit("已添加发帖模板")
            } catch (e: Exception) {
                _events.emit("添加失败: ${e.message}")
            }
        }
    }

    fun selectPostTemplate(id: Long) {
        viewModelScope.launch {
            postTemplateRepository.select(id)
        }
    }

    fun deletePostTemplate(id: Long) {
        viewModelScope.launch {
            postTemplateRepository.delete(id)
            _events.emit("已删除模板")
        }
    }

    fun addCommentTemplate(content: String) {
        viewModelScope.launch {
            try {
                commentTemplateRepository.add(content)
                _events.emit("已添加评论模板")
            } catch (e: Exception) {
                _events.emit("添加失败: ${e.message}")
            }
        }
    }

    fun selectCommentTemplate(id: Long) {
        viewModelScope.launch {
            commentTemplateRepository.select(id)
        }
    }

    fun deleteCommentTemplate(id: Long) {
        viewModelScope.launch {
            commentTemplateRepository.delete(id)
            _events.emit("已删除评论模板")
        }
    }

    fun confirmTasks(tasks: List<TaskType>) {
        _uiState.update { it.copy(showTaskDialog = false) }
        if (tasks.isEmpty()) {
            viewModelScope.launch { _events.emit("请至少选择一个任务") }
            return
        }
        val selected = accounts.value.filter { it.selected }
        // 含发帖时预检模板
        if (TaskType.POST in tasks) {
            viewModelScope.launch {
                val content = postTemplateRepository.getRandomContent()
                if (content.isNullOrBlank()) {
                    _events.emit("请先在「发帖模板」中添加内容")
                    return@launch
                }
                runBusy("任务执行中…") {
                    taskRunner.run(selected, tasks) { progress ->
                        _uiState.update { state -> state.copy(busyMessage = progress) }
                        TaskControlHub.appendLog(progress)
                        if (TaskControlHub.isStopRequested()) {
                            throw CancellationException("user_stop")
                        }
                    }
                }
            }
            return
        }
        runBusy("任务执行中…") {
            taskRunner.run(selected, tasks) { progress ->
                _uiState.update { state -> state.copy(busyMessage = progress) }
                TaskControlHub.appendLog(progress)
                if (TaskControlHub.isStopRequested()) {
                    throw CancellationException("user_stop")
                }
            }
        }
    }

    private fun runBusy(message: String, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) {
            viewModelScope.launch { _events.emit("请等待当前操作完成") }
            return
        }
        TaskControlHub.clearStopRequest()
        TaskControlHub.setTaskRunning(true)
        TaskControlHub.appendLog(message)
        busyJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = message) }
            try {
                block()
                if (TaskControlHub.isStopRequested()) {
                    TaskControlHub.appendLog("任务已按用户请求停止")
                    _events.emit("任务已停止")
                } else {
                    _events.emit("任务执行结束")
                }
            } catch (e: CancellationException) {
                TaskControlHub.appendLog("任务协程已取消")
                throw e
            } catch (e: Exception) {
                TimberSafe.e(e)
                TaskControlHub.appendLog("失败: ${e.message}")
                _events.emit("操作失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                TaskControlHub.setTaskRunning(false)
                busyJob = null
                _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            }
        }
    }

    companion object {
        fun factory(app: WeiboApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(
                        app,
                        app.accountRepository,
                        app.taskRunner,
                        app.postTemplateRepository,
                        app.commentTemplateRepository,
                        app.automationSettingsRepository,
                        app.taskExecutionLogRepository,
                    ) as T
                }
            }
    }
}

private object TimberSafe {
    fun e(t: Throwable) {
        try {
            timber.log.Timber.e(t)
        } catch (_: Throwable) {
            t.printStackTrace()
        }
    }
}
