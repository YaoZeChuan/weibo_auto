package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.vove7.weibo.auto.data.entity.WeiboAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM weibo_accounts ORDER BY lastRefreshAt DESC")
    fun observeAll(): Flow<List<WeiboAccount>>

    @Query("SELECT * FROM weibo_accounts ORDER BY lastRefreshAt DESC")
    suspend fun getAll(): List<WeiboAccount>

    @Query("SELECT * FROM weibo_accounts WHERE selected = 1")
    suspend fun getSelected(): List<WeiboAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<WeiboAccount>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: WeiboAccount): Long

    @Update
    suspend fun update(account: WeiboAccount)

    @Query("UPDATE weibo_accounts SET selected = :selected WHERE id = :id")
    suspend fun setSelected(id: Long, selected: Boolean)

    @Query("UPDATE weibo_accounts SET selected = :selected")
    suspend fun setAllSelected(selected: Boolean)

    @Query(
        """
        UPDATE weibo_accounts
        SET superLikeLit = :lit,
            superLikeExp = :exp,
            lastCheckAt = :at,
            lastRefreshAt = :at
        WHERE id = :id
        """
    )
    suspend fun updateSuperLike(
        id: Long,
        lit: Boolean,
        exp: Int,
        at: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE weibo_accounts
        SET checkInDays = :days,
            lastRefreshAt = :at
        WHERE id = :id
        """
    )
    suspend fun updateCheckIn(
        id: Long,
        days: Int,
        at: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE weibo_accounts
        SET dailyTaskDayStart = :dayStart,
            dailyCheckInStatus = :checkInStatus,
            dailyBrowseCompletedCount = :browseCompleted,
            dailyBrowseRequiredCount = :browseRequired,
            dailyCommentCompletedCount = :commentCompleted,
            dailyCommentRequiredCount = :commentRequired,
            dailyRepostCompletedCount = :repostCompleted,
            dailyRepostRequiredCount = :repostRequired,
            lastRefreshAt = :at
        WHERE id = :id
        """
    )
    suspend fun updateDailyTaskProgress(
        id: Long,
        dayStart: Long,
        checkInStatus: String,
        browseCompleted: Int,
        browseRequired: Int,
        commentCompleted: Int,
        commentRequired: Int,
        repostCompleted: Int,
        repostRequired: Int,
        at: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE weibo_accounts
        SET lastCheckAt = :at,
            lastRefreshAt = :at
        WHERE id = :id
        """
    )
    suspend fun markCheckedToday(
        id: Long,
        at: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM weibo_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM weibo_accounts")
    suspend fun clear()
}
