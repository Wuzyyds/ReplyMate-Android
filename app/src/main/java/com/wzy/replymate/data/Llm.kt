package com.wzy.replymate.data

import com.wzy.replymate.data.prefs.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmException(val code: Int, msg: String) : Exception(msg)

/** 把厂商原始错误翻译成一句用户能看懂、能照着做的话 */
fun humanizeLlmError(e: LlmException): String {
    val raw = e.message.orEmpty()
    return when {
        e.code == 0 -> "还没填 API Key，去「设置」里补一个"
        raw.contains("Arrearage", true) || raw.contains("overdue payment", true) ->
            "账号欠费了（阿里云返回 Arrearage），去百炼控制台充值后重试"
        e.code == 401 || e.code == 403 -> "Key 无效或没有该模型权限"
        e.code == 429 -> "调用太频繁，等几秒再试"
        raw.contains("InvalidApiKey", true) -> "Key 无效，检查是否复制完整"
        raw.contains("ModelNotFound", true) || raw.contains("model_not_found", true) ->
            "模型名不对，检查「设置」里的模型名"
        raw.contains("timeout", true) || e.code == -1 -> "网络不通或超时，检查网络后重试"
        else -> raw.ifBlank { "请求失败（HTTP ${e.code}）" }
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    /** 有图时 content 走 OpenAI 多模态数组：[{type:text},{type:image_url}] */
    val imageBase64: String? = null
)

/**
 * OpenAI 兼容的最小客户端：一个 OkHttp + SSE 解析。
 * 三家厂商（DeepSeek / 通义 / 智谱）只需改 baseUrl 与 model 名。
 */
class LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 流式回复按“两次收到内容之间”的超时算；25 秒没有任何新内容就认为卡住了
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // 整次请求最多等 35 秒：慢网络/模型排队时给明确失败，不让用户无限等
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /** 阻塞式流式请求，调用方必须放在 Dispatchers.IO。onDelta 收到的是累计文本。 */
    fun stream(
        cfg: ModelConfig,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int = 700,
        onDelta: (String) -> Unit = {}
    ): String {
        if (cfg.apiKey.isBlank()) throw LlmException(0, "API Key 未填写")
        if (cfg.model.isBlank()) throw LlmException(0, "模型名未填写")

        val payload = buildJsonObject {
            put("model", cfg.model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        if (m.imageBase64 != null) {
                            // OpenAI 兼容多模态格式：文本 + 图片
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", m.content)
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        buildJsonObject { put("url", "data:image/jpeg;base64," + m.imageBase64) }
                                    )
                                }
                            }
                        } else {
                            put("content", m.content)
                        }
                    }
                }
            }
        }.toString()

        val request = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer " + cfg.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(mediaJson))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: java.io.InterruptedIOException) {
            throw LlmException(-3, "请求超时：网络连不上或模型太慢，重试一下")
        } catch (e: IOException) {
            throw LlmException(-1, "网络不可用：" + (e.message ?: "连接失败"))
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                throw LlmException(r.code, "HTTP " + r.code + " " + body.take(200))
            }
            val source = r.body?.source() ?: throw LlmException(-2, "空响应")
            val sb = StringBuilder()
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        Json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(sb.toString())
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                throw LlmException(-3, "回复到一半超过 25 秒没新内容，已停止；重试一下")
            } catch (e: IOException) {
                throw LlmException(-1, "网络中断：" + (e.message ?: "连接失败"))
            }
            return sb.toString()
        }
    }

    /** 连通性自检：8 token 的 ping，返回耗时毫秒。 */
    fun ping(cfg: ModelConfig): Long {
        if (cfg.apiKey.isBlank()) throw LlmException(0, "API Key 未填写")
        val started = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("stream", false)
            put("max_tokens", 8)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "ping")
                }
            }
        }.toString()

        val request = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer " + cfg.apiKey)
            .post(payload.toRequestBody(mediaJson))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: java.io.InterruptedIOException) {
            throw LlmException(-3, "请求超时：网络连不上或服务商无响应")
        } catch (e: IOException) {
            throw LlmException(-1, "网络不可用：" + (e.message ?: "连接失败"))
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                throw LlmException(r.code, "HTTP " + r.code + " " + body.take(200))
            }
            return System.currentTimeMillis() - started
        }
    }
}
