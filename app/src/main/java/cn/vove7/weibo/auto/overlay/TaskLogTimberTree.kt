package cn.vove7.weibo.auto.overlay

import android.util.Log
import timber.log.Timber

/**
 * 把关键 Timber 日志同步到悬浮窗（INFO 及以上，任务相关 tag）。
 */
class TaskLogTimberTree : Timber.Tree() {
    private val allowTags = setOf(
        "WeiboNav",
        "WeiboTaskRunner",
        "SuperLikeChecker",
        "WeiboDump",
        "WeiboAppController",
        "KeepAliveService",
        "TaskOverlay",
        "Timber",
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        val tname = tag.orEmpty()
        val allowed = tname.isEmpty() ||
            allowTags.any { tname.contains(it, ignoreCase = true) } ||
            tname.contains("Weibo", ignoreCase = true) ||
            message.contains("任务") ||
            message.contains("签到") ||
            message.contains("超like", ignoreCase = true)
        if (!allowed) return
        val prefix = if (tname.isNotBlank()) "[$tname] " else ""
        val line = buildString {
            append(prefix)
            append(message)
            if (t != null) append(" | ").append(t.javaClass.simpleName)
        }
        TaskControlHub.appendLog(
            if (line.length > 220) line.take(220) + "…" else line
        )
    }
}
