package com.wzy.replymate.data

import android.content.Context
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.domain.GenRequest
import com.wzy.replymate.domain.PolishInput
import com.wzy.replymate.domain.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TplDto(
    val relation: String = "ANY",
    val goal: String = "ANY",
    val text: String = "",
    val why: String = ""
)

@Serializable
private data class TplFile(val reply: List<TplDto> = emptyList())

/** 给「话术库」页面用的公开结构 */
data class TplItem(
    val relation: String,
    val goal: String,
    val text: String,
    val why: String
)

/**
 * 离线话术库。它是解析失败 / 断网时的唯一兜底，M1 就必须有最小集合。
 * 命中的 3 条也必须打「离线模板 · 未调用模型」角标（由调用方读 source 决定）。
 */
class TemplateRepo(context: Context) {

    private val list: List<TplDto> = runCatching {
        val txt = context.assets.open("templates.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString(TplFile.serializer(), txt).reply
    }.getOrElse { emptyList() }

    fun pick(req: GenRequest): List<Candidate> = when (req.taskType) {
        TaskType.REPLY -> replyFallback(req)
        TaskType.POST -> postFallback()
        TaskType.POLISH -> polishFallback(req)
    }

    /** 全部话术，供「话术库」页浏览 */
    fun all(): List<TplItem> = list
        .filter { it.text.isNotBlank() }
        .map { TplItem(it.relation, it.goal, it.text, it.why) }

    private fun replyFallback(req: GenRequest): List<Candidate> {
        val exact = list.filter { it.relation == req.relation.name && it.goal == req.goal.name }
        val sameRelation = list.filter { it.relation == req.relation.name && it !in exact }
        val generic = list.filter { it.relation == "ANY" }
        val pool = (exact + sameRelation + generic).filter { it.text.isNotBlank() }
            .distinctBy { it.text }
            .take(3)
        val labels = listOf("稳", "暖", "机灵")
        val out = pool.mapIndexed { i, t -> Candidate(t.text, t.why, labels[i % 3]) }.toMutableList()
        while (out.size < 3) {
            out.add(Candidate("这条我先记下来，等我想清楚再认真回你。", "模板兜底：不断对话，也不乱承诺。", labels[out.size % 3]))
        }
        return out.take(3)
    }

    private fun polishFallback(req: GenRequest): List<Candidate> {
        if (req.polishInput == PolishInput.INTENT) return intentFallback()
        val input = req.input
        val raw = input.trim().trimEnd('。', '！', '!', '，', ',', '；', ';')
        val src = raw.ifBlank { "这件事" }
        return listOf(
            Candidate(
                "这次可能真帮不上：$src。先跟你说一声，别耽误你安排。",
                "模板：在句尾补一个缓冲，把硬拒绝变软。",
                "稳"
            ),
            Candidate(
                "$src，怕做不好反而耽误你，所以先讲清楚。",
                "模板：把拒绝换成「替你考虑」的理由。",
                "暖"
            ),
            Candidate(
                "$src。要不我先给你个替代方案？",
                "模板：给一个出口，不让对话死在这。",
                "机灵"
            )
        )
    }

    /**
     * 「只有个想法」的离线兜底：没有原话可改写，所以只能给通用的委婉话术。
     * 场景偏「不想继续聊 / 想抽身」，这是这类需求里最常见的一类。
     */
    private fun intentFallback(): List<Candidate> = listOf(
        Candidate(
            "最近手头事情有点多，可能没法像之前那样随时聊了，先跟你说一声。",
            "模板：用「忙」当理由，不给对方下评价，也不留追问口子。",
            "稳"
        ),
        Candidate(
            "说实话我最近想安静一阵，不是你的问题，是我自己状态不太行。",
            "模板：把原因归到自己身上，给对方留面子。",
            "暖"
        ),
        Candidate(
            "我可能不太适合这种高频聊天，怕耽误你，还是先这样吧。",
            "模板：把拒绝说成「为你考虑」，边界清楚又不刺人。",
            "机灵"
        )
    )

    private fun postFallback(): List<Candidate> = listOf(
        Candidate(
            "今天这段路走得挺值，人少，风大，脑子终于安静了。",
            "模板：只写具体细节，不空抒情。",
            "稳"
        ),
        Candidate(
            "把手机丢包里走了一天，回来发现没人找我。挺好的，真的。",
            "模板：用一个小反差做钩子。",
            "机灵"
        ),
        Candidate(
            "有些风景得自己去看一眼，照片拍不出来。",
            "模板：留一句可被回应的感慨。",
            "暖"
        )
    )
}
