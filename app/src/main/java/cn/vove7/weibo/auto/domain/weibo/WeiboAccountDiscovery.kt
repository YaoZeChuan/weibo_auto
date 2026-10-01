package cn.vove7.weibo.auto.domain.weibo

import android.content.Context
import android.content.Intent
import cn.vove7.andro_accessibility_api.AccessibilityApi
import cn.vove7.auto.core.api.buildLayoutInfo
import cn.vove7.auto.core.api.containsText
import cn.vove7.auto.core.api.waitForApp
import cn.vove7.auto.core.api.withDesc
import cn.vove7.auto.core.api.withId
import cn.vove7.auto.core.api.withText
import cn.vove7.auto.core.utils.ViewNodeNotFoundException
import cn.vove7.auto.core.viewfinder.ScreenTextFinder
import cn.vove7.auto.core.viewfinder.ViewFinder
import cn.vove7.auto.core.viewnode.ViewNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * 通过无障碍导航微博并抓取「账号管理」页已登录账号。
 *
 * 路径：打开微博 → 底部「我」→ 右上角设置 → 「账号管理」→ 解析列表。
 */
class WeiboAccountDiscovery {

    suspend fun discover(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): List<DiscoveredAccount> = withContext(Dispatchers.Default) {
        AccessibilityApi.requireBaseAccessibility(autoJump = false)
        var openedWeibo = false
        try {
            // 统一：不在首页则先回首页（与启动任务一致）
            onProgress("检查是否在首页…")
            val nav = WeiboNavigator()
            nav.ensureWeiboHomeAtStart(context, onProgress)
            openedWeibo = true
            delay(600)

            onProgress("进入「我」…")
            clickMeTab()
            delay(1_500)

            onProgress("打开设置…")
            clickSettings()
            delay(1_200)

            onProgress("进入账号管理…")
            clickAccountManage()
            delay(1_500)

            onProgress("解析账号列表…")
            val accounts = parseAccountManagePage(onProgress)
            if (accounts.isEmpty()) {
                dumpLayoutForDebug(tag = "empty_accounts")
                error("未解析到任何账号，请确认账号管理页已打开且有登录账号")
            }

            onProgress("已发现 ${accounts.size} 个账号")
            Timber.i("discovered accounts: $accounts")
            accounts
        } finally {
            // 任务结束：回微博首页，再切回本助手（不强杀微博）
            if (openedWeibo) {
                runCatching {
                    WeiboAppController.finishAndReturn(context, onProgress)
                }.onFailure {
                    Timber.w(it, "finishAndReturn after discover failed")
                }
            }
        }
    }

    private fun openWeibo(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(WeiboConsts.PACKAGE)
            ?: error("未安装微博（${WeiboConsts.PACKAGE}）")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        context.startActivity(launch)
    }

