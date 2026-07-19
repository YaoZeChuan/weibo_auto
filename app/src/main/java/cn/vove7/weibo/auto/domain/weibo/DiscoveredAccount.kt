package cn.vove7.weibo.auto.domain.weibo

/**
 * 从微博「账号管理」页解析出的账号。
 * 若页面未暴露真实 uid，则用稳定伪 uid（基于昵称）。
 */
data class DiscoveredAccount(
    val uid: String,
    val name: String,
    val avatarUrl: String? = null,
)
