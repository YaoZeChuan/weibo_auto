package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "weibo_accounts",
    indices = [Index(value = ["uid"], unique = true)]
)
data class WeiboAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val name: String,
    val avatarUrl: String? = null,
    /** 超 like 是否点亮（近7天经验值 >= 阈值，默认 80） */
    val superLikeLit: Boolean = false,
    /** 超 like 近7天经验值；-1 表示尚未检测 */
    val superLikeExp: Int = -1,
    /** 超话连签天数；-1 表示尚未签到检测 */
    val checkInDays: Int = -1,
    /**
     * 最近一次「超like/日常检测」完成时间戳；0 表示从未检测。
     * UI 用日历日判断「当天是否已检测」。
     */
    val lastCheckAt: Long = 0L,
    val lastRefreshAt: Long = System.currentTimeMillis(),
    val selected: Boolean = false,
) {
    /** 是否在今天（本地时区）完成过检测 */
    fun isCheckedToday(now: Long = System.currentTimeMillis()): Boolean {
        if (lastCheckAt <= 0L) return false
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val y = cal.get(java.util.Calendar.YEAR)
        val d = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = lastCheckAt
        return cal.get(java.util.Calendar.YEAR) == y &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == d
    }
}
