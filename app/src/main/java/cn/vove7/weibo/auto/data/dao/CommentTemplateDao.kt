package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.vove7.weibo.auto.data.entity.CommentTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentTemplateDao {

    @Query("SELECT * FROM comment_templates ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CommentTemplate>>

    @Query("SELECT * FROM comment_templates ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CommentTemplate>

    @Query("SELECT * FROM comment_templates WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): CommentTemplate?

    @Query("SELECT * FROM comment_templates ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): CommentTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: CommentTemplate): Long

    @Update
    suspend fun update(template: CommentTemplate)

    @Query("UPDATE comment_templates SET selected = 0")
    suspend fun clearSelected()

    @Query("UPDATE comment_templates SET selected = 1, updatedAt = :at WHERE id = :id")
    suspend fun setSelected(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM comment_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
