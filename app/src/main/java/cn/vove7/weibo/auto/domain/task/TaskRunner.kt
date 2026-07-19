package cn.vove7.weibo.auto.domain.task

import android.content.Context
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.weibo.auto.data.entity.TaskRecord
import cn.vove7.weibo.auto.data.entity.TaskRecordType
import cn.vove7.weibo.auto.data.entity.TaskStatus
import cn.vove7.weibo.auto.data.entity.TaskExecutionResult
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import cn.vove7.weibo.auto.data.repo.AccountRepository
import cn.vove7.weibo.auto.data.repo.AutomationSettingsRepository
import cn.vove7.weibo.auto.data.repo.CommentTemplateRepository
import cn.vove7.weibo.auto.data.repo.PostTemplateRepository
import cn.vove7.weibo.auto.data.repo.TaskRepository
import cn.vove7.weibo.auto.data.repo.TaskExecutionLogRepository
import cn.vove7.weibo.auto.domain.model.TaskType
import cn.vove7.weibo.auto.domain.weibo.SuperLikeChecker
import cn.vove7.weibo.auto.domain.weibo.WeiboAppController
import cn.vove7.weibo.auto.domain.weibo.WeiboConsts
import cn.vove7.weibo.auto.domain.weibo.WeiboNavigator
import cn.vove7.weibo.auto.overlay.TaskControlHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 任务执行器：按账号切号，在赵今麦超话内串行执行勾选任务，
 * 账号任务全部结束后检测超 LIKE。
 */
interface TaskRunner {
    suspend fun run(
        accounts: List<WeiboAccount>,
        tasks: List<TaskType>,
        onProgress: (String) -> Unit = {},
    )
}

