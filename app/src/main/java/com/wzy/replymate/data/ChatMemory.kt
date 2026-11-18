package com.wzy.replymate.data

import com.wzy.replymate.data.db.AppDatabase
import com.wzy.replymate.data.db.ChatMemoryEntity
import com.wzy.replymate.data.db.ChatTurn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 聊天记忆仓库。
 * 一个「标题 + 包名」对应一份记忆：微信里张三和飞书里张三不会串；
 * 同一个人下次打开自动接上，不用手动选。
 */
class ChatMemoryRepo(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val turnsSerializer = ListSerializer(ChatTurn.serializer())

    suspend fun all(): List<ChatMemoryEntity> = db.chatMemoryDao().all()

    suspend fun byId(id: Long): ChatMemoryEntity? = db.chatMemoryDao().byId(id)

    suspend fun find(name: String, pkg: String): ChatMemoryEntity? =
        db.chatMemoryDao().find(name, pkg)

    /** 找到就复用，找不到才新建：同一聊天反复点悬浮球不会刷出一堆空记忆 */
    suspend fun findOrCreate(name: String, pkg: String): ChatMemoryEntity {
        db.chatMemoryDao().find(name, pkg)?.let { return it }
        val fresh = ChatMemoryEntity(name = name, appPkg = pkg)
        val id = db.chatMemoryDao().insert(fresh)
        return fresh.copy(id = id)
    }

    suspend fun create(name: String, pkg: String): ChatMemoryEntity {
        val fresh = ChatMemoryEntity(name = name, appPkg = pkg)
        val id = db.chatMemoryDao().insert(fresh)
        return fresh.copy(id = id)
    }

    suspend fun rename(id: Long, name: String): ChatMemoryEntity? {
        val m = db.chatMemoryDao().byId(id) ?: return null
        val upd = m.copy(name = name, updatedAt = System.currentTimeMillis())
        db.chatMemoryDao().update(upd)
        return upd
    }

    suspend fun append(id: Long, turn: ChatTurn, keep: Int = 120): ChatMemoryEntity? {
        val dao = db.chatMemoryDao()
        val m = dao.byId(id) ?: return null
        val list = turns(m).toMutableList()
        list.add(turn.copy(ts = if (turn.ts == 0L) System.currentTimeMillis() else turn.ts))
        val trimmed = if (list.size > keep) list.takeLast(keep) else list
        val upd = m.copy(
            turnsJson = json.encodeToString(turnsSerializer, trimmed),
            updatedAt = System.currentTimeMillis()
        )
        dao.update(upd)
        return upd
    }

    suspend fun delete(id: Long) {
        db.chatMemoryDao().delete(id)
    }

    fun turns(m: ChatMemoryEntity?): List<ChatTurn> {
        if (m == null || m.turnsJson.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(turnsSerializer, m.turnsJson)
        }.getOrDefault(emptyList())
    }

    fun turnCount(m: ChatMemoryEntity?): Int = turns(m).size

    /**
     * 给模型看的记忆摘要：只带最近几轮，控制在 900 字内，
     * 避免每次聊天都拿整段历史烧 Token。
     */
    fun memoryText(m: ChatMemoryEntity?, limit: Int = 10, maxChars: Int = 900): String {
        val list = turns(m)
            .filter {
                it.role == ChatTurn.ROLE_USER ||
                    it.role == ChatTurn.ROLE_ADOPTED ||
                    (it.role == ChatTurn.ROLE_ASSISTANT && it.text.isNotBlank())
            }
            .takeLast(limit)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        list.forEach { t ->
            val line = when (t.role) {
                ChatTurn.ROLE_USER -> "我：${t.text.take(120)}"
                ChatTurn.ROLE_ADOPTED -> "最后发出去的是：${t.text.take(120)}"
                else -> "你之前建议：${t.text.take(80)}"
            }
            if (sb.length + line.length + 1 > maxChars) return@forEach
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }
}
