package cn.vove7.weibo.auto.data.repo

import cn.vove7.weibo.auto.data.dao.CommentTemplateDao
import cn.vove7.weibo.auto.data.entity.CommentTemplate
import kotlinx.coroutines.flow.Flow

class CommentTemplateRepository(
    private val dao: CommentTemplateDao,
) {
    fun observeAll(): Flow<List<CommentTemplate>> = dao.observeAll()

    suspend fun getAll(): List<CommentTemplate> = dao.getAll()

    suspend fun getRandomContent(): String? = dao.getAll().randomOrNull()?.content

    suspend fun add(content: String, select: Boolean = false): Long {
        val text = content.trim()
        require(text.isNotEmpty()) { "评论内容不能为空" }
        return dao.insert(
            CommentTemplate(
                content = text,
                selected = select,
            )
        )
    }

    suspend fun select(id: Long) {
        dao.clearSelected()
        dao.setSelected(id)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun ensureDefault() {
        if (dao.getAll().isNotEmpty()) return
        add(
            content = "支持！一起加油～",
            select = false,
        )
    }
}