class WeiboTaskRunner(
    private val appContext: Context,
    private val taskRepository: TaskRepository,
    private val accountRepository: AccountRepository,
    private val postTemplateRepository: PostTemplateRepository,
    private val commentTemplateRepository: CommentTemplateRepository,
    private val automationSettingsRepository: AutomationSettingsRepository,
    private val taskExecutionLogRepository: TaskExecutionLogRepository,
    private val navigator: WeiboNavigator = WeiboNavigator(),
    private val superLikeChecker: SuperLikeChecker = SuperLikeChecker(),
) : TaskRunner {

    companion object {
        private const val TAG = "WeiboTaskRunner"
    }

    override suspend fun run(
        accounts: List<WeiboAccount>,
        tasks: List<TaskType>,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.Default) {
        if (accounts.isEmpty() || tasks.isEmpty()) {
            onProgress("未选择账号或任务")
            return@withContext
        }
        AccessibilityApi.requireBaseAccessibility(autoJump = false)

        // 执行顺序：签到 → 浏览 → 发帖；单独勾选的「检测超like」仍支持
        val ordered = orderTasks(tasks)
        val executionLogId = taskExecutionLogRepository.start(
            accountsSummary = accounts.joinToString { it.name },
            tasksSummary = ordered.joinToString { it.label },
        )
        Timber.tag(TAG).i(
            "run accounts=${accounts.map { it.name }} tasks=${ordered.map { it.name }}"
        )

        var opened = false
        var failedAccounts = 0
        var finalResult = TaskExecutionResult.SUCCESS
        var finalDetail: String? = null
        try {
            onProgress("检查是否在首页…")
            navigator.ensureWeiboHomeAtStart(appContext, onProgress)
            opened = true

            accounts.forEachIndexed { index, account ->
                ensureActive()
                if (TaskControlHub.isStopRequested()) {
                    throw CancellationException("user_stop")
                }
                val label = "[${index + 1}/${accounts.size}] ${account.name}"
                Timber.tag(TAG).i("---- $label begin ----")
                try {
                    onProgress("$label：进入账号管理切号…")
                    navigator.goToAccountManage { p -> onProgress("$label：$p") }
                    navigator.switchToAccount(account.name)
                    // switchToAccount 已等待切换结果；这里只保留较短的就绪兜底，避免无条件长暂停。
                    runCatching { navigator.waitWeiboReady(5_000) }

                    // 浏览/发帖：先进入超话；未签到会在 openTargetSuperTopic 内自动签到
                    if (ordered.any { it == TaskType.BROWSE || it == TaskType.POST }) {
                        onProgress("$label：进入超话「${WeiboConsts.TARGET_SUPER_TOPIC_NAME}」…")
                        runCatching {
                            navigator.goToWeiboHome(appContext) { p -> onProgress("$label：$p") }
                        }
                        val days = navigator.openTargetSuperTopic(
                            topicName = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
                        ) { p -> onProgress("$label：$p") }
                        if (days != null) {
                            accountRepository.updateCheckInDays(account.id, days)
                            onProgress("$label：连签 $days 天")
                        }
                    }

                    for (task in ordered) {
                        ensureActive()
                        if (TaskControlHub.isStopRequested()) {
                            throw CancellationException("user_stop")
                        }
                        if (task == TaskType.SUPER_LIKE) continue // 账号末尾统一检测
                        runOneTask(
                            account = account,
                            task = task,
                            label = label,
                            onProgress = onProgress,
                        )
                    }

                    // 每个账号任务做完后检测超 LIKE
                    onProgress("$label：检测超LIKE…")
                    runSuperLikeForAccount(account, label, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedAccounts++
                    Timber.tag(TAG).e(e, "account failed ${account.name}")
                    onProgress("$label：失败 ${e.message ?: e.javaClass.simpleName}")
                    taskRepository.insert(
                        TaskRecord(
                            accountId = account.id,
                            taskType = "ACCOUNT",
                            status = TaskStatus.FAILED,
                            message = e.message,
                        )
                    )
                }

                if (index < accounts.lastIndex) {
                    onProgress("返回微博首页，准备下一账号…")
                    runCatching {
                        navigator.goToWeiboHome(appContext) { p -> onProgress(p) }
                    }
                    delay(600)
                }
            }
            onProgress("全部任务执行完成")
            if (failedAccounts > 0) {
                finalResult = TaskExecutionResult.PARTIAL
                finalDetail = "$failedAccounts/${accounts.size} 个账号执行失败"
            } else {
                finalDetail = "${accounts.size} 个账号全部完成"
            }
        } catch (e: CancellationException) {
            finalResult = TaskExecutionResult.CANCELLED
            finalDetail = "用户停止任务"
            throw e
        } catch (e: Throwable) {
            finalResult = TaskExecutionResult.FAILED
            finalDetail = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            withContext(NonCancellable) {
                taskExecutionLogRepository.finish(executionLogId, finalResult, finalDetail)
            }
            if (opened) {
                runCatching {
                    WeiboAppController.finishAndReturn(appContext, onProgress)
                }
            }
        }
    }

    private fun orderTasks(tasks: List<TaskType>): List<TaskType> {
        val preferred = listOf(
            TaskType.BROWSE,
            TaskType.POST,
            TaskType.SUPER_LIKE,
        )
        return preferred.filter { it in tasks } + tasks.filter { it !in preferred }
    }

    private suspend fun runOneTask(
        account: WeiboAccount,
        task: TaskType,
        label: String,
        onProgress: (String) -> Unit,
    ) {
        val msg = "$label · ${task.label}"
        onProgress("运行: $msg")
        taskRepository.insert(
            TaskRecord(
                accountId = account.id,
                taskType = task.name,
                status = TaskStatus.RUNNING,
                message = "开始 $msg",
            )
        )
        try {
            when (task) {
                TaskType.BROWSE -> {
                    val hasCommentTemplate = commentTemplateRepository.getAll().isNotEmpty()
                    val existingCommentCount = taskRepository.countSuccessfulCommentsToday(account.id)
                    val settings = automationSettingsRepository.settings.value
                    navigator.browseSuperTopicPosts(
                        context = appContext,
                        topicName = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
                        maxSwipeCount = settings.browseSwipeCount,
                        stayMs = settings.browseStaySeconds * 1_000L,
                        nextCommentText = commentTemplateRepository::getRandomContent,
                        existingCommentCount = existingCommentCount,
                        maxDailyCommentCount = settings.dailyCommentLimit,
                        onCommentSent = { dailyCount ->
                            taskRepository.insert(
                                TaskRecord(
                                    accountId = account.id,
                                    taskType = TaskRecordType.COMMENT,
                                    status = TaskStatus.SUCCESS,
                                    message = "当日第 $dailyCount 条评论",
                                )
                            )
                        },
                    ) { p -> onProgress("$label：$p") }
                    taskRepository.insert(
                        TaskRecord(
                            accountId = account.id,
                            taskType = task.name,
                            status = TaskStatus.SUCCESS,
                            message = if (!hasCommentTemplate) {
                                "浏览完成（无评论模板）"
                            } else {
                                "浏览完成（含随机评论）"
                            },
                        )
                    )
                }
                TaskType.POST -> {
                    val content = postTemplateRepository.getRandomContent()
                        ?: error("没有预制发帖内容，请先在「发帖模板」中添加")
                    navigator.performPost(content) { p -> onProgress("$label：$p") }
                    taskRepository.insert(
                        TaskRecord(
                            accountId = account.id,
                            taskType = task.name,
                            status = TaskStatus.SUCCESS,
                            message = "发帖完成",
                        )
                    )
                }
                TaskType.SUPER_LIKE -> {
                    // 在账号末尾统一处理
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "task failed $msg")
            taskRepository.insert(
                TaskRecord(
                    accountId = account.id,
                    taskType = task.name,
                    status = TaskStatus.FAILED,
                    message = e.message,
                )
            )
            // 单任务失败不阻断同账号后续任务
            onProgress("$label：${task.label}失败 ${e.message}")
        }
    }

    private suspend fun runSuperLikeForAccount(
        account: WeiboAccount,
        label: String,
        onProgress: (String) -> Unit,
    ) {
        taskRepository.insert(
            TaskRecord(
                accountId = account.id,
                taskType = TaskType.SUPER_LIKE.name,
                status = TaskStatus.RUNNING,
                message = "检测超LIKE",
            )
        )
        try {
            val exp = navigator.navigateToSuperLikeExpViaClicks(
                topicName = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
            ) { p -> onProgress("$label：$p") }
            val lit = exp != null && exp >= WeiboConsts.SUPER_LIKE_LIT_THRESHOLD
            if (exp != null) {
                accountRepository.applySuperLikeResult(account.id, lit, exp)
            }
            val msg = when {
                exp == null -> "未读到经验值"
                lit -> "超like 已点亮 · $exp"
                else -> "超like 未达标 · $exp"
            }
            taskRepository.insert(
                TaskRecord(
                    accountId = account.id,
                    taskType = TaskType.SUPER_LIKE.name,
                    status = if (exp != null) TaskStatus.SUCCESS else TaskStatus.FAILED,
                    message = msg,
                )
            )
            onProgress("$label：$msg")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "super like after tasks failed")
            taskRepository.insert(
                TaskRecord(
                    accountId = account.id,
                    taskType = TaskType.SUPER_LIKE.name,
                    status = TaskStatus.FAILED,
                    message = e.message,
                )
            )
        }
    }
}
