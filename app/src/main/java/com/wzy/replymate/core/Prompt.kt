package com.wzy.replymate.core

import com.wzy.replymate.domain.EmojiPolicy
import com.wzy.replymate.domain.GenRequest
import com.wzy.replymate.domain.PolishInput
import com.wzy.replymate.domain.Style
import com.wzy.replymate.domain.TaskType

object PromptBuilder {

    private const val SCHEMA =
        "只输出 JSON，不要 markdown 代码块、不要任何多余文字：\n" +
            "{\"candidates\":[{\"text\":\"...\",\"why\":\"...\"},{\"text\":\"...\",\"why\":\"...\"},{\"text\":\"...\",\"why\":\"...\"}]}"

    fun build(req: GenRequest): Pair<String, String> = system(req) to user(req)

    private fun system(req: GenRequest): String = buildString {
        append(SCHEMA).append("\n\n")
        when (req.taskType) {
            TaskType.REPLY -> append(
                "你在帮一个中国人回复微信或工作消息。硬性要求：\n" +
                    "1. 必须给 3 条，三条的应对策略必须不同，不能是同一句话换语序：\n" +
                    "   ① 直接给答复：最短最干脆，先把态度抛出去；\n" +
                    "   ② 先说自己会怎么做、再给时间或结果，显得靠谱；\n" +
                    "   ③ 换角度：确认细节、给更精确的节点，或把话题轻轻抛回去，别重复①②的说法；\n" +
                    "2. 每条 text 不超过 80 字，像真人打字，不要书面语，不要「首先/其次/综上」；\n" +
                    "3. 每条 why 不超过 40 字，说清这条点破了什么、避开了什么；\n" +
                    "4. 不要替用户答应具体时间、金额或承诺，除非原文里就有；\n" +
                    "5. 不要输出「作为 AI」之类的话。"
            )
            TaskType.POST -> append(
                "你在帮用户写微信朋友圈文案。硬性要求：\n" +
                    "1. 必须给 3 条，分别偏「稳 / 暖 / 机灵」，各自开头不能雷同；\n" +
                    "2. 口语，像本人随手发的，不要文艺腔，不要排比句；\n" +
                    "3. 禁止出现：生活明朗、万物可爱、人间烟火气、治愈一切、岁月静好、yyds；\n" +
                    "4. 感叹号最多 1 个；每条不超过 100 字；\n" +
                    "5. why 说清钩子在哪、避开了什么套路。"
            )
            TaskType.POLISH -> if (req.polishInput == PolishInput.INTENT) append(
                "用户只给了一句粗略的意图，你要替他组织成可以直接发出去的话。硬性要求：\n" +
                    "1. 必须给 3 条，分别偏「稳（不给对方压力）/ 暖（照顾对方感受）/ 明确（把边界说清但不刺人）」；\n" +
                    "2. 每条都能独立发出去，不超过 80 字，口语，不要编号和引号；\n" +
                    "3. **不得编造**具体的时间、金额、承诺、理由；要留余地就用「最近」「这段时间」这类模糊说法；\n" +
                    "4. 三句 why 互不相同，说清这条用了什么策略：软化、给台阶、给出口、划边界；\n" +
                    "5. 不要把话说死到「再也不联系」，除非用户明确要求；也不是替用户道歉，除非他要求道歉。"
            ) else append(
                "你在帮用户把一句糙话改成得体表达。硬性要求：\n" +
                    "1. 只改说法，不改事实；**不得新增**原文没有的承诺、时间、金额、理由；\n" +
                    "2. 必须给 3 条，从「更客气 / 更亲近 / 更专业或更简短」三个角度改写；\n" +
                    "3. 三句 why 互不相同，内容是「改了什么」：把哪句换成了哪句、攻击性或长度如何变化；\n" +
                    "4. 不要替用户道歉，除非原文有歉意。"
            )
        }
        append("\n\n语气基调：").append(req.style.hint).append("。")
        append("\n").append(emojiRule(req.emoji))
        req.profileSummary?.takeIf { it.isNotBlank() }?.let {
            append("\n已参考对方档案（只作为语气与禁忌约束，不要写进正文）：").append(it)
        }
        req.memory.takeIf { it.isNotBlank() }?.let {
            append("\n\n你和这个人之前的聊天记忆（只用来保持前后一致，不要复述、不要引用）：\n")
            append(it.take(900))
        }
    }

