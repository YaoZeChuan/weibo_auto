package cn.vove7.weibo.auto.domain.weibo

import android.content.Context
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

data class SuperLikeCheckResult(
    val accountId: Long,
    val accountName: String,
    val exp: Int?,
    val lit: Boolean,
    val message: String,
)

/**
 * 轮询账号检测超 LIKE（纯无障碍模拟点击）：
 *
 * 账号管理切号 → 我页超话列表进目标超话 → 我的头衔/超LIKE → 读近7天经验值
 * → 写库 → Intent 回微博首页 → 下一账号
 *
 * 经验值 >= [WeiboConsts.SUPER_LIKE_LIT_THRESHOLD] 视为已点亮/达标。
 */
class SuperLikeChecker(
    private val navigator: WeiboNavigator = WeiboNavigator(),
) {

    companion object {
        private const val TAG = "SuperLikeChecker"
    }

    suspend fun checkAccounts(
        context: Context,
        accounts: List<WeiboAccount>,
        onProgress: (String) -> Unit = {},
        onAccountResult: suspend (SuperLikeCheckResult) -> Unit = {},
        /** 进入超话时读到连签天数 */
        onCheckInDays: suspend (accountId: Long, days: Int) -> Unit = { _, _ -> },
    ): List<SuperLikeCheckResult> = withContext(Dispatchers.Default) {
        AccessibilityApi.requireBaseAccessibility(autoJump = false)
        if (accounts.isEmpty()) {
            onProgress("没有需要检测的账号")
            return@withContext emptyList()
        }

        var opened = false
        val results = mutableListOf<SuperLikeCheckResult>()
        val topic = WeiboConsts.TARGET_SUPER_TOPIC_NAME
        Timber.tag(TAG).i(
            "checkAccounts start count=${accounts.size} topic=$topic " +
                "names=${accounts.map { it.name }}"
        )
        try {
            onProgress("检查是否在首页…")
            navigator.ensureWeiboHomeAtStart(context, onProgress)
            opened = true

            accounts.forEachIndexed { index, account ->
                val label = "[${index + 1}/${accounts.size}] ${account.name}"
                Timber.tag(TAG).i("---- $label begin ----")
                try {
                    onProgress("$label：进入账号管理…")
                    navigator.goToAccountManage { p -> onProgress("$label：$p") }
                    onProgress("$label：切换账号…")
                    navigator.switchToAccount(account.name)
                    onProgress("$label：等待切号完成…")
                    delay(2_000)
                    runCatching { navigator.waitWeiboReady(15_000) }

                    onProgress("$label：进入超话检测…")
                    // 进超话详情时立刻读到的连签；之后会进入头衔页，再读可能失败
                    var probedCheckInDays: Int? = null
                    val exp = navigator.navigateToSuperLikeExpViaClicks(
                        topicName = topic,
                        onProgress = { p -> onProgress("$label：$p") },
                        onCheckInDaysDetected = { days ->
                            probedCheckInDays = days
                            Timber.tag(TAG).i(
                                "checkAccounts: probe check-in days=$days for ${account.name}"
                            )
                        },
                    )
                    val checkInDays = probedCheckInDays ?: navigator.readCheckInDays()
                    if (checkInDays != null) {
                        onCheckInDays(account.id, checkInDays)
                        onProgress("$label：连签 $checkInDays 天")
                    }

                    val lit = (exp != null && exp >= WeiboConsts.SUPER_LIKE_LIT_THRESHOLD)
                    val msg = when {
                        exp == null -> "未读到经验值"
                        lit -> "超like 已点亮，经验值 $exp"
                        else -> "超like 未达标，经验值 $exp"
                    }
                    val result = SuperLikeCheckResult(
                        accountId = account.id,
                        accountName = account.name,
                        exp = exp,
                        lit = lit,
                        message = msg,
                    )
                    results += result
                    // 先回调写库，UI 通过 Flow 刷新为「未达标/已点亮 + 分数」
                    onAccountResult(result)
                    Timber.tag(TAG).i("result saved callback: $result")
                    onProgress("$label：$msg")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "check failed for ${account.name}")
                    val result = SuperLikeCheckResult(
                        accountId = account.id,
                        accountName = account.name,
                        exp = null,
                        lit = false,
                        message = "检测失败: ${e.message ?: e.javaClass.simpleName}",
                    )
                    results += result
                    onAccountResult(result)
                    onProgress("$label：${result.message}")
                    runCatching { navigator.dumpLayout("check_fail_${account.name}") }
                }

                // 下一账号前：Intent + 连续返回（禁止点内容区「首页」文字）
                if (index < accounts.lastIndex) {
                    onProgress("返回微博首页，准备下一账号…")
                    Timber.tag(TAG).i("goToWeiboHome before next account")
                    runCatching {
                        navigator.goToWeiboHome(context) { p -> onProgress(p) }
                    }.onFailure {
                        Timber.tag(TAG).w(it, "return weibo home before next failed")
                    }
                    delay(600)
                }
            }

            onProgress("超LIKE 检测完成（${results.size} 个账号）")
            Timber.tag(TAG).i("checkAccounts done results=$results")
            results
        } finally {
            if (opened) {
                runCatching {
                    WeiboAppController.finishAndReturn(context, onProgress)
                }.onFailure {
                    Timber.tag(TAG).w(it, "finishAndReturn after super like check failed")
                }
            }
        }
    }
}
