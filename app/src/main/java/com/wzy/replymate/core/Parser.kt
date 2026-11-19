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

    private val TEXT_RE = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val WHY_RE = Regex("\"why\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * 边生成边解析：只要某一项的 text 和 why 都收全了就立刻返回它。
     * 用于「首字先显示」——不必等 3 条全齐才上屏。
     * 只匹配已经闭合的引号，所以不会把半截字符串当成结果。
     */
    fun parsePartial(raw: String, max: Int = 3): List<Candidate> {
        if (raw.isBlank()) return emptyList()
        val texts = TEXT_RE.findAll(raw).map { unescape(it.groupValues[1]).trim() }.toList()
        val whys = WHY_RE.findAll(raw).map { unescape(it.groupValues[1]).trim() }.toList()
        val n = minOf(texts.size, whys.size, max)
        return (0 until n).mapNotNull { i ->
            if (texts[i].isEmpty() || whys[i].isEmpty()) null else Candidate(texts[i], whys[i])
        }
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/")

    fun extractJson(raw: String): String {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else raw
    }
}