    private fun emojiRule(p: EmojiPolicy): String = when (p) {
        EmojiPolicy.NONE -> "6. 不要使用任何 emoji 或颜文字。"
        EmojiPolicy.LIGHT -> "6. 最多在 1 条里用 1 个 emoji，其余不要。"
        EmojiPolicy.RICH -> "6. 可以用 1-3 个 emoji，但不要放在句首。"
    }

    /**
     * 长对话只保留结尾部分：模型真正需要的是「最后一句说了什么」，
     * 前面几百字只会拖慢首字时间（prefill 越长越慢），对质量几乎没帮助。
     */
    private fun String.tailForPrompt(max: Int): String =
        if (length <= max) this
        else "…（前面 ${length - max} 字已省略）\n" + takeLast(max)

    private fun user(req: GenRequest): String = buildString {
        when (req.taskType) {
            TaskType.REPLY -> {
                append("关系：").append(req.relation.label).append('\n')
                append("我想达到：").append(req.goal.label).append('\n')
                append("风格：").append(req.style.label).append("\n\n")
                append("对话原文（最后一句是对方说的）：\n").append(req.input.trim().tailForPrompt(1200))
            }
            TaskType.POST -> {
                append("场景：").append(req.scene.label).append('\n')
                append("篇幅：").append(req.length.label).append('\n')
                append("风格：").append(req.style.label).append("\n\n")
                append("我想说：").append(req.input.trim().ifBlank { "（我没想法，你看着写）" })
                if (req.imageBase64 != null) {
                    append("\n\n附了一张配图。文案要围绕这张图写：说画面里真实可见的内容，")
                    append("不要编造图里没有的东西，也不要堆空洞的形容词；可以在结尾留一句和图呼应的话。")
                }
            }
            TaskType.POLISH -> {
                append("目标语气：").append(req.tone.label).append('\n')
                append("风格：").append(req.style.label).append("\n\n")
                val body = req.input.trim().tailForPrompt(800)
                if (req.polishInput == PolishInput.INTENT) {
                    append("我想达到的效果：\n").append(body)
                } else {
                    append("我原本想说的：\n").append(body)
                }
            }
        }
        // 换一批：让模型换切入角度，而不是换几个词重新排一遍
        if (req.avoid.isNotEmpty()) {
            append("\n\n上一批已经给过下面这些说法。这一批必须和它们明显不同——换切入角度、换句式，")
            append("不能只改语序或换同义词；但主题、事实、对关系的分寸都不能跑偏：\n")
            req.avoid.take(3).forEach { append("· ").append(it.take(60)).append('\n') }
        }
    }
}

/**
 * 表情后处理。模型一定会滥用 emoji，不能只靠 prompt。
 * 顺序要求：先过整批校验，再过滤表情，最后才计算「3 条差异」。
 */
object EmojiFilter {

    fun applyAll(list: List<com.wzy.replymate.domain.Candidate>, policy: EmojiPolicy) =
        list.map { it.copy(text = apply(it.text, policy)) }

    fun apply(text: String, policy: EmojiPolicy): String = when (policy) {
        EmojiPolicy.NONE -> strip(text)
        EmojiPolicy.LIGHT -> keep(text, 1)
        EmojiPolicy.RICH -> keep(text, 3)
    }

    fun strip(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("^[ \\u3000]+"), "")
            .replace(Regex("[ \\u3000]+$"), "")
    }

    fun count(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isEmoji(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }

    private fun keep(text: String, limit: Int): String {
        var used = 0
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isEmoji(cp)) {
                if (used < limit) { sb.appendCodePoint(cp); used++ }
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isEmoji(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
            cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
}
