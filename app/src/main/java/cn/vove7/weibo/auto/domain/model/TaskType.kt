package cn.vove7.weibo.auto.domain.model

/**
 * 启动任务可选项。
 * 签到不再作为独立任务：进入超话后若未签到会自动签到。
 */
enum class TaskType(val label: String) {
    POST("发帖"),
    BROWSE("浏览"),
    SUPER_LIKE("检测超like"),
}
