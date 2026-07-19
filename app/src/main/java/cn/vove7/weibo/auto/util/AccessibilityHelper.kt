package cn.vove7.weibo.auto.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.auto.core.utils.jumpAccessibilityServiceSettings
import cn.vove7.weibo.auto.service.WeiboAccessibilityService

/**
 * 无障碍状态说明（很容易误解）：
 *
 * 1. **系统开关**（用户在设置里打开的授权）
 *    - 重装 App / 签名变化 / 强制停止 / 部分厂商省电策略 会清掉，需要重新开。
 * 2. **服务已连接**（本进程内 `AccessibilityService` 已 onServiceConnected）
 *    - 进程被杀后会短暂为 false，系统通常会自动重连，不一定要再去设置页开关。
 *
 * 旧逻辑只用 `AccessibilityApi.isServiceEnable`（= baseService != null），
 * 进程一重启就显示「未开启」，看起来像“每次都要重新授权”。
 */
object AccessibilityHelper {

    data class Status(
        /** 系统设置里是否已开启本服务 */
        val grantedInSettings: Boolean,
        /** 当前进程是否已拿到可用的 Service 实例 */
        val serviceConnected: Boolean,
    ) {
        val canOperate: Boolean get() = grantedInSettings && serviceConnected

        val summary: String
            get() = when {
                canOperate -> "已开启，可以执行任务"
                grantedInSettings && !serviceConnected ->
                    "系统已授权，服务重连中（若一直如此请下拉关掉再打开一次开关）"
                else -> "未开启，请先到系统设置中开启"
            }
    }

    fun status(context: Context): Status {
        val granted = isGrantedInSystemSettings(context) || isEnabledByAccessibilityManager(context)
        val connected = AccessibilityApi.isServiceEnable
        return Status(grantedInSettings = granted, serviceConnected = connected)
    }

    /** 是否可执行自动化（系统已开且服务已连接） */
    fun canOperate(context: Context): Boolean = status(context).canOperate

    /**
     * 兼容旧调用：以「能否操作」为准。
     * 若只想看系统开关，用 [status].grantedInSettings。
     */
    fun isServiceEnabled(context: Context): Boolean = canOperate(context)

    fun jumpToSettings(context: Context) {
        val cls = runCatching { AccessibilityApi.BASE_SERVICE_CLS }
            .getOrDefault(WeiboAccessibilityService::class.java)
        jumpAccessibilityServiceSettings(cls, context)
    }

    /**
     * 读取 Settings.Secure.enabled_accessibility_services
     * 格式类似：pkg/.Service:other/.Other
     */
    fun isGrantedInSystemSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        if (enabled.isBlank()) return returnFalse()

        val expected = ComponentName(context, WeiboAccessibilityService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component == expected ||
                (component.packageName == expected.packageName &&
                    component.className.endsWith(WeiboAccessibilityService::class.java.simpleName))
            ) {
                return true
            }
        }
        return false
    }

    private fun returnFalse(): Boolean = false

    private fun isEnabledByAccessibilityManager(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expected = ComponentName(context, WeiboAccessibilityService::class.java)
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?: return false
        return list.any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            si.packageName == expected.packageName &&
                (si.name == expected.className || si.name.endsWith(".WeiboAccessibilityService"))
        }
    }
}
