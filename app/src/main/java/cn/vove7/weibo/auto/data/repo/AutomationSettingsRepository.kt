package cn.vove7.weibo.auto.data.repo

import android.content.Context
import cn.vove7.weibo.auto.domain.weibo.WeiboConsts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutomationSettings(
    val dailyCommentLimit: Int,
    val waterPostCount: Int,
    val browseStaySeconds: Int,
    val browseSwipeCount: Int,
)

class AutomationSettingsRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _settings = MutableStateFlow(readSettings())
    val settings = _settings.asStateFlow()

    fun update(settings: AutomationSettings) {
        require(settings.dailyCommentLimit in MIN_DAILY_COMMENT_LIMIT..MAX_DAILY_COMMENT_LIMIT)
        require(settings.waterPostCount in MIN_WATER_POST_COUNT..MAX_WATER_POST_COUNT)
        require(settings.browseStaySeconds in MIN_BROWSE_STAY_SECONDS..MAX_BROWSE_STAY_SECONDS)
        require(settings.browseSwipeCount in MIN_BROWSE_SWIPE_COUNT..MAX_BROWSE_SWIPE_COUNT)
        preferences.edit()
            .putInt(KEY_DAILY_COMMENT_LIMIT, settings.dailyCommentLimit)
            .putInt(KEY_WATER_POST_COUNT, settings.waterPostCount)
            .putInt(KEY_BROWSE_STAY_SECONDS, settings.browseStaySeconds)
            .putInt(KEY_BROWSE_SWIPE_COUNT, settings.browseSwipeCount)
            .apply()
        _settings.value = settings
    }

    private fun readSettings() = AutomationSettings(
        dailyCommentLimit = preferences.getInt(
            KEY_DAILY_COMMENT_LIMIT,
            WeiboConsts.DAILY_COMMENT_LIMIT,
        ).coerceIn(MIN_DAILY_COMMENT_LIMIT, MAX_DAILY_COMMENT_LIMIT),
        waterPostCount = preferences.getInt(
            KEY_WATER_POST_COUNT,
            DEFAULT_WATER_POST_COUNT,
        ).coerceIn(MIN_WATER_POST_COUNT, MAX_WATER_POST_COUNT),
        browseStaySeconds = preferences.getInt(
            KEY_BROWSE_STAY_SECONDS,
            (WeiboConsts.BROWSE_STAY_MS / 1_000).toInt(),
        ).coerceIn(MIN_BROWSE_STAY_SECONDS, MAX_BROWSE_STAY_SECONDS),
        browseSwipeCount = preferences.getInt(
            KEY_BROWSE_SWIPE_COUNT,
            DEFAULT_BROWSE_SWIPE_COUNT,
        ).coerceIn(MIN_BROWSE_SWIPE_COUNT, MAX_BROWSE_SWIPE_COUNT),
    )

    companion object {
        const val MIN_DAILY_COMMENT_LIMIT = 0
        const val MAX_DAILY_COMMENT_LIMIT = 99
        const val MIN_WATER_POST_COUNT = 1
        const val MAX_WATER_POST_COUNT = 99
        const val MIN_BROWSE_STAY_SECONDS = 1
        const val MAX_BROWSE_STAY_SECONDS = 30
        const val MIN_BROWSE_SWIPE_COUNT = 1
        const val MAX_BROWSE_SWIPE_COUNT = 200
        const val DEFAULT_WATER_POST_COUNT = 5
        const val DEFAULT_BROWSE_SWIPE_COUNT = 40

        fun defaultSettings() = AutomationSettings(
            dailyCommentLimit = WeiboConsts.DAILY_COMMENT_LIMIT,
            waterPostCount = DEFAULT_WATER_POST_COUNT,
            browseStaySeconds = (WeiboConsts.BROWSE_STAY_MS / 1_000).toInt(),
            browseSwipeCount = DEFAULT_BROWSE_SWIPE_COUNT,
        )

        private const val PREFERENCES_NAME = "automation_settings"
        private const val KEY_DAILY_COMMENT_LIMIT = "daily_comment_limit"
        private const val KEY_WATER_POST_COUNT = "water_post_count"
        private const val KEY_BROWSE_STAY_SECONDS = "browse_stay_seconds"
        private const val KEY_BROWSE_SWIPE_COUNT = "browse_swipe_count"
    }
}