    /**
     * 底部导航「我」：只点 desc=我 的 Tab（main_radio 最右）。
     * 禁止 withText("我")，否则会点到「我的相册」。
     */
    private suspend fun clickMeTab() {
        val bottomThreshold = (screenHeight() * 0.72f).toInt()
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            val tabs = withDesc("我").findAll()
                .filter {
                    val b = it.bounds
                    b.centerY() >= bottomThreshold && b.width() > 0 && b.height() > 0
                }
                .sortedByDescending { it.bounds.centerX() } // 最右
            Timber.i(
                "clickMeTab desc candidates=${tabs.size} " +
                    tabs.take(3).joinToString { "c=(${it.bounds.centerX()},${it.bounds.centerY()})" }
            )
            val tab = tabs.firstOrNull()
            if (tab != null) {
                Timber.i("clickMeTab -> $tab")
                if (safeClick(tab)) return
                runCatching { tab.globalClick() }.onSuccess { if (it) return }
            }
            delay(400)
        }
        dumpLayoutForDebug(tag = "me_tab_not_found")
        throw ViewNodeNotFoundException("找不到底部导航「我」(desc)，勿点内容区文字")
    }

    /** 右上角设置：多策略查找 */
    private suspend fun clickSettings() {
        val node = findSettingsNode()
            ?: run {
                dumpLayoutForDebug(tag = "settings_not_found")
                dumpTopBarCandidates()
                throw ViewNodeNotFoundException(
                    "找不到右上角「设置」。请把 logcat 中 tag=WeiboDump 的日志发我"
                )
            }
        Timber.i("clickSettings -> $node")
        if (!safeClick(node)) {
            dumpLayoutForDebug(tag = "settings_click_failed")
            error("点击「设置」失败")
        }
    }

    private suspend fun findSettingsNode(): ViewNode? {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            // 1) 文案/描述含「设置」
            findTopRightByLabels(listOf("设置", "Settings", "设置页", "setting"))
                ?.let { return it }

            // 2) 常见 id（不同版本可能不同，尽量覆盖）
            val idHints = listOf(
                "titleSave",
                "titleRight",
                "rightBtn",
                "right_btn",
                "title_right",
                "iv_setting",
                "setting",
                "settings",
                "btn_setting",
                "navigation_right_btn",
                "titlebar_right",
            )
            for (id in idHints) {
                withId(id).findFirst()?.let { node ->
                    if (isInTopBar(node.bounds.centerY())) {
                        Timber.i("settings by id=$id -> $node")
                        return node
                    }
                }
            }

            // 3) 顶部栏最右侧可点击控件（齿轮图标通常没有文字）
            findTopRightMostClickable()?.let {
                Timber.i("settings by top-right clickable -> $it")
                return it
            }

            delay(400)
        }
        return null
    }

    private suspend fun findTopRightByLabels(labels: List<String>): ViewNode? {
        val screenW = screenWidth()
        val rightThreshold = (screenW * 0.45f).toInt()
        val nodes = collectNodesByLabels(labels)
        return nodes
            .filter { isInTopBar(it.bounds.centerY()) && it.bounds.centerX() >= rightThreshold }
            .maxByOrNull { it.bounds.centerX() }
    }

    /**
     * 在顶部约 18% 高度内，取最靠右的可点击节点（过滤过大的容器）。
     */
    private fun findTopRightMostClickable(): ViewNode? {
        val screenW = screenWidth()
        val screenH = screenHeight()
        val topLimit = (screenH * 0.18f).toInt()
        val minX = (screenW * 0.65f).toInt()
        val maxSize = (screenW * 0.25f).toInt() // 排除整条 title bar
        val candidates = mutableListOf<ViewNode>()
        collectClickableInRegion(
            node = ViewNode.getRoot(),
            out = candidates,
            maxBottom = topLimit,
            minCenterX = minX,
            maxWidth = maxSize,
            maxHeight = maxSize,
        )
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
        } catch (e: Throwable) {
            Timber.w(e, "collectClickableInRegion")
        }
    }

    private fun isInTopBar(centerY: Int): Boolean {
        val topLimit = (screenHeight() * 0.22f).toInt()
        return centerY <= topLimit
    }

    private suspend fun clickAccountManage() {
        val node = findClickableByTextsOrDescs(
            listOf("账号管理", "帐号管理"),
            timeoutMs = 12_000,
        ) ?: run {
            dumpLayoutForDebug(tag = "account_manage_not_found")
            throw ViewNodeNotFoundException("找不到「账号管理」")
        }
        if (!safeClick(node)) {
            error("点击「账号管理」失败")
        }
        val title = withText("账号管理", "帐号管理").waitFor(8_000)
            ?: containsText("账号管理", "帐号管理").waitFor(3_000)
        if (title == null) {
            Timber.w("账号管理标题未出现，继续尝试解析页面")
        }
    }

    private suspend fun findBestBottomTab(labels: List<String>): ViewNode? {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            val screenH = screenHeight()
            val bottomThreshold = (screenH * 0.75f).toInt()
            val nodes = collectNodesByLabels(labels)
            val bottom = nodes
                .filter { it.bounds.centerY() >= bottomThreshold }
                .maxByOrNull { it.bounds.centerX() } // 偏右
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

    private suspend fun safeClick(node: ViewNode): Boolean {
        if (node.tryClick()) return true
        return try {
            node.globalClick()
        } catch (e: Throwable) {
            Timber.w(e, "globalClick failed")
            false
        }
    }

    /**
     * 解析「账号管理」列表。
     *
     * 账号列表是 RecyclerView：无障碍树里只有**屏幕上已渲染**的行（ViewFinder 会跳过
     * 不可见节点），一屏通常只放得下 10 行左右。所以必须边下滑边收集，
     * 否则登录了 13 个号也只会解析到第一屏的 10 个。
     */
    private suspend fun parseAccountManagePage(
        onProgress: (String) -> Unit = {},
    ): List<DiscoveredAccount> {
        val names = linkedSetOf<String>()
        var previousPage: Set<String> = emptySet()
        var unchangedRounds = 0
        for (round in 1..MAX_LIST_SWIPES + 1) {
            delay(if (round == 1) 600 else 900)
            val page = collectVisibleAccountNames()
            if (page.isEmpty() && names.isEmpty() && round == 1) {
                // 保持旧行为：严格过滤一屏都没结果时，放宽条件再扫一次
                collectLooseAccountNames(names)
            }
            names += page
            onProgress("解析账号列表…已发现 ${names.size} 个（第 $round 屏）")
            Timber.i("account page round=$round visible=${page.size} total=${names.size}")

            unchangedRounds = if (page.isNotEmpty() && page == previousPage) {
                unchangedRounds + 1
            } else {
                0
            }
            previousPage = page
            if (unchangedRounds >= 2) {
                Timber.i("账号列表连续 $unchangedRounds 屏无变化，已到底")
                break
            }
            if (round > MAX_LIST_SWIPES) break
            if (!scrollAccountListForward()) {
                Timber.i("账号列表无法继续滚动，停在第 $round 屏")
                break
            }
        }

        Timber.i("parsed account names after filter: $names")
        return names.map { name ->
            DiscoveredAccount(
                uid = stableUid(name),
                name = name,
            )
        }
    }

    /** 收集当前屏幕上（可见区域）的账号昵称。 */
    private suspend fun collectVisibleAccountNames(): Set<String> {
        val textNodes = ScreenTextFinder().find()
        Timber.d("account page texts: ${textNodes.map { "${it.text}@${it.bounds}" }}")

        // 排除状态栏 / 标题栏 / 底部导航；账号列表一般在中间区域
        val topExclude = accountBandTop() // 状态栏+标题
        val bottomExclude = accountBandBottom()

        val names = linkedSetOf<String>()
        for (node in textNodes) {
            val raw = node.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) continue
            // 状态栏/系统 UI 不属于微博
            val pkg = node.packageName
            if (pkg != null && pkg != WeiboConsts.PACKAGE) {
                Timber.d("skip non-weibo pkg=$pkg text=$raw")
                continue
            }
            val cy = node.bounds.centerY()
            // 状态栏时间、网速几乎都在顶部
            if (cy < topExclude) {
                Timber.d("skip top-bar text: $raw bounds=${node.bounds}")
                continue
            }
            if (cy > bottomExclude) {
                Timber.d("skip bottom text: $raw")
                continue
            }
            if (isIgnoredUiText(raw)) continue
            if (looksLikeAccountName(raw)) {
                names += raw
            }
        }

        if (names.isEmpty()) {
            collectLooseAccountNames(names)
        }
        return names
    }

    /** 兜底：整屏文案里筛出疑似昵称（严格过滤无结果时使用，保持旧行为）。 */
    private fun collectLooseAccountNames(out: MutableSet<String>) {
        collectAccountLikeTexts(
            node = ViewNode.getRoot(),
            topExclude = accountBandTop(),
            bottomExclude = accountBandBottom(),
        ).forEach { t ->
            if (!isIgnoredUiText(t) && looksLikeAccountName(t)) out += t
        }
    }

    /** 账号列表可见区域上边界（状态栏 + 标题栏）。 */
    private fun accountBandTop(): Int = (screenHeight() * 0.12f).toInt().coerceAtLeast(120)

    /** 账号列表可见区域下边界（底部导航）。 */
    private fun accountBandBottom(): Int = (screenHeight() * 0.88f).toInt()

    /**
     * 账号列表下滑一屏。
     *
     * 优先对列表容器执行无障碍 ACTION_SCROLL_FORWARD（RecyclerView 支持），
     * 失败则用坐标滑动兜底。
     *
     * @return true 表示滚动已触发（可继续解析下一屏）
     */
    private suspend fun scrollAccountListForward(): Boolean {
        val list = findScrollableList()
        if (list != null) {
            val scrolled = runCatching { list.scrollForward() }.getOrDefault(false)
            Timber.i("scrollAccountListForward by node -> $scrolled, node=$list")
            if (scrolled) {
                delay(700)
                return true
            }
        }
        val midX = screenWidth() / 2
        val h = screenHeight()
        val yStart = (h * 0.72f).toInt()
        val yEnd = (h * 0.40f).toInt()
        val ok = runCatching {
            cn.vove7.auto.core.api.swipe(midX, yStart, midX, yEnd, 600)
        }.getOrDefault(false)
        Timber.i("scrollAccountListForward by swipe ($midX,$yStart)->($midX,$yEnd) -> $ok")
        if (ok) delay(700)
        return ok
    }

    /** 找到账号列表所在的最内层可滚动容器（RecyclerView / ListView / ScrollView）。 */
    private fun findScrollableList(): ViewNode? {
        val screenW = screenWidth()
        val screenH = screenHeight()
        val minWidth = (screenW * 0.6f).toInt()
        val minHeight = (screenH * 0.25f).toInt()
        val candidates = mutableListOf<ViewNode>()

        fun walk(node: ViewNode?, depth: Int) {
            // 微博页面树较深，沿用 ViewFinder 的深度上限
            if (node == null || depth > ViewFinder.MAX_TRAVERSE_DEPTH) return
            try {
                val b = node.bounds
                val pkg = node.packageName
                if (node.node.isScrollable &&
                    (pkg == null || pkg == WeiboConsts.PACKAGE) &&
                    b.width() >= minWidth &&
                    b.height() >= minHeight
                ) {
                    candidates += node
                }
            } catch (e: Throwable) {
                Timber.w(e, "findScrollableList walk")
                return
            }
            for (i in 0 until node.childCount) {
                walk(node.childAt(i), depth + 1)
            }
        }

        walk(ViewNode.getRoot(), 0)
        // 最矮（最内层）的可滚动容器就是账号列表
        return candidates.minByOrNull { it.bounds.height() }
    }

    private fun collectAccountLikeTexts(
        node: ViewNode,
        topExclude: Int,
        bottomExclude: Int,
        out: MutableList<String> = mutableListOf(),
    ): List<String> {
        try {
            val text = node.text?.toString()?.trim().orEmpty()
            val cy = node.bounds.centerY()
            if (text.isNotEmpty() &&
                cy in topExclude..bottomExclude &&
                (node.isClickable() || !node.text.isNullOrBlank())
            ) {
                out += text
            }
            for (i in 0 until node.childCount) {
                node.childAt(i)?.let {
                    collectAccountLikeTexts(it, topExclude, bottomExclude, out)
                }
            }
        } catch (e: Throwable) {
            Timber.w(e, "collectAccountLikeTexts")
        }
        return out
    }

    /**
     * 页面按钮、状态栏、系统装饰等，不应作为账号昵称。
     */
    private fun isIgnoredUiText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length <= 1) return true

        val chrome = setOf(
            "账号管理", "帐号管理", "添加账号", "添加帐号", "设置", "完成", "编辑",
            "管理", "当前使用", "当前账号", "切换账号", "切换帐号", "退出登录", "登录", "注册",
            "退出当前账号", "免密登录", "一键登录", "登录设备管理", "管理登录设备", "已登录设备",
            "账号与安全", "帐号与安全",
            "我", "我的", "首页", "发现", "消息", "视频", "微博", "搜索",
            "确定", "取消", "返回", "更多", "帮助", "关于", "隐私",
            "当前登录", "使用中", "正在使用",
        )
        if (t in chrome) return true
        if (t.contains("点击") || t.contains("登录后") || t.contains("未登录")) return true

        // 状态栏时间：晚上10:27 / 上午9:05 / 10:27 / 22:27
        if (TIME_TEXT.matches(t)) return true
        // 网速：4.9K/s  1.2MB/s  128KB/s  3.5k/s
        if (NET_SPEED_TEXT.matches(t)) return true
        // 纯分隔符 / 管道
        if (t.all { it == '|' || it == '·' || it == '•' || it == '-' || it.isWhitespace() }) return true
        // 电量百分比 100 / 100%
        if (BATTERY_TEXT.matches(t)) return true
        // 纯数字或带单位的计数
        if (NUMBERISH.matches(t)) return true

        return false
    }

    private fun looksLikeAccountName(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..30) return false
        if (isIgnoredUiText(t)) return false
        // 必须至少包含一个字母或中日韩字符，避免 "4.9K/s" 漏网
        if (!HAS_LETTER_OR_CJK.containsMatchIn(t)) return false
        return true
    }

    companion object {
        /** 账号列表最多下滑次数（1 屏首屏 + N 次下滑；13 个账号约 3~4 次足够） */
        private const val MAX_LIST_SWIPES = 12

        private val TIME_TEXT = Regex(
            """^(?:上午|下午|晚上|凌晨|中午)?\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?$"""
        )
        private val NET_SPEED_TEXT = Regex(
            """^\d+(?:\.\d+)?\s*[KkMmGg]i?[Bb]?(?:/s|/秒)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val BATTERY_TEXT = Regex("""^\d{1,3}%?$""")
        private val NUMBERISH = Regex("""^\d+(\.\d+)?([万wW千kKmMgG%])?$""")
        // 字母或中日韩统一表意文字
        private val HAS_LETTER_OR_CJK = Regex("""[A-Za-z一-鿿]""")
    }

    private fun stableUid(name: String): String {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return "name_${normalized.hashCode().toUInt()}"
    }

    private fun screenWidth(): Int =
        AccessibilityApi.requireBase.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int =
        AccessibilityApi.requireBase.resources.displayMetrics.heightPixels

    private fun dumpLayoutForDebug(tag: String) {
        try {
            val texts = runCatching {
                // ScreenTextFinder.find 是 suspend，这里可能在 suspend 上下文外被调用
                // 调用方均为 suspend 函数路径上的 run 块，单独包一层
                null
            }
            Timber.tag("WeiboDump").w("===== dump start tag=$tag =====")
            val layout = buildLayoutInfo(includeInvisible = true)
            // 分段输出，避免 logcat 截断
            layout.chunked(3000).forEachIndexed { index, part ->
                Timber.tag("WeiboDump").w("layout[$tag][$index]:\n$part")
            }
            Timber.tag("WeiboDump").w("===== dump end tag=$tag len=${layout.length} =====")
            Timber.tag("WeiboDump").w("screen=${screenWidth()}x${screenHeight()}")
        } catch (e: Throwable) {
            Timber.tag("WeiboDump").w(e, "dump layout failed tag=$tag")
        }
    }

    private suspend fun dumpTopBarCandidates() {
        try {
            val topLimit = (screenHeight() * 0.22f).toInt()
            val all = mutableListOf<String>()
            fun walk(n: ViewNode?, depth: Int) {
                if (n == null || depth > 12) return
                val b = n.bounds
                if (b.centerY() <= topLimit) {
                    all += "text=${n.text} desc=${n.desc()} id=${n.id} click=${n.isClickable()} " +
                        "class=${n.simpleName} bounds=$b"
                }
                for (i in 0 until n.childCount) walk(n.childAt(i), depth + 1)
            }
            walk(ViewNode.getRoot(), 0)
            Timber.tag("WeiboDump").w("top-bar nodes (${all.size}):")
            all.forEach { Timber.tag("WeiboDump").w("TOP: $it") }
            // 同时打屏幕上所有 text/desc 便于对照
            val texts = ScreenTextFinder().find().map { "text=${it.text} bounds=${it.bounds}" }
            Timber.tag("WeiboDump").w("all texts: $texts")
        } catch (e: Throwable) {
            Timber.tag("WeiboDump").w(e, "dumpTopBarCandidates failed")
        }
    }
}
