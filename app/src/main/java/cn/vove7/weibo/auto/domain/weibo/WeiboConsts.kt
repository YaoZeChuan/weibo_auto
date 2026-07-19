package cn.vove7.weibo.auto.domain.weibo

object WeiboConsts {
    const val PACKAGE = "com.sina.weibo"

    /**
     * 超 LIKE 相关 cardlist containerid。
     * 实机 adb 验证可用：
     * adb shell am start -a android.intent.action.VIEW \
     *   -d "sinaweibo://cardlist?containerid=..._-_profile_allbadge" \
     *   -n com.sina.weibo/.page.NewCardListActivity
     */
    const val SUPER_LIKE_CONTAINER_ID =
        "23114044cc042c1e6385c391f94e8094939df5_-_profile_allbadge"

    const val SUPER_LIKE_ACTIVITY = "com.sina.weibo.page.NewCardListActivity"

    /**
     * 近7天经验值阈值：页面文案为「头衔要求：近7天获取经验值≥80」
     * exp >= 该值视为已点亮
     */
    const val SUPER_LIKE_LIT_THRESHOLD = 80

    /**
     * 超话页 pageinfo containerid。
     * 对应：
     * sinaweibo://pageinfo?containerid=...
     * component: com.sina.weibo/.supergroup.SGPageActivity
     */
    const val SUPER_TOPIC_CONTAINER_ID =
        "10080844cc042c1e6385c391f94e8094939df5"

    const val SUPER_TOPIC_ACTIVITY = "com.sina.weibo.supergroup.SGPageActivity"

    /** 超 LIKE / 日常任务目标超话名称 */
    const val TARGET_SUPER_TOPIC_NAME = "赵今麦"

    /** 浏览前用于确认未偏离目标超话的页面标题。 */
    const val TARGET_SUPER_TOPIC_PAGE_MARKER = "赵今麦超话"
    const val SUPER_TOPIC_TITLE = "tvTitle"
    const val SUPER_TOPIC_TITLE_FULL = "com.sina.weibo:id/tvTitle"

    /** 每个账号在本地自然日内允许成功发送的评论上限。 */
    const val DAILY_COMMENT_LIMIT = 4

    /** 浏览任务时长（毫秒），约 2 分钟 */
    const val BROWSE_DURATION_MS = 120_000L

    /** 浏览时每次滑动后最少停留（毫秒） */
    const val BROWSE_STAY_MS = 6_000L

    /** 签到成功弹窗中的成功文案。 */
    const val CHECK_IN_SUCCESS_REMINDER_TEXT = "接收本超话签到提醒推送"

    /**
     * 首页左上角「超话」入口（UI Automator / dump 实测）：
     * - TextView id: home_bar_left_tv1
     * - 父布局 id: home_bar_left_layout
     * class: android.widget.TextView / RelativeLayout
     */
    const val HOME_BAR_LEFT_TV1 = "home_bar_left_tv1"
    const val HOME_BAR_LEFT_TV1_FULL = "com.sina.weibo:id/home_bar_left_tv1"
    const val HOME_BAR_LEFT_LAYOUT = "home_bar_left_layout"
    const val HOME_BAR_LAYOUT = "home_bar_layout"

    /**
     * 「我」页「超话社区」标题（UI Automator 实测）：
     * resource-id = com.sina.weibo:id/title_title_left
     * text = 超话社区
     * 节点本身可能 not clickable，需点父容器
     */
    const val TITLE_TITLE_LEFT = "title_title_left"
    const val TITLE_TITLE_LEFT_FULL = "com.sina.weibo:id/title_title_left"

    /**
     * 发帖页「同步到微博」勾选框（UI Automator 实测）：
     * resource-id = com.sina.weibo:id/checkbox
     * class = android.widget.CheckBox
     * checked=false 表示已取消同步，可直接发送
     */
    const val POST_SYNC_CHECKBOX = "checkbox"
    const val POST_SYNC_CHECKBOX_FULL = "com.sina.weibo:id/checkbox"

    /**
     * 超话详情右上角签到容器（dump 实测）：
     * FrameLayout id=right_button → Button text=签到
     */
    const val SUPER_TOPIC_RIGHT_BUTTON = "right_button"
    const val SUPER_TOPIC_RIGHT_BUTTON_FULL = "com.sina.weibo:id/right_button"
}
