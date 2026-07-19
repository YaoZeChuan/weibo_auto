package cn.vove7.weibo.auto.domain.weibo

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.auto.core.api.back
import cn.vove7.auto.core.api.withDesc
import cn.vove7.weibo.auto.MainActivity
import cn.vove7.weibo.auto.service.KeepAliveService
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * 微博相关收尾动作。
 * 任务结束后优先把本助手拉回前台（不强杀微博）。
 */
object WeiboAppController {

    /**
     * 任务收尾：尽快回到本助手 App。
     * 不再长时间在微博里 back（容易超时且仍停在微博），直接多通道 start MainActivity。
     */
    suspend fun finishAndReturn(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        onProgress("返回助手…")
        // 1) 直接拉起（最重要）
        bringSelfToFront(context)
        delay(500)

        // 2) 若仍不在本 App，先 Home 再拉起（减少微博盖在上面）
        if (!isSelfInForeground(context)) {
            Timber.w("finishAndReturn: still not self, HOME then relaunch")
            runCatching {
                AccessibilityApi.baseService?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME
                )
            }
            delay(450)
            bringSelfToFront(context)
            delay(500)
        }

        // 3) 再兜底一次
        if (!isSelfInForeground(context)) {
            Timber.w("finishAndReturn: third try via KeepAliveService")
            runCatching {
                val i = Intent(context, KeepAliveService::class.java).apply {
                    action = KeepAliveService.ACTION_BRING_MAIN_TO_FRONT
                }
                context.startService(i)
            }.onFailure { Timber.w(it, "KeepAliveService start failed") }
            delay(600)
            bringSelfToFront(context)
        }

        Timber.i(
            "finishAndReturn done selfFg=${isSelfInForeground(context)} " +
                "pkg=${currentPackage()}"
        )
    }

    /** 兼容旧调用名 */
    suspend fun closeWeibo(context: Context, onProgress: (String) -> Unit = {}) {
        finishAndReturn(context, onProgress)
    }

    private fun isSelfInForeground(context: Context): Boolean {
        val self = context.packageName
        return currentPackage() == self
    }

    private fun currentPackage(): String? =
        cn.vove7.auto.core.AutoApi.currentPageInfo?.packageName

    /**
     * 多 Context + 显式 MainActivity + ActivityOptions，对抗 MIUI BAL。
     */
    fun bringSelfToFront(context: Context) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val starters = buildList {
            AccessibilityApi.baseService?.let { add("a11y" to it) }
            add("app" to appCtx)
            // 前台服务 Context 有时比 Application 更容易过 BAL
            runCatching {
                // 通过再启一次 FGS 拿 service 上下文不现实；用 appCtx 即可
            }
        }

        var anyOk = false
        for ((tag, ctx) in starters) {
            try {
                startActivityCompat(ctx, intent)
                Timber.i("bringSelfToFront ok via=$tag")
                anyOk = true
                break
            } catch (e: Throwable) {
                Timber.w(e, "bringSelfToFront failed via=$tag")
            }
        }
        if (!anyOk) {
            // 最后：系统 launch intent
            runCatching {
                val launch = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivityCompat(AccessibilityApi.baseService ?: appCtx, launch)
                    Timber.i("bringSelfToFront ok via launchIntent")
                }
            }.onFailure { Timber.e(it, "bringSelfToFront all failed") }
        }
    }

    private fun startActivityCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            // startActivity 也可用 Bundle options（API 16+）
            context.startActivity(intent, opts.toBundle())
        } else {
            context.startActivity(intent)
        }
    }

    /**
     * 可选：短时回微博主壳（不再长时间 back）。
     */
    private suspend fun goWeiboHomeQuick() {
        val service = AccessibilityApi.baseService
        val ctx = service ?: AccessibilityApi.appCtx
        val launch = ctx.packageManager.getLaunchIntentForPackage(WeiboConsts.PACKAGE) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { ctx.startActivity(launch) }
        delay(500)
    }

    private suspend fun isLikelyWeiboMainByDesc(): Boolean {
        val screenH = screenHeight()
        val bottomThreshold = (screenH * 0.72f).toInt()
        val me = withDesc("我").findAll().any { it.bounds.centerY() >= bottomThreshold }
        val home = withDesc("首页").findAll().any { it.bounds.centerY() >= bottomThreshold }
        return me && home
    }

    private fun screenHeight(): Int =
        AccessibilityApi.requireBase.resources.displayMetrics.heightPixels
}
