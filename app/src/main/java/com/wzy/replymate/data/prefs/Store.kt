package com.wzy.replymate.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wzy.replymate.domain.EmojiPolicy
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.domain.Style
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ModelConfig(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL
) {
    companion object {
        // deepseek-chat 已于 2026-07 退役，默认值必须是现役模型
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"
    }
}

@Serializable
data class ModelPreset(
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val note: String = ""
)

object ModelPresets {
    fun load(context: Context): List<ModelPreset> = runCatching {
        val txt = context.assets.open("models.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<ModelPreset>>(txt)
    }.getOrElse { emptyList() }
}

/**
 * Key 存 EncryptedSharedPreferences（Keystore 加密），失败时降级普通 SP，
 * 但界面永远不明文回显、日志不打印。
 */
class SettingsStore(context: Context) {

    private val app = context.applicationContext

    private val sp: SharedPreferences = runCatching {
        val master = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "rm_secure",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrElse {
        app.getSharedPreferences("rm_secure_fallback", Context.MODE_PRIVATE)
    }

    var config: ModelConfig
        get() = ModelConfig(
            baseUrl = sp.getString(K_BASE, ModelConfig.DEFAULT_BASE) ?: ModelConfig.DEFAULT_BASE,
            apiKey = sp.getString(K_KEY, "") ?: "",
            model = sp.getString(K_MODEL, ModelConfig.DEFAULT_MODEL) ?: ModelConfig.DEFAULT_MODEL
        )
        set(value) {
            sp.edit()
                .putString(K_BASE, value.baseUrl.trim())
                .putString(K_KEY, value.apiKey.trim())
                .putString(K_MODEL, value.model.trim())
                .apply()
        }

    /**
     * 升级后自愈：把已知不适用的模型名换成当前推荐值。
     * 只改 model 字段，Key 和地址原样保留，用户不需要重新粘贴。
     * 例如 glm-5.3-flash 是推理模型，出字要 30 秒以上且经常失败。
     */
    fun migrateModelIfNeeded() {
        val cfg = config
        val fixed = BAD_MODELS[cfg.model] ?: return
        // 没配 Key 的用户不必处理，等他配置时自然会用新默认值
        if (cfg.apiKey.isBlank()) return
        config = cfg.copy(model = fixed)
    }

    var defaultRelation: Relation
        get() = enumOf(sp.getString(K_REL, null), Relation.FRIEND)
        set(v) = sp.edit().putString(K_REL, v.name).apply()

    /** 每次生成记一笔，用来把「常聊的人」排到关系选择器前面 */
    fun bumpRelation(r: Relation) {
        val k = REL_USE_PREFIX + r.name
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
    }

    fun usageOf(r: Relation): Int = sp.getInt(REL_USE_PREFIX + r.name, 0)

    /** 用得最多的关系；一次都没用过则回落到设置里的默认值 */
    fun mostUsedRelation(): Relation {
        val top = Relation.entries.maxByOrNull { usageOf(it) }
        return if (top != null && usageOf(top) > 0) top else defaultRelation
    }

    /** 按使用频率排序（同频保持枚举顺序），给关系选择器用 */
    fun relationsByUsage(): List<Relation> {
        // 还一次都没生成过：按常见程度给初始顺序，
        // 否则按枚举顺序排会把「领导」挤出前五（枚举里它排第六）
        if (Relation.entries.sumOf { usageOf(it) } == 0) {
            return listOf(
                Relation.BOSS, Relation.COLLEAGUE, Relation.COUPLE,
                Relation.FLIRT, Relation.FRIEND, Relation.FAMILY,
                Relation.HR, Relation.CLIENT
            )
        }
        return Relation.entries.sortedWith(
            compareByDescending<Relation> { usageOf(it) }.thenBy { it.ordinal }
        )
    }

    var defaultStyle: Style
        get() = enumOf(sp.getString(K_STYLE, null), Style.STEADY)
        set(v) = sp.edit().putString(K_STYLE, v.name).apply()

    var defaultEmoji: EmojiPolicy
        get() = enumOf(sp.getString(K_EMOJI, null), EmojiPolicy.NONE)
        set(v) = sp.edit().putString(K_EMOJI, v.name).apply()

    /** v1.1 才真正使用；默认允许，但只在用户主动选图时发生 */
    var allowImageUpload: Boolean
        get() = sp.getBoolean(K_IMG, true)
        set(v) = sp.edit().putBoolean(K_IMG, v).apply()

    var autoEvolve: Boolean
        get() = sp.getBoolean(K_EVOLVE, true)
        set(v) = sp.edit().putBoolean(K_EVOLVE, v).apply()

    /** 版本清单地址（静态 JSON）；默认指向公开仓库，主动清空 = 关闭更新检查 */
    var updateUrl: String
        get() {
            val v = sp.getString(K_UPDATE, null)
            return when {
                v == null -> DEFAULT_UPDATE_URL              // 从未设置过：用官方清单
                v.isBlank() -> ""                            // 用户主动清空：关闭检查
                !v.startsWith("http") -> DEFAULT_UPDATE_URL  // 填了非法值：回落默认，别让更新静默失效
                else -> v
            }
        }
        set(v) = sp.edit().putString(K_UPDATE, v.trim()).apply()

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val REL_USE_PREFIX = "rel_use_"

        /**
         * 已知不适用的模型 → 推荐替代。
         * 加新条目只需在这里加一行，老用户升级后自动纠正，不用重新粘贴 Key。
         */
        val BAD_MODELS = mapOf(
            "glm-5.3-flash" to "glm-4-flash"   // 推理模型，30s+ 才出结果，经常超时
        )

        /**
         * 默认更新清单。用 GitHub Pages 而不是 raw.githubusercontent.com：
         * raw 在国内经常被直接阻断（实测模拟器请求超时无响应）；
         * jsDelivr 可访问但 @main 缓存长达 12 小时，发了新版要等半天；
         * Pages 部署后约 40 秒可用，缓存 10 分钟，是三者里最均衡的。
         */
        const val DEFAULT_UPDATE_URL =
            "https://wuzyyds.github.io/ReplyMate-Android/update.json"

        const val K_BASE = "base_url"
        const val K_KEY = "api_key"
        const val K_MODEL = "model_name"
        const val K_REL = "default_relation"
        const val K_STYLE = "default_style"
        const val K_EMOJI = "default_emoji"
        const val K_IMG = "allow_image_upload"
        const val K_EVOLVE = "auto_evolve"
        const val K_UPDATE = "update_url"
    }
}
