package cn.vove7.weibo.auto.data.repo

import android.content.Context
import cn.vove7.weibo.auto.data.dao.AccountDao
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import cn.vove7.weibo.auto.domain.weibo.DiscoveredAccount
import cn.vove7.weibo.auto.domain.weibo.SuperLikeChecker
import cn.vove7.weibo.auto.domain.weibo.SuperLikeCheckResult
import cn.vove7.weibo.auto.domain.weibo.WeiboAccountDiscovery
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

class AccountRepository(
    private val appContext: Context,
    private val accountDao: AccountDao,
    private val discovery: WeiboAccountDiscovery = WeiboAccountDiscovery(),
    private val superLikeChecker: SuperLikeChecker = SuperLikeChecker(),
) {
    fun observeAccounts(): Flow<List<WeiboAccount>> = accountDao.observeAll()

    suspend fun getAllAccounts(): List<WeiboAccount> = accountDao.getAll()

    suspend fun getSelectedAccounts(): List<WeiboAccount> = accountDao.getSelected()

    /**
     * 无障碍打开微博 → 我 → 设置 → 账号管理，抓取已登录账号并写入 Room。
     */
    suspend fun refreshAccounts(onProgress: (String) -> Unit = {}) {
        val discovered = discovery.discover(appContext, onProgress)
        onProgress("正在保存到本地…")
        syncDiscovered(discovered)
    }

    /**
     * 将发现结果同步到本地：同 uid/昵称保留 selected、superLike 状态。
     */
    suspend fun syncDiscovered(discovered: List<DiscoveredAccount>) {
        val now = System.currentTimeMillis()
        val existing = accountDao.getAll()
        val byUid = existing.associateBy { it.uid }
        val byName = existing.associateBy { it.name }

        val toSave = discovered.map { item ->
            val old = byUid[item.uid] ?: byName[item.name]
            WeiboAccount(
                id = old?.id ?: 0,
                uid = item.uid,
                name = item.name,
                avatarUrl = item.avatarUrl ?: old?.avatarUrl,
                superLikeLit = old?.superLikeLit ?: false,
                superLikeExp = old?.superLikeExp ?: -1,
                checkInDays = old?.checkInDays ?: -1,
                dailyTaskDayStart = old?.dailyTaskDayStart ?: 0L,
                dailyCheckInStatus = old?.dailyCheckInStatus ?: "UNKNOWN",
                dailyBrowseCompletedCount = old?.dailyBrowseCompletedCount ?: -1,
                dailyBrowseRequiredCount = old?.dailyBrowseRequiredCount ?: -1,
                dailyCommentCompletedCount = old?.dailyCommentCompletedCount ?: -1,
                dailyCommentRequiredCount = old?.dailyCommentRequiredCount ?: -1,
                dailyRepostCompletedCount = old?.dailyRepostCompletedCount ?: -1,
                dailyRepostRequiredCount = old?.dailyRepostRequiredCount ?: -1,
                lastCheckAt = old?.lastCheckAt ?: 0L,
                lastRefreshAt = now,
                selected = old?.selected ?: false,
            )
        }
        accountDao.clear()
        if (toSave.isNotEmpty()) {
            accountDao.upsertAll(toSave)
        }
        Timber.i("synced ${toSave.size} accounts")
    }

    /**
     * 检测超 like。
     * @param onlySelected true 时只测已勾选；若没有勾选则测全部。
     */
    suspend fun checkSuperLikeStatus(
        onlySelected: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): List<SuperLikeCheckResult> {
        val selected = accountDao.getSelected()
        val targets = when {
            onlySelected && selected.isNotEmpty() -> selected
            else -> accountDao.getAll()
        }
        if (targets.isEmpty()) {
            onProgress("本地没有账号，请先点「刷新」")
            return emptyList()
        }
        return superLikeChecker.checkAccounts(
            context = appContext,
            accounts = targets,
            onProgress = onProgress,
            onAccountResult = { result ->
                if (result.exp != null) {
                    accountDao.updateSuperLike(
                        id = result.accountId,
                        lit = result.lit,
                        exp = result.exp,
                    )
                    Timber.i(
                        "db updateSuperLike id=${result.accountId} name=${result.accountName} " +
                            "exp=${result.exp} lit=${result.lit}"
                    )
                } else {
                    // 失败时不改 exp，只记日志
                    Timber.w("skip db update for ${result.accountName}: ${result.message}")
                }
            },
            onCheckInDays = { accountId, days ->
                accountDao.updateCheckIn(accountId, days)
                Timber.i("db updateCheckIn(from superlike path) id=$accountId days=$days")
            },
        )
    }

    suspend fun setSelected(id: Long, selected: Boolean) {
        accountDao.setSelected(id, selected)
    }

    suspend fun setAllSelected(selected: Boolean) {
        accountDao.setAllSelected(selected)
    }

    suspend fun updateCheckInDays(id: Long, days: Int) {
        accountDao.updateCheckIn(id, days)
        Timber.i("db updateCheckIn id=$id days=$days")
    }

    suspend fun updateDailyTaskProgress(
        accountId: Long,
        progress: cn.vove7.weibo.auto.domain.weibo.WeiboNavigator.DailyTaskProgress,
        now: Long = System.currentTimeMillis(),
    ) {
        accountDao.updateDailyTaskProgress(
            id = accountId,
            dayStart = localDayStartMillis(now),
            checkInStatus = progress.checkInStatus,
            browseCompleted = progress.browse.completedCount,
            browseRequired = progress.browse.requiredCount,
            commentCompleted = progress.comment.completedCount,
            commentRequired = progress.comment.requiredCount,
            repostCompleted = progress.repost.completedCount,
            repostRequired = progress.repost.requiredCount,
            at = now,
        )
        Timber.i("db updateDailyTaskProgress id=$accountId progress=$progress")
    }

    suspend fun applySuperLikeResult(id: Long, lit: Boolean, exp: Int) {
        accountDao.updateSuperLike(id = id, lit = lit, exp = exp)
        Timber.i("db applySuperLikeResult id=$id exp=$exp lit=$lit")
    }

    suspend fun deleteAccount(id: Long) {
        accountDao.deleteById(id)
    }

    private fun localDayStartMillis(now: Long): Long = java.util.Calendar.getInstance().run {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        timeInMillis
    }
}
