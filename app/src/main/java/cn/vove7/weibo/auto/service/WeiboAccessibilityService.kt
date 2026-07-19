package cn.vove7.weibo.auto.service

import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.auto.core.AppPageInfo
import timber.log.Timber

/**
 * 微博任务用无障碍服务，接入 Android-Auto-Api。
 * 具体页面解析与操作逻辑后续在 TaskRunner 中实现。
 */
class WeiboAccessibilityService : AccessibilityApi() {

    override val enableListenPageUpdate: Boolean = true

    override fun onCreate() {
        // 尽早挂上，避免 onServiceConnected 前 UI 误判为未连接
        baseService = this
        super.onCreate()
        Timber.i("WeiboAccessibilityService onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        baseService = this
        Timber.i("WeiboAccessibilityService onServiceConnected")
    }

    override fun onDestroy() {
        Timber.i("WeiboAccessibilityService onDestroy")
        baseService = null
        super.onDestroy()
    }

    override fun onPageUpdate(currentScope: AppPageInfo) {
        Timber.d("onPageUpdate: $currentScope")
    }
}
