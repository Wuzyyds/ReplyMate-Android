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

    var defaultRelation: Relation
        get() = enumOf(sp.getString(K_REL, null), Relation.FRIEND)
        set(v) = sp.edit().putString(K_REL, v.name).apply()

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

    /** 版本清单地址（静态 JSON）；留空 = 关闭更新检查 */
    var updateUrl: String
        get() = sp.getString(K_UPDATE, "") ?: ""
        set(v) = sp.edit().putString(K_UPDATE, v.trim()).apply()

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
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
