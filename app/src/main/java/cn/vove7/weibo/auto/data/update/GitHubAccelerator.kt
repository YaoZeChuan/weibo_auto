package cn.vove7.weibo.auto.data.update

/** Routes GitHub API and GitHub release entry URLs through the configured accelerator. */
object GitHubAccelerator {
    private const val ACCELERATOR_PREFIX = "https://jiasu.yaozc.ccwu.cc/"

    fun accelerate(url: String): String =
        if (url.startsWith(ACCELERATOR_PREFIX)) {
            url
        } else if (url.startsWith("https://github.com/") || url.startsWith("https://api.github.com/")) {
            ACCELERATOR_PREFIX + url
        } else {
            url
        }
}
