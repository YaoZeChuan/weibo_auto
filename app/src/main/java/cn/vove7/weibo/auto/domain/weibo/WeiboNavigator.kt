package cn.vove7.weibo.auto.domain.weibo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.auto.core.AutoApi
import cn.vove7.auto.core.api.back
import cn.vove7.auto.core.api.buildLayoutInfo
import cn.vove7.auto.core.api.containsText
import cn.vove7.auto.core.api.waitForApp
import cn.vove7.auto.core.api.withDesc
import cn.vove7.auto.core.api.withId
import cn.vove7.auto.core.api.withText
import cn.vove7.auto.core.utils.ViewNodeNotFoundException
import cn.vove7.auto.core.viewfinder.ScreenTextFinder
import cn.vove7.auto.core.viewnode.ViewNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 微博页面导航公共能力（打开微博、我、设置、账号管理、切号、超话模拟点击）。
 */
class WeiboNavigator {

    data class DailyTaskCounter(
        val completedCount: Int = -1,
        val requiredCount: Int = -1,
    ) {
        val isCompleted: Boolean
            get() = requiredCount > 0 && completedCount >= requiredCount
    }

    data class DailyTaskProgress(
        val checkInStatus: String,
        val browse: DailyTaskCounter,
        val comment: DailyTaskCounter,
        val repost: DailyTaskCounter,
    ) {
        val signInCompleted: Boolean
            get() = checkInStatus == "COMPLETED"
        val browseCompleted: Boolean
            get() = browse.isCompleted
        val commentCompleted: Boolean
            get() = comment.isCompleted
        val allCompleted: Boolean
            get() = signInCompleted && browseCompleted && commentCompleted
    }

    companion object {
        private const val TAG = "WeiboNav"
    }

    fun openWeibo(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(WeiboConsts.PACKAGE)
            ?: error("未安装微博（${WeiboConsts.PACKAGE}）")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        Timber.tag(TAG).i(
            "openWeibo: start launch intent, ctx=${context.javaClass.simpleName}"
        )
        context.startActivity(launch)
    }

    /**
     * 用微博 Launch Intent 回到首页（CLEAR_TOP），适配多机型，
     * 比多次 back 更稳。Context 优先 AccessibilityService。
     */
    fun openWeiboHomeByIntent(context: Context) {
        val ctx = AccessibilityApi.baseService ?: context.applicationContext
        val launch = ctx.packageManager.getLaunchIntentForPackage(WeiboConsts.PACKAGE)
            ?: error("未安装微博（${WeiboConsts.PACKAGE}）")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        Timber.tag(TAG).i(
            "openWeiboHomeByIntent: ctx=${ctx.javaClass.simpleName}"
        )
        ctx.startActivity(launch)
    }

    suspend fun waitWeiboReady(timeoutMs: Long = 20_000) {
        Timber.tag(TAG).i("waitWeiboReady: timeout=${timeoutMs}ms")
        if (!waitForApp(WeiboConsts.PACKAGE, timeoutMs)) {
            error("微博启动超时，请确认已安装并可手动打开")
        }
        delay(1_200)
        logPage("waitWeiboReady done")
    }

    /**
     * 任务启动时：若当前不在微博主壳（底部有「首页/我」），则回首页。
     * 已在主壳则跳过，避免多余 Intent。
     */
    suspend fun ensureWeiboHomeAtStart(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        Timber.tag(TAG).i(
            "ensureWeiboHomeAtStart: pkg=${currentPackage()} page=${currentPageName()}"
        )
        // 不在微博：先打开
        if (currentPackage() != WeiboConsts.PACKAGE) {
            onProgress("打开微博…")
            openWeibo(context)
            waitWeiboReady()
        }
        if (isLikelyWeiboMainShell()) {
            Timber.tag(TAG).i("ensureWeiboHomeAtStart: already on main shell")
            logPage("already_home")
            return
        }
        onProgress("当前不在首页，返回首页…")
        goToWeiboHome(context, onProgress)
        delay(500)
        logPage("ensureWeiboHomeAtStart done")
    }

    suspend fun goToAccountManage(onProgress: (String) -> Unit = {}) {
        onProgress("进入「我」…")
        Timber.tag(TAG).i("goToAccountManage: step=我")
        clickMeTab()
        delay(1_200)

        onProgress("打开设置…")
        Timber.tag(TAG).i("goToAccountManage: step=设置")
        clickSettings()
        delay(1_000)

        onProgress("进入账号管理…")
        Timber.tag(TAG).i("goToAccountManage: step=账号管理")
        clickAccountManage()
        delay(1_200)
        logPage("on account manage")
    }

    /** 在账号管理页点击指定昵称，完成切号 */
    suspend fun switchToAccount(accountName: String) {
        Timber.tag(TAG).i("switchToAccount: name=$accountName")
        ensureOnAccountManage()
        val node = findAccountRow(accountName)
            ?: run {
                dumpLayout("account_row_not_found_$accountName")
                throw ViewNodeNotFoundException("账号管理中找不到账号：$accountName")
            }
        Timber.tag(TAG).i("switchToAccount click -> $node")
        if (!safeClick(node)) {
            error("点击账号失败：$accountName")
        }
        delay(1_500)
        // 可能出现确认弹窗
        val confirmed = clickIfPresent(listOf("确定", "切换", "是", "继续"), timeoutMs = 2_000)
        Timber.tag(TAG).i("switchToAccount: confirmDialogClicked=$confirmed")
        delay(1_200)
        logPage("after switchToAccount $accountName")
    }

    // region 超话模拟点击路径

    /**
     * 回到微博首页。
     *
     * **不要**点内容区「首页」文字（易点进帖子）。
     * 策略：
     * 1. Launch Intent（CLEAR_TOP）拉起微博主任务
     * 2. 若仍在 Browser / CardList / 超话等深层页，连续系统返回（等同左上角返回）
     * 3. 用底部 Tab 的 **desc**（不是 text）判断是否已在主壳
     */
    suspend fun goToWeiboHome(
        context: Context? = null,
        onProgress: (String) -> Unit = {},
    ) {
        onProgress("Intent 返回微博首页…")
        Timber.tag(TAG).i("goToWeiboHome: start (intent + back, no content text click)")
        val ctx = context ?: AccessibilityApi.appCtx
        runCatching { openWeiboHomeByIntent(ctx) }
            .onFailure { Timber.tag(TAG).w(it, "openWeiboHomeByIntent failed") }
        delay(1_000)
        runCatching { waitWeiboReady(10_000) }

        if (isLikelyWeiboMainShell()) {
            logPage("goToWeiboHome intent ok")
            return
        }

        onProgress("连续返回退出深层页…")
        repeat(10) { i ->
            back()
            delay(400)
            logPage("goToWeiboHome back#$i")
            if (isLikelyWeiboMainShell()) {
                Timber.tag(TAG).i("goToWeiboHome: ok after back#$i")
                return
            }
            // 若已不在微博，重新 Intent 拉起
            if (currentPackage() != WeiboConsts.PACKAGE) {
                Timber.tag(TAG).w("goToWeiboHome: left weibo after back, relaunch")
                runCatching { openWeiboHomeByIntent(ctx) }
                delay(900)
            }
        }

        // 再发一次 Intent，不抛错（后续 goToAccountManage 仍可从当前页导航）
        runCatching { openWeiboHomeByIntent(ctx) }
        delay(800)
        if (!isLikelyWeiboMainShell()) {
            Timber.tag(TAG).w(
                "goToWeiboHome: still not main shell pkg=${currentPackage()} page=${currentPageName()}"
            )
            dumpLayout("go_weibo_home_partial")
        } else {
            logPage("goToWeiboHome final intent ok")
        }
    }

    /**
     * 是否已在微博主壳（有底部导航）。
     * 仅用底部 **desc**（dump 里 main_radio 子项 desc=首页/我），避免匹配帖子正文。
     */
    private suspend fun isLikelyWeiboMainShell(): Boolean {
        val pkg = currentPackage()
        if (pkg != WeiboConsts.PACKAGE) return false
        val page = currentPageName().orEmpty()
        // 明确深层页
        if (page.contains("Browser", true) ||
            page.contains("Transparent", true) ||
            page.contains("NewCardList", true) ||
            page.contains("SGPage", true) ||
            page.contains("SuperGroup", true)
        ) {
            return false
        }
        val bottom = (screenHeight() * 0.72f).toInt()
        val hasMe = withDesc("我").findAll().any { it.bounds.centerY() >= bottom }
        val hasHome = withDesc("首页").findAll().any { it.bounds.centerY() >= bottom }
        Timber.tag(TAG).d(
            "isLikelyWeiboMainShell: page=$page hasMe=$hasMe hasHome=$hasHome bottom=$bottom"
        )
        return hasMe && hasHome
    }

