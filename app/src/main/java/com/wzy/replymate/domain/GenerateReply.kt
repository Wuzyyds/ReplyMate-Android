package com.wzy.replymate.domain

import android.os.SystemClock
import com.wzy.replymate.core.EmojiFilter
import com.wzy.replymate.core.Parser
import com.wzy.replymate.core.PromptBuilder
import com.wzy.replymate.data.ChatMessage
import com.wzy.replymate.data.LlmClient
import com.wzy.replymate.data.LlmException
import com.wzy.replymate.data.TemplateRepo
import com.wzy.replymate.data.db.AppDatabase
import com.wzy.replymate.data.db.GenerationEntity
import com.wzy.replymate.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 生成用例。三条路径：
 * 1) 同参数命中本地话术库 → 直接返回，不消耗 Token
 * 2) 模型成功 → 整批来自模型，并自动存进话术库
 * 3) 解析失败重试 1 次（温度降 0.3）→ 仍失败走模板，打 notice（不写话术库）
 *
 * 一批 3 条只能全模型或全模板，禁止混排；source 只有两个取值。
 */
class GenerateReply(
    private val llm: LlmClient,
    private val templates: TemplateRepo,
    private val store: SettingsStore,
    private val db: AppDatabase
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Candidate.serializer())

    suspend fun generate(
        req: GenRequest,
        forceRefresh: Boolean = false,
        onProgress: (String) -> Unit = {},
        /** 边生成边上屏：每收全一条候选就回调一次，UI 不用等 3 条齐 */
        onPartial: (List<Candidate>) -> Unit = {}
    ): GenResult = withContext(Dispatchers.IO) {
        val key = cacheKey(req)

        // 同参数直接拿话术库，用户在「换一批」时才会绕过
        if (!forceRefresh && key != null) {
            val cached = runCatching { db.generationDao().byCacheKey(key) }.getOrNull()
            val list = cached?.let { parseCandidates(it.candidatesJson) }
            // 条数不够就当没命中，避免用户看到半批
            val want = 3
            if (cached != null && !list.isNullOrEmpty() && list.size >= want) {
                android.util.Log.d("RM", "gen cache hit key=${key.take(12)} rows=${list.size}")
                return@withContext GenResult(
                    candidates = list,
                    source = runCatching { Source.valueOf(cached.source) }.getOrDefault(Source.MODEL),
                    latencyMs = 0,
                    notice = "已命中话术库 · 未消耗 Token",
                    fromCache = true
                )
            }
        }

        val cfg = store.config

        // 按用户要求取消发送前脱敏：原文直接交给模型
        val (sys, user) = PromptBuilder.build(req)
        val messages = listOf(ChatMessage("system", sys), ChatMessage("user", user))
        val started = SystemClock.elapsedRealtime()

        android.util.Log.d(
            "RM",
            "gen start task=${req.taskType} model=${cfg.model} base=${cfg.baseUrl} keyLen=${cfg.apiKey.length} force=$forceRefresh"
        )

        var parsed: List<Candidate>? = null
        // 超时/网络失败时用模板兜底，并把原因写进 notice，别让用户以为是模型不会答
        var fallbackNotice: String? = null
        // 面板一屏放得下 3 条；条数太少不够挑，太多反而要滚动
        val want = 3
        // 3 条 ×（80 字正文 + 22 字 why）约 400 token，700 留足余量且出字更快
        val maxTokens = 700
        try {
            val raw = llm.stream(cfg, messages, 0.8f, maxTokens) { partial ->
                onProgress(partial)
                // 收全一条就先送上去，用户不用盯着「正在想」
                val live = Parser.parsePartial(partial, want)
                if (live.isNotEmpty()) onPartial(live)
            }
            parsed = Parser.parse(raw, want)
            if (parsed == null) {
                // 重试只要 3 条短句，token 减半能让第二次快 30% 左右
                val raw2 = llm.stream(cfg, messages, 0.5f, 500)
                parsed = Parser.parse(raw2, want)
            }
        } catch (e: LlmException) {
            android.util.Log.d("RM", "gen LlmException code=${e.code} msg=${e.message}")
            // Key 未填 / 401 / 403：优先级高于超时与解析，直接上抛，UI 跳设置并高亮
            if (e.code == 0 || e.code == 401 || e.code == 403) throw e
            // 账号欠费、Key 无效、模型名不存在属于「配置错」，静默降级会让用户以为模型不行
            val raw = e.message.orEmpty()
            if (raw.contains("Arrearage", true) ||
                raw.contains("InvalidApiKey", true) ||
                raw.contains("model_not_found", true) ||
                raw.contains("ModelNotFound", true)
            ) throw e
            // 其余（超时、网络、5xx）落到模板兜底，不抛
            fallbackNotice = when (e.code) {
                -3 -> "网络慢或连不上，先给你 3 条备用说法"
                -1 -> "网络不通，先给你 3 条备用说法"
                else -> "这次没生成成功，给你 3 条备用说法"
            }
        } catch (e: Exception) {
            android.util.Log.d("RM", "gen 异常 ${e.javaClass.simpleName}: ${e.message}")
            // 未知异常同样兜底，不让用户空手
        }

        val latency = SystemClock.elapsedRealtime() - started
        val model = parsed
        if (model != null) {
            val result = GenResult(EmojiFilter.applyAll(model, req.emoji), Source.MODEL, latency, null)
            save(req, result, key)
            result
        } else {
            val result = GenResult(
                EmojiFilter.applyAll(templates.pick(req), req.emoji),
                Source.TEMPLATE,
                latency,
                fallbackNotice ?: "这次没生成成功，给你 3 条备用说法"
            )
            // 模板兜底不进话术库，避免下次同问题直接命中模板而不再尝试模型
            save(req, result, null)
            result
        }
    }

    /** 用户手动切离线模板时，只记历史，不进话术库、不参与缓存 */
    suspend fun saveOffline(req: GenRequest, res: GenResult) = withContext(Dispatchers.IO) {
        save(req, res, null, saved = false)
    }

    private suspend fun save(
        req: GenRequest,
        res: GenResult,
        key: String?,
        saved: Boolean = res.source == Source.MODEL && key != null
    ) {
        runCatching {
            db.generationDao().insert(
                GenerationEntity(
                    taskType = req.taskType.name,
                    input = req.input,
                    candidatesJson = json.encodeToString(listSerializer, res.candidates),
                    source = res.source.name,
                    relation = req.relation.name,
                    goal = req.goal.name,
                    style = req.style.name,
                    emojiPolicy = req.emoji.name,
                    latencyMs = res.latencyMs,
                    cacheKey = if (saved) key.orEmpty() else "",
                    saved = saved
                )
            )
        }.onFailure {
            android.util.Log.d("RM", "gen save failed: ${it.message}")
        }
    }

    private fun parseCandidates(raw: String): List<Candidate>? =
        runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 缓存键：同任务、同原文、同关系/目标/风格/表情/场景才可复用。
     * 带图片的发圈不做缓存（图片不同文案就不同）。
     */
    private fun cacheKey(req: GenRequest): String? {
        if (req.imageBase64 != null) return null
        val raw = buildString {
            append(req.taskType.name).append('|')
            append(req.input.trim()).append('|')
            append(req.relation.name).append('|')
            append(req.goal.name).append('|')
            append(req.style.name).append('|')
            append(req.emoji.name).append('|')
            append(req.scene.name).append('|')
            append(req.length.name).append('|')
            append(req.tone.name).append('|')
            append(req.polishInput.name).append('|')
            append(req.profileSummary.orEmpty()).append('|')
            // 记忆不同，回答就不同，不能命中别人的缓存
            append(req.memory)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
