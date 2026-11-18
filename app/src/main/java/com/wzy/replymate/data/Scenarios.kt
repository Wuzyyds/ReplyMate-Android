package com.wzy.replymate.data

import android.content.Context
import com.wzy.replymate.data.prefs.SettingsStore
import com.wzy.replymate.domain.Goal
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.service.ReplyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class HomeScenario(
    val app: String = "微信",
    val title: String = "",
    val relation: String = "BOSS",
    val goal: String = "BARGAIN",
    val input: String = ""
)

/** 首页三个常驻场景。AI 生成失败/没配 Key 时就用它，保证永远有入口。 */
val DEFAULT_SCENARIOS = listOf(
    HomeScenario("微信", "领导问「方案明天能给吗」", "BOSS", "BARGAIN", "王总：这个方案明天能给吗？"),
    HomeScenario("BOSS直聘", "HR 问「期望薪资多少」", "HR", "BARGAIN", "HR：您期望薪资是多少？"),
    HomeScenario("微信", "对方隔两小时回「刚忙完」", "FLIRT", "KEEP", "她：刚忙完，今天有点累\n我：那就早点休息？\n她：嗯，不过还想聊会儿")
)

/** 首页场景缓存：只是文案，不放敏感原文 */
object ScenarioStore {
    private const val PREF = "replymate"
    private const val KEY = "home_scenarios"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(ctx: Context): List<HomeScenario> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw.isNullOrBlank()) return DEFAULT_SCENARIOS
        val list = runCatching {
            json.decodeFromString(ListSerializer(HomeScenario.serializer()), raw)
        }.getOrNull()
        return list?.takeIf { it.size >= 3 } ?: DEFAULT_SCENARIOS
    }

    fun save(ctx: Context, list: List<HomeScenario>) {
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.encodeToString(ListSerializer(HomeScenario.serializer()), list))
                .apply()
        }
    }
}

/**
 * 按当前聊天 App（不知道就随机）让模型出 3 个真实难题。
 * 只用于首页「不知道输入什么」的灵感，不写话术库。
 */
object ScenarioGen {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val listSerializer = ListSerializer(HomeScenario.serializer())
    private val APPS = listOf("微信", "飞书", "钉钉", "BOSS直聘", "工作邮件")

    suspend fun next(ctx: Context, llm: LlmClient, store: SettingsStore): List<HomeScenario> =
        withContext(Dispatchers.IO) {
            val cfg = store.config
            if (cfg.apiKey.isBlank()) return@withContext emptyList()

            val app = currentApp()
            val system = """
                你是中文社交场景设计师。只输出 JSON 数组，不要 markdown、不要解释。
                每项格式：{"app":"微信","title":"领导问方案","relation":"BOSS","goal":"BARGAIN","input":"王总：这个方案明天能给吗？"}
                relation 只能是：FRIEND/FLIRT/FAMILY/HR/BOSS/COLLEAGUE/CLIENT
                goal 只能是：KEEP/PUSH/REFUSE/APOLOGY/BARGAIN/END
                场景必须真实具体、像真人发的，是普通人经常遇到但不知道怎么回的话。
            """.trimIndent()
            val user = "App：$app。生成 3 个不同场景。"

            val raw = runCatching {
                llm.stream(
                    cfg,
                    listOf(
                        ChatMessage("system", system),
                        ChatMessage("user", user)
                    ),
                    0.85f,
                    600
                )
            }.getOrNull() ?: return@withContext emptyList()

            val parsed = parse(raw)
            if (parsed.isEmpty()) return@withContext emptyList()

            // 不足 3 个用默认场景补齐，保证首页永远有 3 个可点
            val out = parsed.take(3).toMutableList()
            DEFAULT_SCENARIOS.forEach { d ->
                if (out.size < 3 && out.none { it.input == d.input }) out.add(d)
            }
            ScenarioStore.save(ctx, out)
            out
        }

    private fun parse(raw: String): List<HomeScenario> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        val body = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        val list = runCatching {
            json.decodeFromString(listSerializer, body)
        }.getOrNull() ?: return emptyList()

        return list.mapNotNull { d ->
            val input = d.input.trim()
            if (input.isBlank()) return@mapNotNull null
            HomeScenario(
                app = d.app.ifBlank { "微信" },
                title = d.title.trim().ifBlank { input.replace("\n", " ").take(16) },
                relation = normalizeRelation(d.relation),
                goal = normalizeGoal(d.goal),
                input = input
            )
        }.distinctBy { it.input }
    }

    private fun normalizeRelation(v: String): String {
        val s = v.trim()
        Relation.entries.firstOrNull { it.name.equals(s, true) || it.label == s }?.let { return it.name }
        return when (s) {
            "朋友" -> "FRIEND"
            "暧昧" -> "FLIRT"
            "家人" -> "FAMILY"
            "领导", "老板" -> "BOSS"
            "同事" -> "COLLEAGUE"
            "客户" -> "CLIENT"
            "HR", "人事" -> "HR"
            else -> "FRIEND"
        }
    }

    private fun normalizeGoal(v: String): String {
        val s = v.trim()
        Goal.entries.firstOrNull { it.name.equals(s, true) || it.label == s }?.let { return it.name }
        return when (s) {
            "维持", "保持" -> "KEEP"
            "推进", "推进关系" -> "PUSH"
            "拒绝" -> "REFUSE"
            "致歉", "道歉" -> "APOLOGY"
            "谈条件", "争取" -> "BARGAIN"
            "结束对话", "结束" -> "END"
            else -> "KEEP"
        }
    }

    private fun currentApp(): String {
        return when (ReplyAccessibilityService.lastChatPkg) {
            "com.tencent.mm" -> "微信"
            "com.ss.android.lark" -> "飞书"
            "com.alibaba.android.rimet" -> "钉钉"
            "com.tencent.wework" -> "企业微信"
            "com.hpbr.bosszhipin" -> "BOSS直聘"
            "com.google.android.gm", "com.android.email" -> "工作邮件"
            else -> APPS.random()
        }
    }
}
