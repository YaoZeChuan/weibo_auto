package cn.vove7.weibo.auto.data.repo

import cn.vove7.weibo.auto.data.dao.PostTemplateDao
import cn.vove7.weibo.auto.data.entity.PostTemplate
import kotlinx.coroutines.flow.Flow

class PostTemplateRepository(
    private val dao: PostTemplateDao,
) {
    fun observeAll(): Flow<List<PostTemplate>> = dao.observeAll()

    suspend fun getAll(): List<PostTemplate> = dao.getAll()

    /** 当前用于发帖的文案：优先 selected，否则最新一条 */
    suspend fun getRandomContent(): String? = dao.getAll().randomOrNull()?.content

    suspend fun add(content: String, select: Boolean = false): Long {
        val text = content.trim()
        require(text.isNotEmpty()) { "发帖内容不能为空" }
        return dao.insert(
            PostTemplate(
                content = text,
                selected = select,
            )
        )
    }

    suspend fun update(id: Long, content: String) {
        val text = content.trim()
        require(text.isNotEmpty()) { "发帖内容不能为空" }
        val all = dao.getAll()
        val old = all.firstOrNull { it.id == id } ?: return
        dao.update(old.copy(content = text, updatedAt = System.currentTimeMillis()))
    }

    suspend fun select(id: Long) {
        dao.clearSelected()
        dao.setSelected(id)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    /** 首次启动时若无模板，写入默认文案 */
    suspend fun ensureDefault() {
        if (dao.getAll().isNotEmpty()) return
        add(
            content = "今日打卡，继续加油～ #赵今麦[超话]#",
            select = false,
        )
    }
}