    /**
     * 完整路径（纯模拟点击，不依赖 Intent）：
     * 首页 →「我」→「超话社区」→「全部关注」→ 目标超话
     * →「展开更多」→「我的头衔」→「超LIKE」→ 读经验值
     *
     * @return 经验值，解析失败返回 null
     */
    /**
     * 必须在非主线程执行（gesture/swipe 禁止 main thread）。
     *
     * 路径：
     * 1. 「我」页优先直接点目标超话（赵今麦常直接展示在超话社区区块）
     * 2. 否则：超话社区 → 全部关注 → 目标超话
     * 3. 展开更多 → 我的头衔 → 超LIKE → 读经验值
     *
     * 注意：微博「我」页 RecyclerView 大量子节点 a11y 为 null，文案查找常失败，
     * 必须配合坐标点击兜底。
     */
    suspend fun navigateToSuperLikeExpViaClicks(
        topicName: String = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
        onProgress: (String) -> Unit = {},
        /** 进入超话详情后读到连签天数时回调（可写库） */
        onCheckInDaysDetected: (Int) -> Unit = {},
    ): Int? = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("==== navigateToSuperLikeExpViaClicks topic=$topicName ====")
        var lastError: Throwable? = null
        // 组件未加载完时：回首页重进一次
        repeat(2) { attempt ->
            try {
                onProgress(
                    if (attempt == 0) "进入超话检测…"
                    else "组件可能未加载，回首页重进（${attempt + 1}/2）…"
                )
                if (attempt > 0) {
                    runCatching {
                        goToWeiboHome(context = null, onProgress = onProgress)
                    }
                    delay(1_000)
                } else {
                    onProgress("回到微博首页…")
                    goToWeiboHome(context = null, onProgress = onProgress)
                    delay(800)
                }

                enterMeThenOpenTopic(topicName, onProgress)
                delay(1_500)
                logPage("after enter topic $topicName attempt=${attempt + 1}")

                // 进超话：读连签；若只有「签到」则自动点签到
                runCatching {
                    val daysAlready = waitReadCheckInDays(timeoutMs = 2_500)
                    if (daysAlready != null) {
                        Timber.tag(TAG).i("navigateToSuperLike: 连签 $daysAlready")
                        onProgress("签到状态：连签 $daysAlready 天")
                        onCheckInDaysDetected(daysAlready)
                    } else if (hasSignInButtonText()) {
                        onProgress("未签到，自动签到…")
                        val days = performCheckIn { p -> onProgress(p) }
                        if (days != null) {
                            onProgress("签到成功，连签 $days 天")
                            onCheckInDaysDetected(days)
                        }
                    }
                }

                // 无签到/连签痕迹 → 可能未加载，重进
                if (!hasCheckInOrSignUi()) {
                    Timber.tag(TAG).w(
                        "navigateToSuperLike: no 签到/连签 UI, reload attempt=${attempt + 1}"
                    )
                    dumpLayout("super_topic_no_checkin_ui_$attempt")
                    if (attempt == 0) {
                        lastError = IllegalStateException("超话页未出现签到/连签，重进")
                        return@repeat
                    }
                }

                onProgress("确认超话详情页…")
                ensureOnSuperTopicDetailBeforeExpand(topicName)
                logPage("before 展开更多")

                onProgress("点击「展开查看更多」…")
                clickExpandMore()
                delay(1_000)
                logPage("after 展开更多")

                // 展开后仍无「我的头衔」→ 重进
                if (!waitForMyTitle(timeoutMs = 4_000)) {
                    Timber.tag(TAG).w(
                        "navigateToSuperLike: no 我的头衔 after expand, reload attempt=${attempt + 1}"
                    )
                    dumpLayout("no_my_title_after_expand_$attempt")
                    if (attempt == 0) {
                        lastError = IllegalStateException("展开后无「我的头衔」，重进")
                        return@repeat
                    }
                    error("展开后仍找不到「我的头衔」")
                }

                onProgress("点击「我的头衔」…")
                clickByTexts(
                    labels = listOf("我的头衔"),
                    step = "我的头衔",
                    timeoutMs = 8_000,
                    preferBottomHalf = false,
                )
                delay(1_200)
                logPage("after 我的头衔")

                onProgress("点击「超LIKE」…")
                clickSuperLikeLabel()
                delay(800)
                logPage("after 超LIKE")

                onProgress("读取经验值…")
                val exp = readExpValue()
                Timber.tag(TAG).i(
                    "navigateToSuperLikeExpViaClicks: exp=$exp topic=$topicName attempt=${attempt + 1}"
                )
                dismissDialogIfAny()
                return@withContext exp
            } catch (e: Throwable) {
                lastError = e
                Timber.tag(TAG).w(e, "navigateToSuperLike attempt=${attempt + 1} failed")
                if (attempt == 0) {
                    onProgress("进入失败，回首页重试…")
                }
            }
        }
        throw lastError ?: IllegalStateException("进入超话检测失败")
    }

    /**
     * 点「我」→ 下划约半屏露出超话社区 → 点话题进入。
     */
    private suspend fun enterMeThenOpenTopic(
        topicName: String,
        onProgress: (String) -> Unit,
    ) {
        onProgress("进入「我」…")
        Timber.tag(TAG).i("enterMeThenOpenTopic: click 我")
        clickMeTab()
        waitUntilMePageReady(timeoutMs = 8_000)
        logPage("after click 我")
        // 用户要求：点「我」后先下滑约半屏，再搜「赵今麦」
        onProgress("下滑露出超话社区…")
        swipeMePageHalfScreen()
        delay(900)
        logVisibleTexts("after_me_half_swipe")

        onProgress("在「我」页进入「$topicName」…")
        val enteredFromMe = enterTopicFromMePage(topicName)
        if (!enteredFromMe) {
            onProgress("点击「超话社区」…")
            clickSuperTopicCommunity()
            delay(1_200)
            logPage("after 超话社区")
            onProgress("尝试「全部关注」…")
            runCatching {
                clickByTexts(
                    labels = listOf("全部关注"),
                    step = "全部关注",
                    timeoutMs = 5_000,
                    preferBottomHalf = false,
                )
            }.onFailure {
                Timber.tag(TAG).w(it, "全部关注 not found, continue")
            }
            delay(800)
            onProgress("进入超话「$topicName」…")
            clickTopicByNameOrCoord(topicName)
        }
    }

    /** 是否已有签到按钮或连签文案（判断超话头图区是否加载） */
    private fun hasCheckInOrSignUi(): Boolean {
        if (readCheckInDays() != null) return true
        val labels = listOf("签到", "立即签到", "今日签到", "连签")
        if (dfsFindViewNodes { n ->
                val t = n.text?.toString().orEmpty()
                labels.any { t.contains(it) } &&
                    n.bounds.centerY() < (screenHeight() * 0.50f).toInt()
            }.isNotEmpty()
        ) {
            return true
        }
        for (label in labels) {
            if (findBySystemText(label).isNotEmpty()) return true
        }
        return false
    }

    private suspend fun waitForMyTitle(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasMyTitleText()) return true
            delay(350)
        }
        return hasMyTitleText()
    }

    /** 测试入口：打开微博后走完整超话 → 超LIKE 路径 */
    suspend fun testNavigateSuperTopicToExp(
        context: Context,
        topicName: String = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
        onProgress: (String) -> Unit = {},
    ): Int? = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("==== testNavigateSuperTopicToExp topic=$topicName ====")
        AccessibilityApi.requireBaseAccessibility(autoJump = false)
        onProgress("打开微博…")
        openWeibo(context)
        waitWeiboReady()
        navigateToSuperLikeExpViaClicks(topicName, onProgress)
    }

    // region 日常任务（签到 / 浏览 / 发帖）

    /**
     * 进入目标超话详情（赵今麦）：
     * 我 → 超话社区列表点话题；必要时确认「展开查看更多」。
     *
     * @return 进入后读到的连签天数（右上「连签274天」）；未签/未读到为 null
     */
    suspend fun openTargetSuperTopic(
        topicName: String = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
        onProgress: (String) -> Unit = {},
    ): Int? = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("==== openTargetSuperTopic topic=$topicName ====")
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                if (attempt > 0) {
                    onProgress("超话组件可能未加载，回首页重进…")
                    runCatching { goToWeiboHome(context = null, onProgress = onProgress) }
                    delay(1_000)
                }
                enterMeThenOpenTopic(topicName, onProgress)
                ensureOnSuperTopicDetailBeforeExpand(topicName)
                delay(800)
                logPage("openTargetSuperTopic done attempt=${attempt + 1}")

                onProgress("读取签到状态…")
                // 等头图区加载：签到按钮或连签文案
                val loaded = waitUntil(
                    timeoutMs = 4_000,
                    intervalMs = 400,
                ) { hasCheckInOrSignUi() || isOnSuperTopicDetailPage() }

                // 已有连签 → 已签到
                val daysAlready = waitReadCheckInDays(timeoutMs = 2_500)
                if (daysAlready != null) {
                    Timber.tag(TAG).i("openTargetSuperTopic: already 连签 $daysAlready")
                    onProgress("已签到，连签 $daysAlready 天")
                    return@withContext daysAlready
                }
                // 看到「签到」文字 → 自动点签到
                if (hasSignInButtonText()) {
                    onProgress("未签到，自动签到…")
                    val days = performCheckIn { p -> onProgress(p) }
                    if (days != null) {
                        onProgress("签到成功，连签 $days 天")
                        return@withContext days
                    }
                    onProgress("已点签到，未读到连签天数")
                    return@withContext null
                }
                // 既无连签也无签到按钮 → 可能未加载
                dumpLayout("open_topic_no_checkin_ui_$attempt")
                if (attempt == 0 || !loaded) {
                    lastError = IllegalStateException("超话页未出现签到/连签")
                    Timber.tag(TAG).w("openTargetSuperTopic: reload, no check-in UI")
                    return@repeat
                }
                onProgress("未检测到签到入口")
                return@withContext null
            } catch (e: Throwable) {
                lastError = e
                Timber.tag(TAG).w(e, "openTargetSuperTopic attempt=${attempt + 1}")
            }
        }
        throw lastError ?: IllegalStateException("无法进入超话「$topicName」")
    }

    /** 头图区是否有可点的「签到」文案（未签到状态） */
    private fun hasSignInButtonText(): Boolean {
        if (readCheckInDays() != null) return false
        val labels = listOf("签到", "立即签到", "今日签到")
        return dfsFindViewNodes { n ->
            val t = n.text?.toString().orEmpty().trim()
            labels.any { t == it || t == " $it" } &&
                !t.contains("连签") &&
                n.bounds.centerY() < (screenHeight() * 0.50f).toInt() &&
                t.length <= 6
        }.isNotEmpty() || labels.any { label ->
            findBySystemText(label).any {
                val t = it.text?.toString().orEmpty()
                !t.contains("连签") && t.length <= 8
            }
        }
    }

    private suspend fun waitUntil(
        timeoutMs: Long,
        intervalMs: Long = 400,
        predicate: () -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return true
            delay(intervalMs)
        }
        return predicate()
    }

    /**
     * 超话内签到。
     * dump 结构：FrameLayout id=right_button → Button text=签到
     * 仅 tryClick 子 Button 常无效，需点父容器 + globalClick 中心，并以「连签xx天」校验成功。
     */
    suspend fun performCheckIn(onProgress: (String) -> Unit = {}): Int? =
        withContext(Dispatchers.Default) {
            Timber.tag(TAG).i("==== performCheckIn ====")
            readCheckInDays()?.let { days ->
                Timber.tag(TAG).i("performCheckIn: already checked in days=$days")
                onProgress("已签到，连签 $days 天")
                return@withContext days
            }
            onProgress("点击「签到」…")
            // 最多 3 轮不同点击策略，每轮后校验是否出现连签
            repeat(3) { round ->
                readCheckInDays()?.let { return@withContext it }
                val target = findSignInClickTarget()
                if (target == null) {
                    Timber.tag(TAG).w("performCheckIn: target null round=$round")
                    delay(500)
                    return@repeat
                }
                Timber.tag(TAG).i(
                    "performCheckIn: round=$round target ${describeNode(target)}"
                )
                val ok = clickSignInRobust(target, round)
                Timber.tag(TAG).i("performCheckIn: click round=$round ok=$ok")
                delay(1_200)

                // 签到成功弹窗会显示「接收本超话签到提醒推送」。检测到该文案即视为成功，
                // 并用系统级 back 关闭弹窗，避免继续等待连签文案并重复点击。
                if (hasCheckInSuccessReminderText()) {
                    Timber.tag(TAG).i(
                        "performCheckIn: success dialog detected, close with system back"
                    )
                    onProgress("检测到签到成功弹窗")
                    back()
                    delay(300)
                    return@withContext readCheckInDays()
                }

                dismissDialogIfAny()
                val days = waitReadCheckInDays(timeoutMs = 3_500)
                if (days != null) {
                    Timber.tag(TAG).i("performCheckIn: success days=$days round=$round")
                    onProgress("签到完成，连签 $days 天")
                    return@withContext days
                }
            }
            val finalDays = readCheckInDays()
            if (finalDays != null) {
                onProgress("签到完成，连签 $finalDays 天")
                return@withContext finalDays
            }
            dumpLayout("check_in_click_failed")
            // 找到过节点但点不成功
            if (findSignInClickTarget() != null) {
                error("找到签到按钮但点击未生效（未出现连签天数）")
            }
            error("找不到「签到」按钮")
        }

    private fun hasCheckInSuccessReminderText(): Boolean =
        collectAllNodeTexts().any {
            it.contains(WeiboConsts.CHECK_IN_SUCCESS_REMINDER_TEXT)
        }

    /**
     * 签到点击目标优先级：
     * 1. id=right_button（父容器，dump 实测 Clickable）
     * 2. Button text=签到
     * 3. 其它含「签到」短文案节点
     */
    private fun findSignInClickTarget(): ViewNode? {
        // 1) right_button
        val byId = findBySystemViewId(WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON_FULL)
            .ifEmpty { findBySystemViewId(WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON) }
            .ifEmpty {
                dfsFindViewNodes { n ->
                    idShort(n) == WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON
                }
            }
        byId.firstOrNull {
            val b = it.bounds
            b.width() > 0 && b.centerY() < (screenHeight() * 0.55f).toInt()
        }?.let {
            Timber.tag(TAG).d("findSignInClickTarget: right_button ${describeNode(it)}")
            return it
        }

        // 2) Button / text = 签到（上半屏）
        val labels = listOf("签到", "立即签到", "今日签到")
        val textNodes = dfsFindViewNodes { n ->
            val t = n.text?.toString().orEmpty().trim()
            labels.any { t == it } && !t.contains("连签") && t.length <= 6
        }.filter {
            val b = it.bounds
            b.width() > 0 && b.height() > 0 &&
                b.centerY() < (screenHeight() * 0.55f).toInt()
        }
        // 优先 class Button
        textNodes.firstOrNull {
            it.className.orEmpty().contains("Button", true)
        }?.let {
            Timber.tag(TAG).d("findSignInClickTarget: Button ${describeNode(it)}")
            return it
        }
        textNodes.firstOrNull()?.let {
            // 若子节点，优先可点父
            var p: ViewNode? = it
            var d = 0
            while (p != null && d < 4) {
                if (p.isClickable() || idShort(p) == WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON) {
                    Timber.tag(TAG).d("findSignInClickTarget: parent of text ${describeNode(p)}")
                    return p
                }
                p = p.parent
                d++
            }
            return it
        }
        // 系统 API
        for (label in labels) {
            findBySystemText(label).firstOrNull {
                val t = it.text?.toString().orEmpty()
                !t.contains("连签") && t.length <= 8
            }?.let { return it }
        }
        return null
    }

    /**
     * 多策略点击签到：
     * round0: click 自身 + 父 right_button
     * round1: globalClick 中心
     * round2: 再找节点后 globalClick
     */
    private suspend fun clickSignInRobust(node: ViewNode, round: Int): Boolean {
        // 收集候选：自身、right_button 父、可点父
        val candidates = linkedSetOf<ViewNode>()
        candidates += node
        var p: ViewNode? = node.parent
        var depth = 0
        while (p != null && depth < 5) {
            if (idShort(p) == WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON || p.isClickable()) {
                candidates += p
            }
            p = p.parent
            depth++
        }
        // right_button 优先
        val ordered = candidates.sortedByDescending {
            when {
                idShort(it) == WeiboConsts.SUPER_TOPIC_RIGHT_BUTTON -> 3
                it.className.orEmpty().contains("Button", true) -> 2
                it.isClickable() -> 1
                else -> 0
            }
        }
        for (c in ordered) {
            Timber.tag(TAG).i("clickSignInRobust round=$round try ${describeNode(c)}")
            // 1) 直接 ACTION_CLICK
            if (runCatching { c.click() }.getOrDefault(false)) {
                Timber.tag(TAG).i("clickSignInRobust: click() ok")
                return true
            }
            if (c.tryClick()) {
                Timber.tag(TAG).i("clickSignInRobust: tryClick ok")
                return true
            }
        }
        // 2) 坐标点击中心（最稳，不依赖 ACTION_CLICK）
        val target = ordered.firstOrNull() ?: node
        val b = target.bounds
        if (b.width() > 0 && b.height() > 0) {
            val cx = b.centerX()
            val cy = b.centerY()
            Timber.tag(TAG).i("clickSignInRobust: globalClick ($cx,$cy)")
            runCatching {
                cn.vove7.auto.core.api.setScreenSize(screenWidth(), screenHeight())
                if (cn.vove7.auto.core.api.click(cx, cy)) return true
            }.onFailure { Timber.tag(TAG).w(it, "global click failed") }
            if (globalClickNode(target)) return true
        }
        return tryClickCommunityNode(node, "checkin_fallback_r$round")
    }

    /**
     * 解析超话详情右上角「连签274天」（截图：日历图标下）。
     * 文案可能整段，也可能「连签274」与「天」拆节点。
     */
    fun readCheckInDays(): Int? {
        val patterns = listOf(
            Regex("""连签\s*(\d+)\s*天"""),
            Regex("""连签\s*(\d+)"""),
            Regex("""连续签到\s*(\d+)\s*天"""),
            Regex("""已连签\s*(\d+)"""),
        )
        val fromDfs = dfsFindViewNodes { true }.mapNotNull { n ->
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.desc()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val texts = (collectAllNodeTexts() + fromDfs).distinct()
        // 优先上半屏节点（签到区在头图右侧）
        val upper = dfsFindViewNodes { n ->
            val t = n.text?.toString().orEmpty()
            (t.contains("连签") || t.contains("签到")) &&
                n.bounds.centerY() < (screenHeight() * 0.45f).toInt()
        }.mapNotNull { it.text?.toString()?.trim() }
        for (t in (upper + texts).distinct()) {
            for (p in patterns) {
                val m = p.find(t)
                if (m != null) {
                    val d = m.groupValues[1].toIntOrNull()
                    if (d != null && d in 1..9999) {
                        Timber.tag(TAG).i("readCheckInDays: hit '$t' -> $d")
                        return d
                    }
                }
            }
        }
        // 拼接全文再匹配（连签 / 274 / 天 拆开时）
        val joined = texts.joinToString("")
        for (p in patterns) {
            val m = p.find(joined)
            if (m != null) {
                val d = m.groupValues[1].toIntOrNull()
                if (d != null && d in 1..9999) {
                    Timber.tag(TAG).i("readCheckInDays: joined hit exp=$d")
                    return d
                }
            }
        }
        Timber.tag(TAG).d("readCheckInDays: miss sample=${texts.filter { it.contains("签") }.take(10)}")
        return null
    }

    private suspend fun waitReadCheckInDays(timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            readCheckInDays()?.let { return it }
            delay(400)
        }
        return readCheckInDays()
    }

    /**
     * 超话内按 [maxSwipeCount] 次滑动浏览：
     * 滑动 → 停留 ≥6s → 随机长按正文纯文字 → 评论 → 再浏览。
     * 评论文案来自 [commentText]；为空则只浏览不评论。
     */
    suspend fun browseSuperTopicPosts(
        context: Context,
        topicName: String = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
        maxSwipeCount: Int,
        stayMs: Long = WeiboConsts.BROWSE_STAY_MS,
        nextCommentText: suspend () -> String? = { null },
        existingCommentCount: Int = 0,
        maxDailyCommentCount: Int = WeiboConsts.DAILY_COMMENT_LIMIT,
        onCommentSent: suspend (dailyCount: Int) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i(
            "==== browseSuperTopicPosts swipes=$maxSwipeCount " +
                "stay=${stayMs}ms " +
                "commentProvider=true " +
                "dailyCount=$existingCommentCount/$maxDailyCommentCount ===="
        )
        var step = 0
        var commentCount = existingCommentCount.coerceAtLeast(0)
        var capNoticeShown = false
        val w = screenWidth()
        val h = screenHeight()
        ensureTargetSuperTopicBeforeBrowse(context, topicName, onProgress)
        onProgress("切换到「最新」帖子…")
        clickLatestTab()
        delay(700)
        while (step < maxSwipeCount) {
            if (cn.vove7.weibo.auto.overlay.TaskControlHub.isStopRequested()) break
            step++
            val remainingSwipes = maxSwipeCount - step
            onProgress("浏览中… 剩余 $remainingSwipes 次 · 今日已评 $commentCount/$maxDailyCommentCount")

            // Do not swipe on an unrelated page after a dialog or navigation changes the UI.
            ensureTargetSuperTopicBeforeBrowse(context, topicName, onProgress)

            // Only swipe upward on the screen so the feed keeps moving down to newer posts.
            val mode = step % 6
            val xJitter = ((step % 7) - 3) * (w / 50)
            val midX = (w / 2 + xJitter).coerceIn(w / 5, w * 4 / 5)
            val travel = when (mode) {
                0, 1 -> 0.34f + (step % 3) * 0.03f
                2 -> 0.30f
                3 -> 0.22f
                4 -> 0.26f
                else -> 0.32f
            }
            val y1 = (h * 0.78f).toInt()
            val y2 = (y1 - h * travel).toInt().coerceAtLeast((h * 0.18f).toInt())
            val dur = when (mode) {
                0, 1 -> 280 + (step % 3) * 40
                2, 5 -> 500 + (step % 2) * 80
                3 -> 820 + (step % 2) * 80
                else -> 360
            }
            Timber.tag(TAG).d(
                "browse step=$step mode=$mode swipe ($midX,$y1)->($midX,$y2) dur=$dur"
            )
            runCatching {
                cn.vove7.auto.core.api.swipe(midX, y1, midX, y2, dur)
            }

            val stay = stayMs.coerceAtLeast(1_000L)
            onProgress("看帖：第 $step 次滑动一次，停留 ${stay / 1000} 秒")
            onProgress("停留阅读 ${stay / 1000}s… 剩余 $remainingSwipes 次")
            delay(stay)

            // 随机评论：有模板且时间还够
            if (commentCount >= maxDailyCommentCount) {
                if (!capNoticeShown) {
                    onProgress("今日评论已达上限（$maxDailyCommentCount 条），仅继续滑动浏览")
                    capNoticeShown = true
                }
            } else if (remainingSwipes > 0 && step % 2 == 1 // 大约隔一轮评一次，避免太密
            ) {
                val commentText = nextCommentText()
                val ok = if (commentText.isNullOrBlank()) {
                    false
                } else {
                    runCatching {
                        commentOnVisiblePost(commentText) { p -> onProgress(p) }
                    }.onFailure {
                        Timber.tag(TAG).w(it, "commentOnVisiblePost failed")
                    }.getOrDefault(false)
                }
                if (ok) {
                    commentCount++
                    onCommentSent(commentCount)
                    onProgress("评论成功（今日 $commentCount/$maxDailyCommentCount）")
                    delay(800)
                }
            }
        }
        onProgress("浏览结束（滑动 $step/$maxSwipeCount 次，今日评论 $commentCount/$maxDailyCommentCount 条）")
        Timber.tag(TAG).i("browseSuperTopicPosts done steps=$step comments=$commentCount")
    }

    /**
     * 浏览后打开超话的每日任务面板，读取签到、看帖、评论完成状态。
     * 无论是否完成，最后都返回超话页面，便于继续执行浏览任务。
     */
    suspend fun inspectDailyTaskProgress(
        topicName: String = WeiboConsts.TARGET_SUPER_TOPIC_NAME,
        onProgress: (String) -> Unit = {},
    ): DailyTaskProgress {
        onProgress("检查「${topicName}」每日任务完成情况…")
        if (!clickBottomNavTabByDesc("我的") && !clickBottomNavTabByDesc("我")) {
            error("找不到超话页面右下角「我的」按钮")
        }
        waitForTopicMyPage(topicName)

        val taskEntry = findDailyTaskEntryButton()
            ?: error("找不到「我在${topicName}超话」页面的每日任务入口")
        if (!safeClick(taskEntry) && !globalClickNode(taskEntry)) {
            error("点击每日任务入口失败")
        }
        waitForDailyTaskPanel()

        val progress = readDailyTaskProgress()
        Timber.tag(TAG).i(
            "dailyTaskProgress signIn=${progress.checkInStatus} " +
                "browse=${progress.browse.completedCount}/${progress.browse.requiredCount} " +
                "comment=${progress.comment.completedCount}/${progress.comment.requiredCount} " +
                "repost=${progress.repost.completedCount}/${progress.repost.requiredCount}"
        )
        onProgress(
            "每日任务：签到${when (progress.checkInStatus) { "COMPLETED" -> "已完成"; "INCOMPLETE" -> "未完成"; else -> "未检测" }}，" +
                "看帖 ${progress.browse.completedCount}/${progress.browse.requiredCount}，" +
                "评论 ${progress.comment.completedCount}/${progress.comment.requiredCount}"
        )

        // 第一次 back 关闭任务面板；若仍停留在「我在…超话」二级页，再 back 回超话。
        back()
        delay(500)
        if (hasTopicMyPageText(topicName)) {
            back()
            delay(700)
        }
        return progress
    }

    private suspend fun waitForTopicMyPage(topicName: String) {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            if (hasTopicMyPageText(topicName)) return
            delay(300)
        }
        error("未进入「我在${topicName}超话」页面")
    }

    private suspend fun waitForDailyTaskPanel() {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            val texts = dfsFindViewNodes { true }.mapNotNull { it.text?.toString()?.trim() }
            if (texts.any { it.contains("每日成长") } ||
                texts.any { it.contains("签到（随连续签到提高）") }
            ) return
            delay(300)
        }
        error("每日任务面板加载超时")
    }

    private suspend fun findDailyTaskEntryButton(): ViewNode? {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            val w = screenWidth()
            val h = screenHeight()
            val candidate = dfsFindViewNodes { node ->
                node.className.orEmpty().contains("ImageView", ignoreCase = true) &&
                    node.isClickable() &&
                    node.bounds.centerX() > w * 0.78f &&
                    node.bounds.centerY() in (h * 0.15f).toInt()..(h * 0.35f).toInt()
            }.maxByOrNull { it.bounds.centerY() }
            if (candidate != null) return candidate
            delay(300)
        }
        return null
    }

    private fun hasTopicMyPageText(topicName: String): Boolean {
        val texts = dfsFindViewNodes { true }.mapNotNull { node ->
            listOfNotNull(
                node.text?.toString()?.trim(),
                runCatching { node.desc()?.trim() }.getOrNull(),
            ).filter { it.isNotEmpty() }
        }.flatten()
        return texts.any { it.contains("我在${topicName}超话") } ||
            texts.any { it.contains("我在") && it.contains(topicName) && it.contains("超话") }
    }

    private fun readDailyTaskProgress(): DailyTaskProgress {
        data class TextAt(val text: String, val centerY: Int)

        val texts = dfsFindViewNodes { true }.flatMap { node ->
            listOfNotNull(
                node.text?.toString()?.trim(),
                runCatching { node.desc()?.trim() }.getOrNull(),
            ).filter { it.isNotEmpty() }.map { TextAt(it, node.bounds.centerY()) }
        }

        fun rowCounter(label: String, doneLabel: String? = null): DailyTaskCounter {
            val labels = texts.filter { it.text.trim() == label }
            for (labelNode in labels) {
                // 任务的进度文案位于标题正下方。只看向下 180px，
                // 避免「转发帖子」错误读到上一行「评论帖子」的完成次数。
                val rowTexts = texts.filter {
                    it.centerY in labelNode.centerY..(labelNode.centerY + 180)
                }
                rowTexts.forEach { row ->
                    val match = Regex("今日完成次数\\s*(\\d+)\\s*/\\s*(\\d+)").find(row.text)
                    if (match != null) {
                        return DailyTaskCounter(
                            completedCount = match.groupValues[1].toIntOrNull() ?: -1,
                            requiredCount = match.groupValues[2].toIntOrNull() ?: -1,
                        )
                    }
                }
                if (rowTexts.any { it.text.contains("已完成") || (doneLabel != null && it.text.contains(doneLabel)) }) {
                    return DailyTaskCounter(1, 1)
                }
            }
            return DailyTaskCounter()
        }

        val signIn = rowCounter("签到（随连续签到提高）", "已签到")

        return DailyTaskProgress(
            checkInStatus = when {
                signIn.isCompleted -> "COMPLETED"
                signIn.completedCount >= 0 -> "INCOMPLETE"
                else -> "UNKNOWN"
            },
            browse = rowCounter("看帖"),
            comment = rowCounter("评论帖子"),
            repost = rowCounter("转发帖子"),
        )
    }

    private suspend fun clickLatestTab() {
        val latest = findBySystemViewId("com.sina.weibo:id/item_text")
            .ifEmpty { findBySystemViewId("item_text") }
            .firstOrNull { it.text?.toString()?.trim() == "最新" }
            ?: dfsFindViewNodes { node ->
                idShort(node) == "item_text" && node.text?.toString()?.trim() == "最新"
            }.firstOrNull()
        if (latest == null) {
            Timber.tag(TAG).w("clickLatestTab: item_text=最新 not found")
            return
        }
        if (!tryClickCommunityNode(latest, "latest_tab")) {
            Timber.tag(TAG).w("clickLatestTab: click failed ${describeNode(latest)}")
        }
    }

    /**
     * The browse loop must only swipe inside the selected super topic. If a dialog or navigation
     * leaves it elsewhere, return to the Weibo home shell and enter the target topic again.
     */
    private suspend fun ensureTargetSuperTopicBeforeBrowse(
        context: Context,
        topicName: String,
        onProgress: (String) -> Unit,
    ) {
        if (isOnTargetSuperTopicPage(topicName)) return

        // The title view can briefly disappear while Weibo refreshes the topic content.
        val settleDeadline = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < settleDeadline) {
            delay(250)
            if (isOnTargetSuperTopicPage(topicName)) return
        }

        onProgress("当前不在「${WeiboConsts.TARGET_SUPER_TOPIC_PAGE_MARKER}」，返回首页重新进入…")
        goToWeiboHome(context, onProgress)
        delay(800)
        openTargetSuperTopic(topicName, onProgress)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (isOnTargetSuperTopicPage(topicName)) return
            delay(350)
        }
        dumpLayout("target_super_topic_not_restored")
        error("未能重新进入「${WeiboConsts.TARGET_SUPER_TOPIC_PAGE_MARKER}」，已停止滑动")
    }

    private suspend fun isOnTargetSuperTopicPage(topicName: String): Boolean {
        if (currentPackage() != WeiboConsts.PACKAGE) return false

        // The page title is stable on the target super-topic page: tvTitle = "赵今麦超话".
        val marker = WeiboConsts.TARGET_SUPER_TOPIC_PAGE_MARKER
        val titleNodes = findBySystemViewId(WeiboConsts.SUPER_TOPIC_TITLE_FULL)
            .ifEmpty { findBySystemViewId(WeiboConsts.SUPER_TOPIC_TITLE) }
        if (titleNodes.any { node ->
                node.text?.toString()?.contains(marker) == true ||
                    node.desc()?.contains(marker) == true
            }
        ) {
            return true
        }
        if (dfsFindViewNodes { node ->
                idShort(node) == WeiboConsts.SUPER_TOPIC_TITLE &&
                    (node.text?.toString()?.contains(marker) == true ||
                    node.desc()?.contains(marker) == true
                    )
            }.isNotEmpty()
        ) {
            return true
        }

        if (withText(marker).exist() || containsText(marker).exist() || withDesc(marker).exist() ||
            findBySystemText(marker).isNotEmpty()
        ) return true

        // 部分版本的超话页面不会暴露稳定的标题节点，但页面内容同时包含发帖入口和话题名。
        // 这两个文案组合出现时，可确认仍在目标超话内部，避免误返回首页重进。
        val pageTexts = dfsFindViewNodes { true }.flatMap { node ->
            listOfNotNull(
                node.text?.toString()?.trim(),
                runCatching { node.desc()?.trim() }.getOrNull(),
            ).filter { it.isNotEmpty() }
        }
        val hasTopicMarker = pageTexts.any { it.contains("赵今麦超话") }
        val hasPostEntry = pageTexts.any { it.contains("我来发一帖") }
        val hasTopicName = pageTexts.any {
            it.contains(topicName) || it.contains("赵今麦")
        }
        if (hasTopicMarker || (hasPostEntry && hasTopicName)) {
            Timber.tag(TAG).i(
                "isOnTargetSuperTopicPage: matched topic marker or post entry + topic name fallback"
            )
            return true
        }

        val visibleTexts = runCatching { ScreenTextFinder().find() }
            .getOrDefault(emptyList())
            .mapNotNull { it.text?.toString() }
        return visibleTexts.any { text ->
            text.contains(marker) || (text.contains(topicName) && text.contains("超话"))
        }
    }

    /**
     * 随机找一条可见帖子正文（contentTextView，避开 #话题# 区域）：
     * 长按 → 评论 → 输入模板 → 确保未勾选「同时转发」→ 点「评论」发送。
     */
    private suspend fun commentOnVisiblePost(
        commentText: String,
        onProgress: (String) -> Unit,
    ): Boolean {
        onProgress("寻找可评论帖子…")
        val body = findRandomPostBodyText()
        if (body == null) {
            Timber.tag(TAG).w("commentOnVisiblePost: no contentTextView")
            return false
        }
        Timber.tag(TAG).i("commentOnVisiblePost: long-press ${describeNode(body)}")
        onProgress("长按正文…")
        if (!longPressPostBody(body)) {
            Timber.tag(TAG).w("commentOnVisiblePost: long press failed")
            return false
        }
        delay(700)
        // 菜单：转发 / 评论 / 收藏
        onProgress("点击菜单「评论」…")
        val menuComment = dfsFindViewNodes { n ->
            n.text?.toString()?.trim() == "评论"
        }.firstOrNull {
            // 弹层中部，不是底部发送按钮
            val y = it.bounds.centerY()
            y in (screenHeight() * 0.35f).toInt()..(screenHeight() * 0.70f).toInt()
        } ?: findBySystemText("评论").firstOrNull {
            it.bounds.centerY() < (screenHeight() * 0.75f).toInt()
        }
        if (menuComment == null || !tryClickCommunityNode(menuComment, "menu_comment")) {
            Timber.tag(TAG).w("commentOnVisiblePost: menu 评论 not found")
            closeCommentUiIfOpen("menu_comment_not_found")
            return false
        }
        delay(1_000)

        onProgress("输入评论…")
        val editor = findCommentEditor()
        if (editor == null) {
            Timber.tag(TAG).w("commentOnVisiblePost: editor not found")
            dumpLayout("comment_editor_not_found")
            closeCommentUiIfOpen("editor_not_found")
            return false
        }
        runCatching { editor.tryClick() }
        delay(250)
        val setOk = runCatching { editor.trySetText(commentText) }.getOrDefault(false)
        if (!setOk) runCatching { editor.text = commentText }
        delay(500)
        Timber.tag(TAG).i("commentOnVisiblePost: text setOk=$setOk")

        // 同时转发默认可能勾选，需关掉
        uncheckCommentForward()
        delay(300)

        onProgress("发送评论…")
        if (!clickCommentSend()) {
            Timber.tag(TAG).w("commentOnVisiblePost: send failed")
            dumpLayout("comment_send_failed")
            closeCommentUiIfOpen("send_failed")
            return false
        }
        // A successful send automatically closes ComposerActivity and returns to the SG page.
        // Never send a global back here: stale editor nodes can otherwise return to the home page.
        if (!waitForCommentComposerToClose()) {
            onProgress("评论发送未确认，未计入今日次数")
            return false
        }
        return true
    }

    private suspend fun closeCommentUiIfOpen(reason: String) {
        val page = currentPageName().orEmpty()
        val isCommentUi = page.contains("ComposerActivity", ignoreCase = true) ||
            page.contains("WeiboDialog", ignoreCase = true) ||
            page.contains("CustomDialog", ignoreCase = true)
        if (!isCommentUi) {
            Timber.tag(TAG).d("closeCommentUiIfOpen: skip reason=$reason page=$page")
            return
        }
        Timber.tag(TAG).i("closeCommentUiIfOpen: back reason=$reason page=$page")
        back()
        delay(400)
    }

    private suspend fun waitForCommentComposerToClose(timeoutMs: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val page = currentPageName().orEmpty()
            if (page.contains("SGPage", ignoreCase = true) ||
                page.contains("SuperGroup", ignoreCase = true)
            ) {
                Timber.tag(TAG).i("comment send confirmed page=$page")
                return true
            }
            if (!page.contains("ComposerActivity", ignoreCase = true)) {
                Timber.tag(TAG).w("comment send ended on unexpected page=$page")
                return false
            }
            delay(250)
        }
        Timber.tag(TAG).w("comment composer still visible after send")
        return false
    }

    /**
     * 随机选一条正文：id=contentTextView，在中部可见区。
     * 长按点偏正文中心略下，减少点到 #话题# 链接。
     */
    private fun findRandomPostBodyText(): ViewNode? {
        val h = screenHeight()
        val candidates = dfsFindViewNodes { n ->
            idShort(n) == "contentTextView" ||
                (n.id?.endsWith("/contentTextView") == true)
        }.filter {
            val b = it.bounds
            b.width() > screenWidth() / 3 &&
                b.height() in 40..900 &&
                b.centerY() in (h * 0.22f).toInt()..(h * 0.82f).toInt()
        }
        if (candidates.isEmpty()) {
            // 兜底：desc 含较长正文且非纯话题
            return dfsFindViewNodes { n ->
                val d = n.desc()?.trim().orEmpty()
                d.length in 12..400 &&
                    n.bounds.centerY() in (h * 0.25f).toInt()..(h * 0.80f).toInt() &&
                    n.bounds.width() > screenWidth() / 2
            }.randomOrNull()
        }
        return candidates.randomOrNull()
    }

    private suspend fun longPressPostBody(node: ViewNode): Boolean {
        // 点正文中心略偏下，避开顶部 #话题#
        val b = node.bounds
        val cx = b.centerX()
        val cy = (b.top + b.height() * 0.62f).toInt().coerceIn(b.top + 8, b.bottom - 8)
        Timber.tag(TAG).i("longPressPostBody at ($cx,$cy) ${describeNode(node)}")
        // 1) 节点 longClick
        if (runCatching { node.tryLongClick() }.getOrDefault(false)) return true
        if (runCatching { node.longClick() }.getOrDefault(false)) return true
        // 2) 手势长按坐标
        return runCatching {
            cn.vove7.auto.core.api.setScreenSize(screenWidth(), screenHeight())
            cn.vove7.auto.core.api.longClick(cx, cy)
        }.getOrDefault(false)
    }

    private fun findCommentEditor(): ViewNode? {
        val editables = dfsFindViewNodes { n ->
            runCatching { n.node.isEditable }.getOrDefault(false)
        }
        if (editables.isNotEmpty()) {
            return editables.maxByOrNull { it.bounds.width() * it.bounds.height() }
        }
        return dfsFindViewNodes { n ->
            val id = idShort(n)
            val cls = n.className.orEmpty()
            id.contains("edit", true) ||
                id.contains("input", true) ||
                id.contains("comment", true) ||
                cls.contains("EditText")
        }.firstOrNull()
    }

    /** 评论框「同时转发」：id=checkbox，checked=true 时点掉 */
    private suspend fun uncheckCommentForward() {
        val boxes = findBySystemViewId(WeiboConsts.POST_SYNC_CHECKBOX_FULL)
            .ifEmpty { findBySystemViewId(WeiboConsts.POST_SYNC_CHECKBOX) }
            .ifEmpty {
                dfsFindViewNodes {
                    idShort(it) == "checkbox" ||
                        it.className.orEmpty().contains("CheckBox", true)
                }
            }
        for (b in boxes) {
            runCatching { b.node.refresh() }
            val checked = runCatching { b.node.isChecked }.getOrNull()
            Timber.tag(TAG).i("comment forward checkbox checked=$checked ${describeNode(b)}")
            if (checked == true) {
                if (runCatching { b.click() }.getOrDefault(false) || b.tryClick()) {
                    delay(300)
                }
                return
            }
            if (checked == false) return
        }
        // 文案「同时转发」旁
        val label = dfsFindViewNodes {
            it.text?.toString()?.contains("同时转发") == true
        }.firstOrNull()
        if (label != null) {
            val near = listOfNotNull(label, label.parent, label.previousSibling, label.nextSibling)
            for (c in near) {
                val checked = runCatching { c.node.isChecked }.getOrNull()
                if (checked == true) {
                    tryClickCommunityNode(c, "uncheck_forward")
                    return
                }
            }
        }
    }

    private suspend fun clickCommentSend(): Boolean {
        // id=btnSend text=评论；输入后应变为可点
        val end = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < end) {
            val byId = dfsFindViewNodes { idShort(it) == "btnSend" }
            val btn = byId.firstOrNull()
                ?: dfsFindViewNodes {
                    it.text?.toString()?.trim() == "评论" &&
                        it.bounds.centerY() > (screenHeight() * 0.40f).toInt()
                }.maxByOrNull { it.bounds.centerX() }
            if (btn != null) {
                Timber.tag(TAG).i("clickCommentSend ${describeNode(btn)}")
                if (tryClickCommunityNode(btn, "btnSend")) return true
                if (runCatching { btn.click() }.getOrDefault(false)) return true
                if (globalClickNode(btn)) return true
            }
            delay(350)
        }
        return false
    }

    /**
     * 超话发帖：我来发一帖 → 填文案 → 关闭「同步到微博」→ 发送。
     */
    suspend fun performPost(
        content: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        Timber.tag(TAG).i("==== performPost len=${content.length} ====")
        require(content.isNotBlank()) { "发帖内容为空" }
        onProgress("点击「我来发一帖」…")
        val composeLabels = listOf("我来发一帖", "发帖", "说点什么", "写帖子")
        var opened = false
        for (label in composeLabels) {
            val nodes = dfsFindViewNodes { n ->
                n.text?.toString()?.contains(label) == true ||
                    n.desc()?.contains(label) == true
            }
            Timber.tag(TAG).d("performPost compose label=$label count=${nodes.size}")
            val n = nodes.firstOrNull()
            if (n != null && tryClickCommunityNode(n, "compose_$label")) {
                opened = true
                break
            }
            val sys = findBySystemText(label)
            if (sys.isNotEmpty() && tryClickCommunityNode(sys.first(), "compose_sys_$label")) {
                opened = true
                break
            }
        }
        if (!opened) {
            dumpLayout("compose_entry_not_found")
            error("找不到发帖入口「我来发一帖」")
        }
        delay(1_500)

        onProgress("填写发帖内容…")
        val editor = findPostEditor()
            ?: run {
                dumpLayout("post_editor_not_found")
                error("找不到发帖输入框")
            }
        Timber.tag(TAG).i("performPost editor ${describeNode(editor)}")
        // 聚焦并设置文本
        runCatching { editor.tryClick() }
        delay(300)
        val setOk = runCatching {
            editor.trySetText(content)
        }.getOrDefault(false)
        if (!setOk) {
            // 再试 text 属性
            runCatching { editor.text = content }
        }
        delay(600)
        Timber.tag(TAG).i("performPost text setOk=$setOk")

        onProgress("关闭「同步到微博」…")
        uncheckSyncToWeibo()
        delay(400)

        onProgress("点击发送…")
        if (!clickSendPost()) {
            dumpLayout("send_post_not_found")
            error("找不到「发送」按钮")
        }
        delay(1_500)
        dismissDialogIfAny()
        onProgress("发帖完成")
        logPage("performPost done")
    }

    private fun findPostEditor(): ViewNode? {
        // editable 节点
        val editables = dfsFindViewNodes { n ->
            runCatching { n.node.isEditable }.getOrDefault(false)
        }
        if (editables.isNotEmpty()) {
            return editables.maxByOrNull { it.bounds.width() * it.bounds.height() }
        }
        // 常见 id / 类名
        return dfsFindViewNodes { n ->
            val id = idShort(n)
            val cls = n.className.orEmpty()
            id.contains("edit", true) ||
                id.contains("input", true) ||
                id.contains("content", true) ||
                cls.contains("EditText")
        }.firstOrNull()
    }

    /**
     * 关闭「同步到微博」。
     * 读 com.sina.weibo:id/checkbox 的 isChecked：
     * - false → 已取消，绝不点击（避免又勾上）
     * - true  → 点一次；**重新查找节点**再读状态，仅当仍为 true 才再点
     *
     * 注意：点完后旧 ViewNode 的 isChecked 可能仍是缓存 true，
     * 不能复用旧节点判断，否则会误点第二次把勾又打开。
     */
    private suspend fun uncheckSyncToWeibo() {
        // 最多两轮：每轮都重新 find + 读 checked
        repeat(2) { round ->
            val box = findPostSyncCheckBox()
            if (box == null) {
                Timber.tag(TAG).w("uncheckSync: checkbox not found round=$round")
                if (round == 0) {
                    // 兜底：class CheckBox
                    if (uncheckAnyCheckBoxOnce()) return
                }
                if (round == 1) dumpLayout("post_sync_checkbox_not_found")
                return
            }
            // refresh 节点再读
            runCatching { box.node.refresh() }
            val checked = runCatching { box.node.isChecked }.getOrNull()
            Timber.tag(TAG).i(
                "uncheckSync: round=$round checked=$checked ${describeNode(box)}"
            )
            when (checked) {
                false -> {
                    Timber.tag(TAG).i("uncheckSync: already false, ready to send")
                    return
                }
                true -> {
                    // 只点 checkbox 自身，避免 tryClick 点到父容器
                    val clicked = clickCheckBoxDirect(box, "uncheck_r$round")
                    Timber.tag(TAG).i("uncheckSync: click round=$round ok=$clicked")
                    delay(500)
                    // 下一轮循环会重新 find 再读
                }
                null -> {
                    if (round == 0) {
                        clickCheckBoxDirect(box, "uncheck_unknown")
                        delay(500)
                    } else {
                        return
                    }
                }
            }
        }
        // 最终再确认一次
        val finalBox = findPostSyncCheckBox()
        runCatching { finalBox?.node?.refresh() }
        val finalChecked = runCatching { finalBox?.node?.isChecked }.getOrNull()
        Timber.tag(TAG).i("uncheckSync: final checked=$finalChecked")
        if (finalChecked == true) {
            Timber.tag(TAG).w("uncheckSync: still checked after 2 clicks, stop (avoid toggle loop)")
        }
    }

    /** 直接点 CheckBox，优先 click() 再 tryClick，避免误点父布局 */
    private suspend fun clickCheckBoxDirect(node: ViewNode, source: String): Boolean {
        Timber.tag(TAG).i("clickCheckBoxDirect source=$source ${describeNode(node)}")
        if (runCatching { node.click() }.getOrDefault(false)) return true
        if (node.tryClick()) return true
        return globalClickNode(node)
    }

    /** 页面上任意 CheckBox：仅当 checked==true 点一次 */
    private suspend fun uncheckAnyCheckBoxOnce(): Boolean {
        val boxes = dfsFindViewNodes { n ->
            n.className.orEmpty().contains("CheckBox", ignoreCase = true) ||
                idShort(n) == WeiboConsts.POST_SYNC_CHECKBOX
        }
        for (b in boxes) {
            runCatching { b.node.refresh() }
            val checked = runCatching { b.node.isChecked }.getOrNull()
            Timber.tag(TAG).d("uncheckAnyCheckBox checked=$checked ${describeNode(b)}")
            if (checked == false) return true
            if (checked == true) {
                clickCheckBoxDirect(b, "uncheck_any")
                delay(500)
                return true
            }
        }
        return false
    }

    /** 发帖页同步勾选：id/checkbox 或 full resource-id */
    private fun findPostSyncCheckBox(): ViewNode? {
        val byFull = findBySystemViewId(WeiboConsts.POST_SYNC_CHECKBOX_FULL)
        if (byFull.isNotEmpty()) {
            return byFull.firstOrNull {
                it.className.orEmpty().contains("CheckBox", true) ||
                    runCatching { it.node.isCheckable }.getOrDefault(false)
            } ?: byFull.first()
        }
        val byShort = findBySystemViewId(WeiboConsts.POST_SYNC_CHECKBOX)
        if (byShort.isNotEmpty()) {
            return byShort.firstOrNull {
                it.className.orEmpty().contains("CheckBox", true)
            } ?: byShort.first()
        }
        return dfsFindViewNodes { n ->
            idShort(n) == WeiboConsts.POST_SYNC_CHECKBOX ||
                n.id == WeiboConsts.POST_SYNC_CHECKBOX_FULL
        }.firstOrNull {
            it.className.orEmpty().contains("CheckBox", true) ||
                runCatching { it.node.isCheckable }.getOrDefault(false)
        }
    }

    private suspend fun clickSendPost(): Boolean {
        val labels = listOf("发送", "发布", "发微博")
        for (label in labels) {
            // 优先右上角
            val nodes = dfsFindViewNodes { n ->
                val t = n.text?.toString().orEmpty()
                t == label || t.contains(label)
            }.filter {
                it.bounds.centerY() < (screenHeight() * 0.25f).toInt() ||
                    it.bounds.centerX() > (screenWidth() * 0.55f).toInt()
            }
            Timber.tag(TAG).d("clickSendPost label=$label count=${nodes.size}")
            val btn = nodes.maxByOrNull { it.bounds.centerX() }
            if (btn != null && tryClickCommunityNode(btn, "send_$label")) return true
            val sys = findBySystemText(label)
            if (sys.isNotEmpty() && tryClickCommunityNode(sys.first(), "send_sys_$label")) return true
        }
        return false
    }

    // endregion

    /**
     * 点击底部导航 Tab。
     * dump：main_radio 下子项 **desc**=首页/视频/发现/消息/我，本身 Clickable。
     * **禁止** withText/containsText，否则会点到「我的相册」等内容文案。
     */
    private suspend fun clickBottomNavTabByDesc(desc: String): Boolean {
        val bottomThreshold = (screenHeight() * 0.72f).toInt()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            // 1) withDesc + 底部区域
            val byDesc = withDesc(desc).findAll()
                .filter {
                    val b = it.bounds
                    b.centerY() >= bottomThreshold && b.width() > 0 && b.height() > 0
                }
            Timber.tag(TAG).d(
                "clickBottomNavTabByDesc($desc): byDesc=${byDesc.size} " +
                    byDesc.take(4).joinToString { describeNode(it) }
            )
            // 「我」在最右，「首页」在最左
            val preferred = when (desc) {
                "我", "我的" -> byDesc.maxByOrNull { it.bounds.centerX() }
                "首页", "主页", "Home" -> byDesc.minByOrNull { it.bounds.centerX() }
                else -> byDesc.firstOrNull()
            }
            if (preferred != null) {
                Timber.tag(TAG).i("clickBottomNavTabByDesc: click desc=$desc -> $preferred")
                if (safeClick(preferred) || globalClickNode(preferred)) return true
            }

            // 2) DFS：id=main_radio 子树内 desc 匹配
            val mainRadioKids = dfsFindViewNodes { n ->
                val d = runCatching { n.desc() }.getOrNull().orEmpty()
                d == desc && n.bounds.centerY() >= bottomThreshold
            }
            val tab = when (desc) {
                "我", "我的" -> mainRadioKids.maxByOrNull { it.bounds.centerX() }
                "首页", "主页", "Home" -> mainRadioKids.minByOrNull { it.bounds.centerX() }
                else -> mainRadioKids.firstOrNull()
            }
            if (tab != null) {
                Timber.tag(TAG).i("clickBottomNavTabByDesc: dfs desc=$desc -> $tab")
                if (safeClick(tab) || globalClickNode(tab)) return true
            }
            delay(350)
        }
        return false
    }

    /** 底部「首页」Tab（仅 desc，不点正文） */
    private suspend fun clickHomeTab(): Boolean =
        clickBottomNavTabByDesc("首页") ||
            clickBottomNavTabByDesc("主页")

    /**
     * 在「我」页进入目标超话（纯无障碍）。
     *
     * 必须先定位「超话社区」标题，再点其**下方**横向列表项。
     * 中间宫格也有 icon_info_icon（我的相册等），titleY 未知时绝不能乱点。
     *
     * 成功判据：Activity 进入 SGPage / SuperGroup（log 已验证），**不要**再用
     * 文案「相册」误判（多窗口树里可能仍残留「我」页节点）。
     */
    private suspend fun enterTopicFromMePage(topicName: String): Boolean {
        Timber.tag(TAG).i(
            "enterTopicFromMePage: topic=$topicName isOnMe=${isOnMePage()} page=${currentPageName()}"
        )
        // 若误入相册等子页，先 back 回「我」
        if (isWrongSubPageFromMe()) {
            Timber.tag(TAG).w("enterTopicFromMePage: wrong subpage ${currentPageName()}, back")
            back()
            delay(800)
        }
        // 已在超话页则直接成功
        if (isOnSuperTopicDetailPage()) {
            Timber.tag(TAG).i("enterTopicFromMePage: already on SG page")
            return true
        }
        val end = System.currentTimeMillis() + 15_000
        var attempt = 0
        while (System.currentTimeMillis() < end) {
            attempt++
            if (isOnSuperTopicDetailPage()) {
                Timber.tag(TAG).i(
                    "enterTopicFromMePage: success page=${currentPageName()} attempt=$attempt"
                )
                return true
            }
            if (isWrongSubPageFromMe()) {
                Timber.tag(TAG).w(
                    "enterTopicFromMePage: wrong subpage ${currentPageName()}, back"
                )
                back()
                delay(700)
                continue
            }
            if (clickSuperTopicListItemOnMePage(topicName, attempt)) {
                // 等待页面切换（SGPage 会出现在 onPageUpdate）
                val entered = waitUntilSuperTopicDetailPage(timeoutMs = 4_000)
                if (entered) {
                    Timber.tag(TAG).i(
                        "enterTopicFromMePage: ok after click attempt=$attempt page=${currentPageName()}"
                    )
                    return true
                }
                if (isWrongSubPageFromMe()) {
                    Timber.tag(TAG).w(
                        "enterTopicFromMePage: hit wrong page ${currentPageName()}, back"
                    )
                    back()
                    delay(800)
                    continue
                }
                Timber.tag(TAG).w(
                    "enterTopicFromMePage: clicked but page=${currentPageName()} attempt=$attempt"
                )
            }
            // 未找到：慢速短滑露出区块，滑完多等再识别（避免划过头还没判断）
            swipeUpOnMePage(attempt)
            delay(1_200)
        }
        dumpLayout("enter_topic_from_me_failed")
        Timber.tag(TAG).w("enterTopicFromMePage: failed page=${currentPageName()}")
        return false
    }

    /** 是否已在超话详情 Activity（成功） */
    private fun isOnSuperTopicDetailPage(): Boolean {
        val page = currentPageName().orEmpty()
        return page.contains("SGPage", true) ||
            page.contains("SuperGroup", true) ||
            page.contains("supergroup", true)
    }

    private suspend fun waitUntilSuperTopicDetailPage(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isOnSuperTopicDetailPage()) return true
            if (isWrongSubPageFromMe()) return false
            delay(300)
        }
        return isOnSuperTopicDetailPage()
    }

    /**
     * 「我」页主页特征。
     * **优先看 Activity 类名**；SGPage 绝不是我页。
     * 不要用「我的相册」文案（子页/残留树都会有）。
     */
    private fun isOnMePage(): Boolean {
        val page = currentPageName().orEmpty()
        if (isOnSuperTopicDetailPage()) return false
        if (page.contains("FlowCommon", true) ||
            page.contains("Album", true) ||
            page.contains("Browser", true) ||
            page.contains("NewCardList", true)
        ) {
            return false
        }
        // MainTabActivity + 资料区
        if (!page.contains("MainTab", true) && page.isNotEmpty() &&
            !page.contains("Dialog", true)
        ) {
            // 其它未知页：仅当有 mine_nickname 才算我页
            return dfsFindViewNodes { n ->
                n.id.orEmpty().endsWith("mine_nickname") ||
                    n.id.orEmpty().endsWith("mine_header_avatar")
            }.isNotEmpty()
        }
        val hasMineProfile = dfsFindViewNodes { n ->
            val id = n.id.orEmpty()
            id.endsWith("mine_nickname") || id.endsWith("mine_header_avatar")
        }.isNotEmpty()
        if (hasMineProfile) return true
        return dfsFindViewNodes { n ->
            val t = n.text?.toString().orEmpty()
            t.contains("任务中心") || t.contains("超话社区")
        }.isNotEmpty()
    }

    /**
     * 误点宫格进入的子页（相册等）。
     * **只看 pageName**，不扫全树文案（多窗口会残留「我的相册」导致把 SGPage 误判）。
     */
    private fun isWrongSubPageFromMe(): Boolean {
        val page = currentPageName().orEmpty()
        // 超话成功页
        if (isOnSuperTopicDetailPage()) return false
        if (page.contains("FlowCommon", true)) return true
        if (page.contains("Album", true)) return true
        // 其它业务页且不是 MainTab / Dialog
        return false
    }

    private fun isLikelyLeftMePageSuccessfully(): Boolean {
        if (isOnSuperTopicDetailPage()) return true
        val page = currentPageName().orEmpty()
        if (page.contains("CardList", true)) return true
        return false
    }

    /**
     * 点「我」页 **超话社区** 横向列表项。
     * 必须先找到「超话社区」标题，只点其下方节点；禁止点中间宫格 icon_info_icon。
     */
    private suspend fun clickSuperTopicListItemOnMePage(
        topicName: String,
        attempt: Int,
    ): Boolean {
        val h = screenHeight()
        val bottomTabY = (h * 0.92f).toInt()
        // 宫格「我的相册」等大约在 0.30~0.55 屏高；超话社区通常在 0.75 以下
        val gridMaxY = (h * 0.62f).toInt()

        Timber.tag(TAG).d(
            "clickSuperTopicListItem: attempt=$attempt h=$h gridMaxY=$gridMaxY"
        )

        // 1) 定位「超话社区」标题（title_title_left 或 text）
        val communityTitle = dfsFindViewNodes { n ->
            val t = n.text?.toString().orEmpty()
            t.contains("超话社区") ||
                (idShort(n) == "title_title_left" && t.contains("超话"))
        }.minByOrNull { it.bounds.centerY() }

        if (communityTitle == null) {
            Timber.tag(TAG).w(
                "clickSuperTopicListItem: 超话社区 title not found, swipe only (no mid-grid click)"
            )
            return false
        }
        val titleY = communityTitle.bounds.centerY()
        Timber.tag(TAG).i(
            "clickSuperTopicListItem: communityTitleY=$titleY ${describeNode(communityTitle)}"
        )

        // 2) 标题下方的话题名
        val byName = dfsFindViewNodes { n ->
            n.text?.toString()?.contains(topicName) == true
        }.filter {
            val b = it.bounds
            b.centerY() > titleY && b.centerY() < bottomTabY
        }
        Timber.tag(TAG).d(
            "clickSuperTopicListItem: byName=${byName.size} " +
                byName.take(5).joinToString { describeNode(it) }
        )
        for (n in byName) {
            if (tryClickCommunityNode(n, "dfs_name_$topicName")) return true
        }

        // 3) 标题下方 icon_info_icon（横向超话头像），排除宫格区
        val icons = dfsFindViewNodes { n ->
            idShort(n) == "icon_info_icon" || idShort(n).endsWith("icon_info_icon")
        }.filter {
            val b = it.bounds
            b.width() > 0 && b.height() > 0 &&
                b.centerY() > titleY &&
                b.centerY() > gridMaxY &&
                b.centerY() < bottomTabY
        }.sortedBy { it.bounds.left }
        Timber.tag(TAG).d(
            "clickSuperTopicListItem: superTopicIcons=${icons.size} " +
                icons.take(6).joinToString { describeNode(it) }
        )
        val firstIcon = icons.firstOrNull()
        if (firstIcon != null && tryClickCommunityNode(firstIcon, "dfs_super_icon")) {
            return true
        }

        // 4) 标题下方 Clickable 卡片（横向列表项）
        val cards = dfsFindViewNodes { n ->
            if (!n.isClickable()) return@dfsFindViewNodes false
            val b = n.bounds
            if (b.width() <= 0 || b.height() <= 0) return@dfsFindViewNodes false
            val y = b.centerY()
            y > titleY && y > gridMaxY && y < bottomTabY &&
                b.width() in 80..350 &&
                b.height() in 100..450
        }.sortedBy { it.bounds.left }
        Timber.tag(TAG).d(
            "clickSuperTopicListItem: cards=${cards.size} " +
                cards.take(6).joinToString { describeNode(it) }
        )
        val firstCard = cards.firstOrNull()
        if (firstCard != null && tryClickCommunityNode(firstCard, "dfs_super_card")) {
            return true
        }

        // 5) 「立即签到」在超话社区标题行右侧，可进社区（备选）
        val signIn = dfsFindViewNodes { n ->
            n.text?.toString()?.contains("立即签到") == true
        }.filter { it.bounds.centerY() > titleY - 80 && it.bounds.centerY() < titleY + 120 }
        for (n in signIn) {
            if (tryClickCommunityNode(n, "dfs_sign_in")) return true
        }
        return false
    }

    /** 与 dump 相同：从 ViewNode.getRoot() DFS，含不可见节点 */
    private fun dfsFindViewNodes(
        maxDepth: Int = 60,
        predicate: (ViewNode) -> Boolean,
    ): List<ViewNode> {
        val out = mutableListOf<ViewNode>()
        val root = runCatching { ViewNode.getRoot() }.getOrNull() ?: return emptyList()
        val stack = ArrayDeque<Pair<ViewNode, Int>>()
        stack.add(root to 0)
        var scanned = 0
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            scanned++
            try {
                if (predicate(node)) out += node
                val children = node.children
                // ViewChildList：倒序入栈保持从左到右优先
                for (i in children.size - 1 downTo 0) {
                    val c = children[i] ?: continue
                    stack.add(c to (depth + 1))
                }
            } catch (_: Throwable) {
            }
        }
        if (out.isEmpty()) {
            Timber.tag(TAG).d("dfsFindViewNodes: miss scanned=$scanned")
        }
        return out
    }

    private fun idShort(n: ViewNode): String {
        val id = n.id ?: return ""
        return id.substringAfterLast('/')
    }

    /** 是否已离开「我」主页进入超话相关页 */
    private suspend fun isLikelyLeftMePage(): Boolean {
        if (isOnMePage()) return false
        val page = currentPageName().orEmpty()
        if (page.contains("SGPage", true) ||
            page.contains("SuperGroup", true) ||
            page.contains("supergroup", true) ||
            page.contains("CardList", true)
        ) {
            return true
        }
        val keywords = listOf("我的头衔", "展开更多", "全部关注", "主持人", "超话粉丝", "活跃头衔")
        for (k in keywords) {
            if (withText(k).exist() || containsText(k).exist() ||
                findBySystemText(k).isNotEmpty()
            ) {
                return true
            }
        }
        return !isOnMePage()
    }

    /**
     * 点「我」后首滑：短距慢滑露出超话社区（不要划太猛把整页滑走）。
     * 用户路径：点我 → 上滑一点 → 再搜「赵今麦」。
     */
    private suspend fun swipeMePageHalfScreen() {
        runCatching {
            val midX = screenWidth() / 2
            val h = screenHeight()
            // 约 18% 屏高、偏慢：0.70 → 0.52
            val yStart = (h * 0.70f).toInt()
            val yEnd = (h * 0.52f).toInt()
            val dur = 780
            Timber.tag(TAG).i(
                "swipeMePageFirst: y=$yStart->$yEnd dur=${dur}ms (短距慢滑)"
            )
            cn.vove7.auto.core.api.swipe(midX, yStart, midX, yEnd, dur)
            delay(900)
        }.onFailure {
            Timber.tag(TAG).w(it, "swipeMePageHalfScreen failed")
        }
    }

    /**
     * 「我」页后续微调上滑（未找到话题时用，比半屏短）。
     */
    private suspend fun swipeUpOnMePage(attempt: Int = 0) {
        runCatching {
            val midX = screenWidth() / 2
            val h = screenHeight()
            val yStart = (h * 0.68f).toInt()
            val yEnd = (h * 0.52f).toInt()
            val dur = 650 + (attempt % 3) * 80
            Timber.tag(TAG).i(
                "swipeUpOnMePage: short swipe y=$yStart->$yEnd dur=${dur}ms attempt=$attempt"
            )
            cn.vove7.auto.core.api.swipe(midX, yStart, midX, yEnd, dur)
            delay(500)
        }.onFailure {
            Timber.tag(TAG).w(it, "swipeUpOnMePage failed")
        }
    }

    /** 按话题名点击（ViewNode DFS，与 dump 同源） */
    private suspend fun clickTopicByNameOrCoord(
        topicName: String,
        allowCoord: Boolean = false,
    ): Boolean {
        val end = System.currentTimeMillis() + 10_000
        var attempt = 0
        while (System.currentTimeMillis() < end) {
            attempt++
            // 「我」页：直接点横向列表项
            if (isOnMePage() && clickSuperTopicListItemOnMePage(topicName, attempt)) {
                delay(1_200)
                if (!isOnMePage()) return true
            }
            val nodes = dfsFindViewNodes { n ->
                n.text?.toString()?.contains(topicName) == true
            }
            Timber.tag(TAG).d(
                "clickTopicByName: attempt=$attempt name=$topicName count=${nodes.size} " +
                    nodes.take(5).joinToString { describeNode(it) }
            )
            for (node in nodes) {
                if (tryClickCommunityNode(node, "topic_$topicName")) {
                    delay(1_000)
                    if (!isOnMePage()) return true
                }
            }
            delay(350)
        }
        Timber.tag(TAG).w("clickTopicByName: failed topic=$topicName")
        return false
    }

    /**
     * 进入超话相关列表：不点标题，直接点「我」页超话社区区块内列表项。
     */
    private suspend fun clickSuperTopicCommunity() {
        val topic = WeiboConsts.TARGET_SUPER_TOPIC_NAME
        if (clickSuperTopicListItemOnMePage(topic, 0)) {
            delay(1_000)
            return
        }
        // 再试一轮 DFS 任意 icon_info
        if (clickSuperTopicListItemOnMePage(topic, 1)) {
            delay(1_000)
            return
        }
        dumpLayout("super_topic_list_item_not_found")
        throw ViewNodeNotFoundException("找不到超话社区列表项（ViewNode DFS）")
    }

    private suspend fun tryClickCommunityNode(node: ViewNode, source: String): Boolean {
        Timber.tag(TAG).i(
            "tryClickCommunityNode source=$source ${describeNode(node)}"
        )
        // 文案节点常 not clickable，safeClick/tryClick 会向上找可点父容器
        if (safeClick(node)) {
            Timber.tag(TAG).i("tryClickCommunityNode: safeClick ok source=$source")
            return true
        }
        // 显式点父容器
        var p = node.parent
        var depth = 0
        while (p != null && depth < 6) {
            Timber.tag(TAG).d(
                "tryClickCommunityNode: try parent#$depth clickable=${p.isClickable()} ${describeNode(p)}"
            )
            if (p.isClickable() && safeClick(p)) {
                Timber.tag(TAG).i("tryClickCommunityNode: parent click ok depth=$depth")
                return true
            }
            p = p.parent
            depth++
        }
        if (globalClickNode(node)) {
            Timber.tag(TAG).i("tryClickCommunityNode: globalClick ok source=$source")
            return true
        }
        return false
    }

    private fun findBySystemText(text: String): List<ViewNode> {
        val roots = accessibilityRoots()
        val out = mutableListOf<ViewNode>()
        for (root in roots) {
            try {
                val list = root.findAccessibilityNodeInfosByText(text).orEmpty()
                Timber.tag(TAG).d(
                    "findBySystemText($text): root=${root.className} hits=${list.size}"
                )
                list.forEach { out += ViewNode(it) }
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "findBySystemText($text) failed")
            }
        }
        return out
    }

    private fun findBySystemViewId(viewId: String): List<ViewNode> {
        val roots = accessibilityRoots()
        val out = mutableListOf<ViewNode>()
        for (root in roots) {
            try {
                val list = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
                if (list.isNotEmpty()) {
                    Timber.tag(TAG).d(
                        "findBySystemViewId($viewId): hits=${list.size}"
                    )
                }
                list.forEach { out += ViewNode(it) }
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "findBySystemViewId($viewId) failed")
            }
        }
        return out
    }

    private fun accessibilityRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            AutoApi.rootInActiveWindow()?.also { list += it }
        }
        runCatching {
            AutoApi.windows()?.forEach { w ->
                w.root?.let { r -> if (r !in list) list += r }
            }
        }
        return list
    }

    private suspend fun collectNodesByLabelsIncludeInvisible(labels: List<String>): List<ViewNode> {
        val result = linkedSetOf<ViewNode>()
        for (label in labels) {
            result += withText(label).includeInvisible(true).findAll()
            result += withDesc(label).includeInvisible(true).findAll()
            result += containsText(label).includeInvisible(true).findAll()
        }
        return result.toList()
    }

    private suspend fun logVisibleTexts(tag: String) {
        val texts = runCatching {
            ScreenTextFinder().includeInvisible(true).find()
                .mapNotNull { it.text?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }.getOrDefault(emptyList())
        Timber.tag(TAG).i("texts[$tag](${texts.size}): ${texts.take(40)}")
    }

    private fun describeNode(n: ViewNode): String {
        val t = n.text?.toString() ?: runCatching { n.desc() }.getOrNull() ?: ""
        return "[t=$t id=${n.id} click=${n.isClickable()} " +
            "c=(${n.bounds.centerX()},${n.bounds.centerY()}) b=${n.bounds}]"
    }

    private suspend fun globalClickNode(node: ViewNode): Boolean {
        return try {
            val ok = node.globalClick()
            Timber.tag(TAG).i("globalClickNode ok=$ok bounds=${node.bounds}")
            ok
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "globalClickNode failed")
            false
        }
    }

    /**
     * 展开前确认已在超话详情页。
     * 判据：页面上存在「展开查看更多 / 展开更多」等文案。
     * 若没有：再点一次话题名「赵今麦」，然后继续。
     */
    private suspend fun ensureOnSuperTopicDetailBeforeExpand(topicName: String) {
        if (hasExpandMoreText()) {
            Timber.tag(TAG).i(
                "ensureOnSuperTopicDetailBeforeExpand: already has expand text, page=${currentPageName()}"
            )
            return
        }
        // 已在 SG 页但文案未出：先等一会
        if (isOnSuperTopicDetailPage()) {
            val appeared = waitForExpandMoreText(timeoutMs = 3_000)
            if (appeared) {
                Timber.tag(TAG).i("ensureOnSuperTopicDetailBeforeExpand: expand text appeared after wait")
                return
            }
        }
        Timber.tag(TAG).w(
            "ensureOnSuperTopicDetailBeforeExpand: no「展开查看更多」, click topic=$topicName again"
        )
        // 再点话题名（可能还在列表/半层）
        val clicked = runCatching {
            clickTopicNameOnScreen(topicName)
        }.getOrDefault(false)
        Timber.tag(TAG).i("ensureOnSuperTopicDetailBeforeExpand: re-click topic ok=$clicked")
        delay(1_200)
        waitUntilSuperTopicDetailPage(timeoutMs = 4_000)
        waitForExpandMoreText(timeoutMs = 4_000)
        if (!hasExpandMoreText() && !hasMyTitleText()) {
            Timber.tag(TAG).w(
                "ensureOnSuperTopicDetailBeforeExpand: still no expand/title text page=${currentPageName()}"
            )
            dumpLayout("before_expand_no_detail")
        }
    }

    private fun hasExpandMoreText(): Boolean {
        val labels = listOf("展开查看更多", "展开更多", "查看更多")
        // DFS（与 dump 同源）+ 系统 API
        if (dfsFindViewNodes { n ->
                val t = n.text?.toString().orEmpty()
                labels.any { t.contains(it) }
            }.isNotEmpty()
        ) {
            return true
        }
        for (label in labels) {
            if (findBySystemText(label).isNotEmpty()) return true
            // 同步 existBlocking 可能没有，用 findFirstBlocking
            if (withText(label).existBlocking() || containsText(label).existBlocking()) return true
        }
        return false
    }

    private fun hasMyTitleText(): Boolean {
        return dfsFindViewNodes { n ->
            n.text?.toString()?.contains("我的头衔") == true
        }.isNotEmpty() ||
            findBySystemText("我的头衔").isNotEmpty() ||
            withText("我的头衔").existBlocking() ||
            containsText("我的头衔").existBlocking()
    }

    private suspend fun waitForExpandMoreText(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasExpandMoreText() || hasMyTitleText()) return true
            delay(350)
        }
        return hasExpandMoreText() || hasMyTitleText()
    }

    /** 在当前屏点话题名（超话列表/详情标题均可） */
    private suspend fun clickTopicNameOnScreen(topicName: String): Boolean {
        val nodes = dfsFindViewNodes { n ->
            n.text?.toString()?.contains(topicName) == true
        }.filter {
            val b = it.bounds
            b.width() > 0 && b.height() > 0 &&
                b.centerY() < (screenHeight() * 0.85f).toInt()
        }
        // 优先 icon_info_title / 较短精确名，避开商品长标题
        val preferred = nodes.firstOrNull {
            idShort(it) == "icon_info_title" ||
                it.text?.toString()?.trim() == topicName
        } ?: nodes.minByOrNull { it.text?.toString()?.length ?: 999 }
        if (preferred != null) {
            Timber.tag(TAG).i("clickTopicNameOnScreen: ${describeNode(preferred)}")
            return tryClickCommunityNode(preferred, "reclick_$topicName")
        }
        // 系统 API 兜底
        val sys = findBySystemText(topicName)
        val short = sys.firstOrNull { it.text?.toString()?.trim() == topicName }
            ?: sys.minByOrNull { it.text?.toString()?.length ?: 999 }
        if (short != null) {
            return tryClickCommunityNode(short, "reclick_sys_$topicName")
        }
        return false
    }

    /** 展开更多：优先「展开查看更多」 */
    private suspend fun clickExpandMore() {
        val labels = listOf("展开查看更多", "展开更多", "查看更多", "展开")
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            // 已能看到「我的头衔」则无需再展开
            if (hasMyTitleText()) {
                Timber.tag(TAG).i("clickExpandMore: 「我的头衔」已可见，跳过展开")
                return
            }
            // DFS 优先（WebView/自定义控件更稳）
            for (label in labels) {
                val nodes = dfsFindViewNodes { n ->
                    n.text?.toString()?.contains(label) == true
                }.filter {
                    it.bounds.centerY() <= (screenHeight() * 0.80f).toInt()
                }
                Timber.tag(TAG).d(
                    "clickExpandMore: dfs label=$label count=${nodes.size}"
                )
                val node = nodes.minByOrNull { it.bounds.centerY() }
                if (node != null) {
                    Timber.tag(TAG).i("clickExpandMore: click [$label] -> $node")
                    if (tryClickCommunityNode(node, "expand_$label")) return
                }
            }
            for (label in labels) {
                val nodes = collectNodesByLabels(listOf(label))
                Timber.tag(TAG).d(
                    "clickExpandMore: finder label=$label count=${nodes.size}"
                )
                val node = nodes
                    .filter { it.bounds.centerY() <= (screenHeight() * 0.80f).toInt() }
                    .minByOrNull { it.bounds.centerY() }
                    ?: nodes.firstOrNull()
                if (node != null) {
                    Timber.tag(TAG).i("clickExpandMore: finder click [$label] -> $node")
                    if (safeClick(node) || globalClickNode(node)) return
                }
            }
            delay(400)
        }
        dumpLayout("expand_more_not_found")
        // 不强制失败：有的账号默认已展开
        Timber.tag(TAG).w("clickExpandMore: not found, continue anyway")
    }

    /**
     * 通用文案点击：按 labels 顺序找节点并点击。
     */
    private suspend fun clickByTexts(
        labels: List<String>,
        step: String,
        timeoutMs: Long,
        preferBottomHalf: Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        val midY = (screenHeight() * 0.5f).toInt()
        while (System.currentTimeMillis() < end) {
            for (label in labels) {
                val all = collectNodesByLabels(listOf(label))
                Timber.tag(TAG).d(
                    "clickByTexts[$step]: label=$label count=${all.size} " +
                        all.take(6).joinToString { n ->
                            "c=(${n.bounds.centerX()},${n.bounds.centerY()}) t=${n.text}"
                        }
                )
                val filtered = when {
                    preferBottomHalf -> all.filter { it.bounds.centerY() >= midY }
                    else -> all
                }
                val node = filtered.firstOrNull() ?: all.firstOrNull()
                if (node != null) {
                    Timber.tag(TAG).i("clickByTexts[$step]: click [$label] -> $node")
                    if (safeClick(node)) return
                }
            }
            delay(350)
        }
        dumpLayout("click_failed_$step")
        throw ViewNodeNotFoundException("找不到「${labels.firstOrNull()}」（步骤 $step）")
    }

    private fun logPage(tag: String) {
        Timber.tag(TAG).i(
            "page[$tag]: pkg=${currentPackage()} page=${currentPageName()}"
        )
    }

    // endregion

    /**
     * 打开超 LIKE 列表页。
     *
     * 自动任务时本 App 已在后台，必须用 **AccessibilityService** 作为 Context
     * 发 Intent，普通 Application Context 会被 BAL 静默拦截。
     *
     * 注意：App 进程内 `Runtime.exec("am start")` **不能** 当 shell 用，
     * 会 Permission Denial（你 log 里的 exit=255），已移除该兜底。
     */
    suspend fun openSuperLikePage(context: Context) = withContext(Dispatchers.Main) {
        fireSuperLikeIntent(resolveStartContext(context))
    }

    /**
     * 打开超话页：
     * sinaweibo://pageinfo?containerid=... → SGPageActivity
     */
    suspend fun openSuperTopicPage(context: Context) = withContext(Dispatchers.Main) {
        fireSuperTopicIntent(resolveStartContext(context))
    }

    /**
     * 可靠打开超话页：保持微博前台，用无障碍 Service 发 deep link，
     * 校验是否进入 SGPage / 超话相关页。
     */
    suspend fun openSuperTopicPageReliable(
        context: Context,
        maxRetry: Int = 2,
        onProgress: (String) -> Unit = {},
    ) {
        var lastError: Throwable? = null
        repeat(maxRetry) { attempt ->
            onProgress("打开超话页（第 ${attempt + 1}/$maxRetry 次）…")

            if (!isWeiboInForeground()) {
                onProgress("微博不在前台，重新拉起微博…")
                openWeibo(context)
                runCatching { waitWeiboReady(12_000) }
            }
            delay(if (attempt == 0) 600 else 900)

            try {
                openSuperTopicPage(context)
            } catch (e: Throwable) {
                Timber.w(e, "openSuperTopicPage attempt=${attempt + 1} fire failed")
                lastError = e
            }

            val entered = waitUntilSuperTopicPage(timeoutMs = 5_000)
            if (entered) {
                Timber.i("openSuperTopicPageReliable: page verified on attempt ${attempt + 1}")
                return
            }

            Timber.w(
                "openSuperTopicPageReliable: not verified attempt=${attempt + 1}, " +
                    "pkg=${currentPackage()} page=${currentPageName()}"
            )
        }
        dumpLayout("super_topic_page_not_opened")
        throw lastError ?: IllegalStateException(
            "多次尝试后仍未进入超话相关页面（当前 ${currentPackage()}/${currentPageName()}）"
        )
    }

    /**
     * 可靠打开：保持在微博前台（**不**回桌面），用无障碍 Service 发 deep link，
     * 校验是否进入 NewCardList / 超LIKE 相关页。
     */
    suspend fun openSuperLikePageReliable(
        context: Context,
        maxRetry: Int = 3,
        onProgress: (String) -> Unit = {},
    ) {
        var lastError: Throwable? = null
        repeat(maxRetry) { attempt ->
            onProgress("打开超LIKE 页（第 ${attempt + 1}/$maxRetry 次）…")

            // 确保微博在前台（不 home；home 后在 MIUI 上 deep link 常被吞）
            if (!isWeiboInForeground()) {
                onProgress("微博不在前台，重新拉起微博…")
                openWeibo(context)
                runCatching { waitWeiboReady(12_000) }
            }
            delay(if (attempt == 0) 600 else 900)

            try {
                openSuperLikePage(context)
            } catch (e: Throwable) {
                Timber.w(e, "openSuperLikePage attempt=${attempt + 1} fire failed")
                lastError = e
            }

            // 轮询等待页面切换（比固定 delay 更稳）
            val entered = waitUntilSuperLikePage(timeoutMs = 5_000)
            if (entered) {
                Timber.i("openSuperLikePageReliable: page verified on attempt ${attempt + 1}")
                return
            }

            Timber.w(
                "openSuperLikePageReliable: not verified attempt=${attempt + 1}, " +
                    "pkg=${currentPackage()} page=${currentPageName()}"
            )
        }
        dumpLayout("super_like_page_not_opened")
        throw lastError ?: IllegalStateException(
            "多次尝试后仍未进入超LIKE 相关页面（当前 ${currentPackage()}/${currentPageName()}）"
        )
    }

    private fun resolveStartContext(fallback: Context): Context {
        val service = AccessibilityApi.baseService
        return if (service != null) {
            Timber.i("fireSuperLikeIntent use AccessibilityService context")
            service
        } else {
            Timber.w("fireSuperLikeIntent fallback Application context (BAL 风险高)")
            fallback.applicationContext
        }
    }

    /**
     * 仅发 Intent 跳转超 LIKE 页（不做重试/页面校验）。
     * ACTION_VIEW + data + package + NEW_TASK
     */
    private fun fireSuperLikeIntent(context: Context) {
        val uri = Uri.parse(
            "sinaweibo://cardlist?containerid=${WeiboConsts.SUPER_LIKE_CONTAINER_ID}"
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            // 强制指定微博包名，避免弹出浏览器选择框
            setPackage(WeiboConsts.PACKAGE)
            // Service / 非 Activity 上下文需要 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Timber.i(
            "openSuperLikePage fired ctx=${context.javaClass.simpleName} uri=$uri"
        )
    }

    private fun fireSuperTopicIntent(context: Context) {
        val uri = Uri.parse(
            "sinaweibo://pageinfo?containerid=${WeiboConsts.SUPER_TOPIC_CONTAINER_ID}"
        )
        // 与 dumpsys 对齐：dat + pkg + cmp(SGPageActivity) + NEW_TASK
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                component = ComponentName(
                    WeiboConsts.PACKAGE,
                    WeiboConsts.SUPER_TOPIC_ACTIVITY,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.i(
                "openSuperTopicPage fired (component) ctx=${context.javaClass.simpleName} uri=$uri"
            )
            return
        } catch (e: Throwable) {
            Timber.w(e, "openSuperTopicPage component failed: $uri")
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(WeiboConsts.PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.i(
                "openSuperTopicPage fired (view+package) ctx=${context.javaClass.simpleName}"
            )
            return
        } catch (e: Throwable) {
            Timber.w(e, "openSuperTopicPage view failed: $uri")
            throw e
        }
    }

    private fun isWeiboInForeground(): Boolean =
        currentPackage() == WeiboConsts.PACKAGE

    private fun currentPackage(): String? =
        cn.vove7.auto.core.AutoApi.currentPageInfo?.packageName
            ?: ViewNode.activeWinNode()?.packageName

    private fun currentPageName(): String? =
        cn.vove7.auto.core.AutoApi.currentPageInfo?.pageName

    /**
     * 是否已进入徽章/超LIKE 相关页。
     * 优先看 Activity 类名 NewCardListActivity，再看文案关键词。
     */
    suspend fun isOnSuperLikeRelatedPage(): Boolean {
        val page = currentPageName().orEmpty()
        val pkg = currentPackage()
        if (pkg == WeiboConsts.PACKAGE && page.contains("NewCardList", ignoreCase = true)) {
            return true
        }
        if (pkg != WeiboConsts.PACKAGE) {
            Timber.d("not weibo foreground: pkg=$pkg page=$page")
            return false
        }
        val keywords = listOf(
            "超LIKE", "超Like", "超like", "徽章", "全部徽章",
            "经验值", "点亮",
        )
        for (k in keywords) {
            if (withText(k).exist() || containsText(k).exist() || withDesc(k).exist()) {
                return true
            }
        }
        val texts = runCatching {
            ScreenTextFinder().find().mapNotNull { it.text?.toString() }
        }.getOrDefault(emptyList())
        Timber.d("super like page texts sample: ${texts.take(20)} pkg=$pkg page=$page")
        return texts.any { t ->
            keywords.any { k -> t.contains(k, ignoreCase = true) }
        }
    }

    private suspend fun waitUntilSuperLikePage(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isOnSuperLikeRelatedPage()) return true
            delay(400)
        }
        return isOnSuperLikeRelatedPage()
    }

    /**
     * 是否已进入超话详情/列表页（不是「我」页上的「超话社区」区块）。
     * 「我」页本身就有「超话社区」「签到」，不能当成功判据。
     */
    suspend fun isOnSuperTopicRelatedPage(): Boolean {
        // 仍在「我」页 = 一定还没进超话详情
        if (isOnMePage()) {
            Timber.tag(TAG).d("isOnSuperTopicRelatedPage: still on 我 page")
            return false
        }
        val page = currentPageName().orEmpty()
        val pkg = currentPackage()
        if (pkg == WeiboConsts.PACKAGE &&
            (page.contains("SGPage", ignoreCase = true) ||
                page.contains("SuperGroup", ignoreCase = true) ||
                page.contains("supergroup", ignoreCase = true) ||
                page.contains("CardList", ignoreCase = true))
        ) {
            return true
        }
        if (pkg != WeiboConsts.PACKAGE) {
            Timber.d("not weibo foreground: pkg=$pkg page=$page")
            return false
        }
        // 仅用超话详情页特征，避免「我」页误判
        val keywords = listOf("我的头衔", "展开更多", "全部关注", "主持人", "超话粉丝", "今日签到")
        for (k in keywords) {
            if (withText(k).exist() || containsText(k).exist() || withDesc(k).exist() ||
                findBySystemText(k).isNotEmpty()
            ) {
                return true
            }
        }
        return false
    }

    private suspend fun waitUntilSuperTopicPage(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isOnSuperTopicRelatedPage()) return true
            delay(400)
        }
        return isOnSuperTopicRelatedPage()
    }

    /**
     * 点击「超LIKE」。
     * 头衔页可能已直接展示经验值文案，此时无需再点。
     */
    suspend fun clickSuperLikeLabel() {
        // 已在经验值详情（WebView 活跃头衔弹层）则跳过
        if (collectAllNodeTexts().any { it.contains("经验值") && it.any(Char::isDigit) }) {
            Timber.tag(TAG).i("clickSuperLikeLabel: exp text already visible, skip click")
            return
        }
        val labels = listOf("超LIKE", "超Like", "超like", "超 LIKE")
        // 系统 API 优先（WebView 内文案）
        val sys = labels.flatMap { findBySystemText(it) }
        val node = sys.firstOrNull()
            ?: findClickableByTextsOrDescs(labels, timeoutMs = 8_000)
            ?: containsText("超LIKE", "超Like", "超like").waitFor(4_000)
            ?: run {
                dumpLayout("super_like_label_not_found")
                throw ViewNodeNotFoundException("找不到文字「超LIKE」")
            }
        Timber.tag(TAG).i("clickSuperLikeLabel -> $node")
        if (!safeClick(node) && !globalClickNode(node) && !tryClickCommunityNode(node, "超LIKE")) {
            // 文案已出现经验值则不报错
            if (collectAllNodeTexts().none { it.contains("经验值") }) {
                error("点击「超LIKE」失败")
            }
        }
        delay(800)
    }

    /**
     * 解析近7天经验值。
     *
     * 头衔 WebView dump 实测文案：
     * - 「你近7天获取的经验值: 74」
     * - 「头衔要求：近7天获取经验值≥80」
     * - 「再获取6经验值可获得超LIKE头衔…」
     *
     * ScreenTextFinder 默认可能只扫到状态栏（时间/网速/电量100），
     * 必须用系统 findAccessibilityNodeInfosByText + includeInvisible 扫 WebView。
     */
    suspend fun readExpValue(timeoutMs: Long = 10_000): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        // 优先匹配「你近7天…经验值」；避免把「≥80」要求值或电量 100 当经验
        val preferredPatterns = listOf(
            Regex("""你近\s*7\s*天获取的?经验值\s*[:：]?\s*(\d+)"""),
            Regex("""近\s*7\s*天获取的?经验值\s*[:：]?\s*(\d+)"""),
            Regex("""获取的?经验值\s*[:：]\s*(\d+)"""),
            Regex("""经验值\s*[:：]\s*(\d+)"""),
        )
        val fallbackPatterns = listOf(
            Regex("""经验\s*[:：]?\s*(\d+)"""),
            Regex("""EXP\s*[:：]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        )
        while (System.currentTimeMillis() < end) {
            val texts = collectAllNodeTexts()
            Timber.tag(TAG).d("exp candidate texts(${texts.size}): ${texts.take(40)}")

            // 1) 单条文本优先匹配
            for (t in texts) {
                // 跳过要求阈值行，避免把 ≥80 解析成 80
                if (t.contains("头衔要求") || t.contains("≥") || t.contains(">=")) continue
                for (p in preferredPatterns) {
                    val m = p.find(t)
                    if (m != null) {
                        val v = m.groupValues[1].toIntOrNull()
                        Timber.tag(TAG).i("readExpValue preferred hit text='$t' exp=$v")
                        return v
                    }
                }
            }
            // 2) 拼接全文再匹配（经验值与数字拆节点时）
            val joined = texts.joinToString(" ")
            for (p in preferredPatterns) {
                val m = p.find(joined)
                if (m != null) {
                    val v = m.groupValues[1].toIntOrNull()
                    Timber.tag(TAG).i("readExpValue preferred joined exp=$v")
                    return v
                }
            }
            // 3) 兜底：排除状态栏噪声后再 match
            val filtered = texts.filterNot { isStatusBarNoise(it) }
            val filteredJoined = filtered.joinToString(" ")
            for (p in preferredPatterns + fallbackPatterns) {
                val m = p.find(filteredJoined)
                if (m != null) {
                    val v = m.groupValues[1].toIntOrNull()
                    // 电量 100 常见误读：若全文无「经验」则丢弃
                    if (v == 100 && filteredJoined.none { it == '经' }) continue
                    Timber.tag(TAG).i("readExpValue fallback exp=$v from='$filteredJoined'")
                    return v
                }
            }
            delay(400)
        }
        dumpLayout("exp_value_not_found")
        return null
    }

    /** 收集全树文本：系统 API + ScreenTextFinder(含不可见) + 手动 DFS WebView */
    private fun collectAllNodeTexts(): List<String> {
        val out = linkedSetOf<String>()
        // 系统按关键字拉 WebView 节点
        listOf(
            "经验值", "经验", "超LIKE", "超Like", "活跃头衔",
            "近7天", "头衔要求", "再获取",
        ).forEach { key ->
            findBySystemText(key).forEach { n ->
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                runCatching { n.desc() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        // ScreenTextFinder 含不可见
        runCatching {
            // 同步阻塞路径：findAllBlocking 若有；否则用 find 需 suspend，这里 DFS
        }
        // 手动 DFS 全树（含 WebView）
        val roots = accessibilityRoots()
        for (root in roots) {
            dfsCollectTexts(root, out, depth = 0)
        }
        return out.toList()
    }

    private fun dfsCollectTexts(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        depth: Int,
    ) {
        if (node == null || depth > 50) return
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            // hint 有时带文案
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
            for (i in 0 until node.childCount) {
                dfsCollectTexts(node.getChild(i), out, depth + 1)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isStatusBarNoise(t: String): Boolean {
        if (t.matches(Regex("""^\d{1,3}$"""))) return true // 单独数字如电量 100
        if (t.contains("K/s", true) || t.contains("M/s", true)) return true
        if (t.startsWith("下午") || t.startsWith("上午") || t.contains(":")) {
            // 时间 下午2:36
            if (t.length <= 12) return true
        }
        if (t == "|" || t == "100") return true
        return false
    }

    /** 关掉弹窗，尽量回到可继续操作的状态 */
    suspend fun dismissDialogIfAny() {
        clickIfPresent(listOf("关闭", "取消", "知道了", "我知道了", "确定"), timeoutMs = 1_200)
        // 若仍在弹层，back 一次
        delay(200)
    }

    /** 从超LIKE 页回到账号管理：多 back + 必要时重新导航 */
    suspend fun backToAccountManage(onProgress: (String) -> Unit = {}) {
        repeat(5) {
            if (isOnAccountManage()) return
            back()
            delay(400)
        }
        if (!isOnAccountManage()) {
            onProgress("重新进入账号管理…")
            goToAccountManage(onProgress)
        }
    }

    suspend fun ensureOnAccountManage() {
        if (isOnAccountManage()) return
        // 尝试 back 到账号管理
        repeat(3) {
            back()
            delay(350)
            if (isOnAccountManage()) return
        }
        goToAccountManage()
    }

    private suspend fun isOnAccountManage(): Boolean {
        return withText("账号管理", "帐号管理").exist() ||
            containsText("账号管理", "帐号管理").exist() ||
            withText("添加账号", "添加帐号").exist()
    }

    private suspend fun findAccountRow(accountName: String): ViewNode? {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            withText(accountName).findFirst()?.let { return it }
            containsText(accountName).findFirst()?.let { return it }
            withDesc(accountName).findFirst()?.let { return it }
            delay(350)
        }
        return null
    }

    /**
     * 底部「我」Tab：只点 main_radio 里 desc=我 的按钮。
     * 绝不能用 withText("我")，会点到「我的相册」等。
     */
    private suspend fun clickMeTab() {
        if (clickBottomNavTabByDesc("我")) {
            delay(400)
            return
        }
        dumpLayout("me_tab_not_found")
        throw ViewNodeNotFoundException("找不到底部导航「我」(desc)，勿点内容区「我」文字")
    }

    /** 等待「我」页资料区出现，避免 RecyclerView 未绑定时误点 */
    private suspend fun waitUntilMePageReady(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isWrongSubPageFromMe()) {
                Timber.tag(TAG).w("waitUntilMePageReady: wrong subpage, back")
                back()
                delay(600)
                clickBottomNavTabByDesc("我")
                delay(500)
                continue
            }
            val ready = dfsFindViewNodes { n ->
                val id = n.id.orEmpty()
                val t = n.text?.toString().orEmpty()
                id.endsWith("mine_nickname") ||
                    id.endsWith("mine_header_avatar") ||
                    t.contains("超话社区") ||
                    t.contains("任务中心")
            }.isNotEmpty()
            if (ready) {
                Timber.tag(TAG).i("waitUntilMePageReady: ok")
                delay(400)
                return
            }
            delay(400)
        }
        Timber.tag(TAG).w("waitUntilMePageReady: timeout, continue anyway")
    }

    private suspend fun clickSettings() {
        val node = findSettingsNode()
            ?: run {
                dumpLayout("settings_not_found")
                throw ViewNodeNotFoundException("找不到右上角「设置」")
            }
        if (!safeClick(node)) error("点击「设置」失败")
    }

    private suspend fun clickAccountManage() {
        val node = findClickableByTextsOrDescs(
            listOf("账号管理", "帐号管理"),
            timeoutMs = 12_000,
        ) ?: run {
            dumpLayout("account_manage_not_found")
            throw ViewNodeNotFoundException("找不到「账号管理」")
        }
        if (!safeClick(node)) error("点击「账号管理」失败")
        withText("账号管理", "帐号管理").waitFor(8_000)
            ?: containsText("账号管理", "帐号管理").waitFor(3_000)
    }

    private suspend fun findSettingsNode(): ViewNode? {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            findTopRightByLabels(listOf("设置", "Settings", "设置页", "setting"))?.let { return it }
            val idHints = listOf(
                "titleSave", "titleRight", "rightBtn", "right_btn", "title_right",
                "iv_setting", "setting", "settings", "btn_setting",
                "navigation_right_btn", "titlebar_right",
            )
            for (id in idHints) {
                withId(id).findFirst()?.let { node ->
                    if (isInTopBar(node.bounds.centerY())) return node
                }
            }
            findTopRightMostClickable()?.let { return it }
            delay(400)
        }
        return null
    }

    private suspend fun findTopRightByLabels(labels: List<String>): ViewNode? {
        val rightThreshold = (screenWidth() * 0.45f).toInt()
        return collectNodesByLabels(labels)
            .filter { isInTopBar(it.bounds.centerY()) && it.bounds.centerX() >= rightThreshold }
            .maxByOrNull { it.bounds.centerX() }
    }

    private fun findTopRightMostClickable(): ViewNode? {
        val screenW = screenWidth()
        val screenH = screenHeight()
        val topLimit = (screenH * 0.18f).toInt()
        val minX = (screenW * 0.65f).toInt()
        val maxSize = (screenW * 0.25f).toInt()
        val candidates = mutableListOf<ViewNode>()
        collectClickableInRegion(ViewNode.getRoot(), candidates, topLimit, minX, maxSize, maxSize)
        return candidates.maxByOrNull { it.bounds.centerX() }
    }

    private fun collectClickableInRegion(
        node: ViewNode?,
        out: MutableList<ViewNode>,
        maxBottom: Int,
        minCenterX: Int,
        maxWidth: Int,
        maxHeight: Int,
    ) {
        if (node == null) return
        try {
            val b = node.bounds
            if (node.isClickable() &&
                b.bottom <= maxBottom + 40 &&
                b.centerY() <= maxBottom &&
                b.centerX() >= minCenterX &&
                b.width() in 24..maxWidth &&
                b.height() in 24..maxHeight
            ) {
                out += node
            }
            for (i in 0 until node.childCount) {
                collectClickableInRegion(
                    node.childAt(i), out, maxBottom, minCenterX, maxWidth, maxHeight
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun isInTopBar(centerY: Int): Boolean =
        centerY <= (screenHeight() * 0.22f).toInt()

    private suspend fun findBestBottomTab(labels: List<String>): ViewNode? {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            val bottomThreshold = (screenHeight() * 0.75f).toInt()
            val bottom = collectNodesByLabels(labels)
                .filter { it.bounds.centerY() >= bottomThreshold }
                .maxByOrNull { it.bounds.centerX() }
            if (bottom != null) return bottom
            delay(400)
        }
        return null
    }

    private suspend fun collectNodesByLabels(labels: List<String>): List<ViewNode> {
        val result = linkedSetOf<ViewNode>()
        for (label in labels) {
            result += withText(label).findAll()
            result += withDesc(label).findAll()
            result += containsText(label).findAll()
        }
        return result.toList()
    }

    private suspend fun findClickableByTextsOrDescs(
        labels: List<String>,
        timeoutMs: Long,
    ): ViewNode? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            for (label in labels) {
                withText(label).findFirst()?.let { return it }
                withDesc(label).findFirst()?.let { return it }
                containsText(label).findFirst()?.let { return it }
            }
            delay(350)
        }
        return null
    }

    private suspend fun clickIfPresent(labels: List<String>, timeoutMs: Long): Boolean {
        val node = findClickableByTextsOrDescs(labels, timeoutMs) ?: return false
        return safeClick(node)
    }

    suspend fun safeClick(node: ViewNode): Boolean {
        if (node.tryClick()) return true
        return try {
            node.globalClick()
        } catch (e: Throwable) {
            Timber.w(e, "globalClick failed")
            false
        }
    }

    fun dumpLayout(tag: String) {
        try {
            val layout = buildLayoutInfo(includeInvisible = true)
            layout.chunked(3000).forEachIndexed { index, part ->
                Timber.tag("WeiboDump").w("layout[$tag][$index]:\n$part")
            }
        } catch (e: Throwable) {
            Timber.tag("WeiboDump").w(e, "dump failed $tag")
        }
    }

    private fun screenWidth(): Int =
        AccessibilityApi.requireBase.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int =
        AccessibilityApi.requireBase.resources.displayMetrics.heightPixels
}
