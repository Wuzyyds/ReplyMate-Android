package com.wzy.replymate.data

import com.wzy.replymate.data.db.AppDatabase
import com.wzy.replymate.data.db.ContactEntity
import com.wzy.replymate.data.db.ProfileChangeEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProfileSummary(
    val habit: String = "",
    val care: String = "",
    val taboo: String = "",
    val trick: String = ""
) {
    fun isEmpty(): Boolean = habit.isBlank() && care.isBlank() && taboo.isBlank() && trick.isBlank()

    fun lines(): List<Pair<String, String>> = listOf(
        "表达习惯" to habit,
        "真正在意" to care,
        "雷区" to taboo,
        "吃哪一套" to trick
    ).filter { it.second.isNotBlank() }

    fun oneLine(): String = lines().joinToString(" · ") { it.second }

    fun toPrompt(): String = buildString {
        if (habit.isNotBlank()) append("表达习惯：").append(habit).append("；")
        if (care.isNotBlank()) append("真正在意：").append(care).append("；")
        if (taboo.isNotBlank()) append("雷区：").append(taboo).append("；")
        if (trick.isNotBlank()) append("吃这一套：").append(trick)
    }
}

object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(s: String): ProfileSummary =
        if (s.isBlank()) ProfileSummary() else runCatching { json.decodeFromString<ProfileSummary>(s) }.getOrDefault(ProfileSummary())
    fun encode(s: ProfileSummary): String = json.encodeToString(s)
}

/**
 * 规则化演化：只吃本机已有的生成/采纳记录，不调模型。
 * 好处是断网可用、不会瞎编；代价是画像比较"浅"，够 v1 用。
 * 硬约束：一次只改 1 条，写 profile_change 便于撤销，绝不静默批量改。
 */
class ProfileEvolver(private val db: AppDatabase) {

    suspend fun evolve(contact: ContactEntity): Pair<ContactEntity, ProfileChangeEntity?> {
        val rows = db.generationDao().byRelation(contact.relation)
        if (rows.size < 3) return contact to null

        val adopted = rows.filter { it.adoptedIndex >= 0 }
        val pool = (if (adopted.size >= 3) adopted else rows).take(20)
        val avgLen = pool.map { it.input.length }.average()
        val styleTop = pool.groupingBy { it.style }.eachCount().maxByOrNull { it.value }?.key
        val goalTop = pool.groupingBy { it.goal }.eachCount().maxByOrNull { it.value }?.key
        val emojiTop = pool.groupingBy { it.emojiPolicy }.eachCount().maxByOrNull { it.value }?.key

        val cur = ProfileCodec.decode(contact.summaryJson)
        val proposals = ArrayList<Triple<String, String, String>>()
        if (styleTop != null) proposals.add(
            Triple("habit", "表达习惯", "${styleLabel(styleTop)} 档用得最多，回复偏${styleLabel(styleTop)}")
        )
        if (goalTop != null) proposals.add(
            Triple("care", "真正在意", "最近多在「${goalLabel(goalTop)}」，在意的是结果与节奏")
        )
        if (avgLen > 0) proposals.add(
            Triple("trick", "吃哪一套", "先给结论再展开，长度压在 ${bucket(avgLen)} 字内")
        )
        if (emojiTop == "NONE") proposals.add(
            Triple("taboo", "雷区", "不吃花哨 emoji，别在正文里堆表情")
        )

        val pick = proposals.firstOrNull { fieldValue(cur, it.first) != it.third } ?: return contact to null
        val old = fieldValue(cur, pick.first)
        val updated = when (pick.first) {
            "habit" -> cur.copy(habit = pick.third)
            "care" -> cur.copy(care = pick.third)
            "taboo" -> cur.copy(taboo = pick.third)
            else -> cur.copy(trick = pick.third)
        }
        val change = ProfileChangeEntity(
            contactId = contact.id,
            field = pick.first,
            fieldLabel = pick.second,
            oldValue = old,
            newValue = pick.third,
            evidence = "基于最近 ${pool.size} 次生成（采纳 ${adopted.size} 次）"
        )
        val saved = contact.copy(
            summaryJson = ProfileCodec.encode(updated),
            evolvedAt = System.currentTimeMillis(),
            useCount = contact.useCount + 1
        )
        db.contactDao().update(saved)
        db.contactDao().insertChange(change)
        return saved to change
    }

    suspend fun revert(change: ProfileChangeEntity) {
        val contact = db.contactDao().byId(change.contactId) ?: return
        val cur = ProfileCodec.decode(contact.summaryJson)
        val back = when (change.field) {
            "habit" -> cur.copy(habit = change.oldValue)
            "care" -> cur.copy(care = change.oldValue)
            "taboo" -> cur.copy(taboo = change.oldValue)
            else -> cur.copy(trick = change.oldValue)
        }
        db.contactDao().update(contact.copy(summaryJson = ProfileCodec.encode(back)))
        db.contactDao().markReverted(change.id)
    }

    private fun fieldValue(s: ProfileSummary, f: String): String = when (f) {
        "habit" -> s.habit
        "care" -> s.care
        "taboo" -> s.taboo
        else -> s.trick
    }

    private fun bucket(avg: Double): Int = ((avg / 10).toInt().coerceIn(2, 8)) * 10

    private fun styleLabel(v: String): String = when (v) {
        "WARM" -> "暖"
        "QUICK" -> "机灵"
        else -> "稳"
    }

    private fun goalLabel(v: String): String = when (v) {
        "PUSH" -> "推进"
        "REFUSE" -> "拒绝"
        "APOLOGY" -> "致歉"
        "BARGAIN" -> "谈条件"
        "END" -> "结束对话"
        else -> "维持"
    }
}
