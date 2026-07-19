package cn.vove7.weibo.auto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 发帖预制文案。任务发帖时使用 [selected]=true 的一条；若无选中则用最新一条。
 */
@Entity(tableName = "post_templates")
data class PostTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    /** 是否作为当前发帖默认模板 */
    val selected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
