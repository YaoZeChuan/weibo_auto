package cn.vove7.weibo.auto

import android.app.Application
import android.content.Intent
import android.os.Build
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.weibo.auto.data.db.AppDatabase
import cn.vove7.weibo.auto.data.repo.AccountRepository
import cn.vove7.weibo.auto.data.repo.AutomationSettingsRepository
import cn.vove7.weibo.auto.data.repo.CommentTemplateRepository
import cn.vove7.weibo.auto.data.repo.PostTemplateRepository
import cn.vove7.weibo.auto.data.repo.TaskRepository
import cn.vove7.weibo.auto.data.repo.TaskExecutionLogRepository
import cn.vove7.weibo.auto.domain.task.TaskRunner
import cn.vove7.weibo.auto.domain.task.WeiboTaskRunner
import cn.vove7.weibo.auto.domain.weibo.WeiboAccountDiscovery
import cn.vove7.weibo.auto.overlay.TaskLogTimberTree
import cn.vove7.weibo.auto.overlay.TaskOverlayController
import cn.vove7.weibo.auto.service.KeepAliveService
import cn.vove7.weibo.auto.service.WeiboAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class WeiboApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set
    lateinit var accountRepository: AccountRepository
        private set
    lateinit var taskRepository: TaskRepository
        private set
    lateinit var taskExecutionLogRepository: TaskExecutionLogRepository
        private set
    lateinit var postTemplateRepository: PostTemplateRepository
        private set
    lateinit var commentTemplateRepository: CommentTemplateRepository
        private set
    lateinit var automationSettingsRepository: AutomationSettingsRepository
        private set
    lateinit var taskRunner: TaskRunner
        private set
    lateinit var taskOverlay: TaskOverlayController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
            Timber.plant(TaskLogTimberTree())
        }
        taskOverlay = TaskOverlayController(this)

        database = AppDatabase.getInstance(this)
        accountRepository = AccountRepository(
            appContext = this,
            accountDao = database.accountDao(),
            discovery = WeiboAccountDiscovery(),
        )
        taskRepository = TaskRepository(database.taskRecordDao())
        taskExecutionLogRepository = TaskExecutionLogRepository(database.taskExecutionLogDao())
        postTemplateRepository = PostTemplateRepository(database.postTemplateDao())
        commentTemplateRepository = CommentTemplateRepository(database.commentTemplateDao())
        automationSettingsRepository = AutomationSettingsRepository(this)
        taskRunner = WeiboTaskRunner(
            appContext = this,
            taskRepository = taskRepository,
            accountRepository = accountRepository,
            postTemplateRepository = postTemplateRepository,
            commentTemplateRepository = commentTemplateRepository,
            automationSettingsRepository = automationSettingsRepository,
            taskExecutionLogRepository = taskExecutionLogRepository,
        )
        appScope.launch {
            runCatching { postTemplateRepository.ensureDefault() }
            runCatching { commentTemplateRepository.ensureDefault() }
        }

        AccessibilityApi.init(this, WeiboAccessibilityService::class.java)

        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Timber.i("WeiboApp created")
    }

    companion object {
        lateinit var instance: WeiboApp
            private set
    }
}
