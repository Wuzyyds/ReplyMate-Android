package com.wzy.replymate.domain

/** 三种说话场景：回复 / 发圈 / 润色。共用一条生成管线。 */
enum class TaskType(val label: String) {
    REPLY("回复"), POST("发圈"), POLISH("润色")
}

enum class Relation(val label: String) {
    FRIEND("朋友"), FLIRT("暧昧"), FAMILY("家人"),
    HR("HR"), BOSS("领导"), COLLEAGUE("同事"), CLIENT("客户")
}

enum class Goal(val label: String) {
    KEEP("维持"), PUSH("推进"), REFUSE("拒绝"),
    APOLOGY("致歉"), BARGAIN("谈条件"), END("结束对话")
}

enum class Style(val label: String, val hint: String) {
    STEADY("稳", "克制、专业、不卑不亢"),
    WARM("暖", "有温度、先接情绪"),
    QUICK("机灵", "带一点幽默，别油")
}

/** 表情档位。none 必须后处理彻底剥离，不能只靠 prompt。 */
enum class EmojiPolicy(val label: String) {
    NONE("无"), LIGHT("少"), RICH("多")
}

enum class PostScene(val label: String) {
    DAILY("日常"), TRAVEL("旅行"), FOOD("美食"),
    OVERTIME("加班"), ANNIVERSARY("纪念日"), RANT("吐槽")
}

enum class PostLength(val label: String) {
    SHORT("短"), MEDIUM("中"), TAGS("带话题")
}

enum class PolishTone(val label: String) {
    POLITE("更客气"), CLOSE("更亲近"), PRO("更专业"), SHORT("更简短")
}

/**
 * 润色的两种输入。
 * REWRITE：用户已经有一句糙话，只改说法不改事实。
 * INTENT ：用户只有意图（"不想跟他聊了，想委婉拒绝"），由模型组织成可直接发出去的话，
 *          此时「不改事实」的约束不成立，必须换成「不编造时间/金额/承诺」。
 */
enum class PolishInput(val label: String) {
    REWRITE("改写我的话"), INTENT("只有个想法")
}

/** 一批结果的来源。整批原子：要么全模型，要么全模板，禁止混排。 */
enum class Source { MODEL, TEMPLATE }

@kotlinx.serialization.Serializable
data class Candidate(
    val text: String,
    val why: String,
    val styleLabel: String = ""
)

data class GenRequest(
    val taskType: TaskType = TaskType.REPLY,
    val input: String,
    val relation: Relation = Relation.FRIEND,
    val goal: Goal = Goal.KEEP,
    val scene: PostScene = PostScene.DAILY,
    val length: PostLength = PostLength.SHORT,
    val tone: PolishTone = PolishTone.POLITE,
    val polishInput: PolishInput = PolishInput.REWRITE,
    val style: Style = Style.STEADY,
    val emoji: EmojiPolicy = EmojiPolicy.NONE,
    val profileSummary: String? = null,
    /** 悬浮聊天窗里这个联系人的历史记忆；空 = 首次聊，不影响缓存键以外的逻辑 */
    val memory: String = "",
    /** 发圈配图（base64，已压到 1024/JPEG80）；null = 纯文字 */
    val imageBase64: String? = null,
    /** 上一批已经给过的说法，换一批时传进来，让模型换个方向而不是换个语序 */
    val avoid: List<String> = emptyList()
)

data class GenResult(
    val candidates: List<Candidate>,
    val source: Source,
    val latencyMs: Long,
    /** 非空表示整批降级或命中缓存，UI 必须显示这句人话 */
    val notice: String? = null,
    /** 本次直接来自本地话术库，没有调用模型 */
    val fromCache: Boolean = false
)
