package com.wzy.replymate.core

import com.wzy.replymate.domain.Candidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RawCandidate(val text: String = "", val why: String = "")

@Serializable
internal data class RawBatch(val candidates: List<RawCandidate> = emptyList())

/**
 * 结构化解析 + 整批原子校验。
 * 四条畸形返回——只回 2 条 / 3 句解释同一句 / 解释为空 / text 为空——一律返回 null，
 * 由上层整批降级为离线模板（不允许 2 条模型 + 1 条模板混排）。
 * 空值与纯空格统一按 trim 后判空。
 */
object Parser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * @param expect 期望条数：回复场景 5 条，其余 3 条。
     * 模型偶尔多给几条，取前 expect 条；少于 expect 条视为不合格（避免半批）。
     */
    fun parse(raw: String, expect: Int = 3): List<Candidate>? {
        if (raw.isBlank()) return null
        val cleaned = extractJson(raw)
        val batch = runCatching { json.decodeFromString(RawBatch.serializer(), cleaned) }.getOrNull() ?: return null
        val list = batch.candidates
            .map { Candidate(it.text.trim(), it.why.trim()) }
            .filter { it.text.isNotEmpty() && it.why.isNotEmpty() }
        if (list.size < expect) return null
        val picked = list.take(expect)
        if (picked.map { it.why }.toSet().size < expect) return null
        if (picked.map { it.text }.toSet().size < expect) return null
        return picked
    }

    fun extractJson(raw: String): String {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else raw
    }
}
