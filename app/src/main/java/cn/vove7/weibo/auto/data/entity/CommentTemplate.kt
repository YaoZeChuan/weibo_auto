package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 浏览任务长按评论时使用的预制文案。
 * 使用 [selected]=true 的一条；若无选中则用最新一条。
 */
@Entity(tableName = "comment_templates")
data class CommentTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val selected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
