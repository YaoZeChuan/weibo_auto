package cn.vove7.weibo.auto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.vove7.weibo.auto.data.entity.PostTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface PostTemplateDao {

    @Query("SELECT * FROM post_templates ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PostTemplate>>

    @Query("SELECT * FROM post_templates ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PostTemplate>

    @Query("SELECT * FROM post_templates WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): PostTemplate?

    @Query("SELECT * FROM post_templates ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): PostTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: PostTemplate): Long

    @Update
    suspend fun update(template: PostTemplate)

    @Query("UPDATE post_templates SET selected = 0")
    suspend fun clearSelected()

    @Query("UPDATE post_templates SET selected = 1, updatedAt = :at WHERE id = :id")
    suspend fun setSelected(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM post_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
