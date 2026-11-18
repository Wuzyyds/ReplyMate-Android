package com.wzy.replymate.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 服务商清单，来自 assets/models.json。
 * 用户只需要选一个名字 + 填 Key，baseUrl 和模型名自动带出来，不用手抄地址。
 */
@Serializable
data class Provider(
    val name: String,
    val baseUrl: String,
    val model: String,
    val note: String = ""
)

object ModelCatalog {

    private const val ASSET = "models.json"
    private var cached: List<Provider>? = null

    fun all(ctx: Context): List<Provider> {
        cached?.let { return it }
        val list = runCatching {
            val txt = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<List<Provider>>(txt)
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** 用当前 baseUrl 反查是哪家，切回来时能自动高亮 */
    fun matchByBase(ctx: Context, baseUrl: String): Provider? {
        val target = baseUrl.trim().trimEnd('/')
        if (target.isBlank()) return null
        return all(ctx).firstOrNull { it.baseUrl.trim().trimEnd('/').equals(target, true) }
    }

    /** 认不出 baseUrl 时，用模型名前缀兜底（deepseek-* / qwen* / glm*） */
    fun matchByModel(ctx: Context, model: String): Provider? {
        val m = model.trim().lowercase()
        if (m.isBlank()) return null
        return all(ctx).firstOrNull { p ->
            val key = p.model.trim().lowercase().substringBefore('-')
            key.isNotBlank() && m.startsWith(key)
        }
    }
}
