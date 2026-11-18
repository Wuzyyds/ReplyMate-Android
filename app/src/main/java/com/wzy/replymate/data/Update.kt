package com.wzy.replymate.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 版本清单（放在任意静态地址上，比如 GitHub raw / 对象存储）：
 *
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "notes": "新增朋友圈配图；修复悬浮球遮挡",
 *   "apkUrl": "https://example.com/replymate-1.1.0.apk",
 *   "minSupported": 1
 * }
 *
 * versionCode 比本机大 → 提示更新；minSupported > 本机 → 强提示（可选，目前只做提示不禁用）。
 * 侧载 APK 没有商店，只能靠这份 JSON 主动告知，所以 URL 留空就等于关闭该功能。
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val notes: String = "",
    val apkUrl: String = "",
    val minSupported: Int = 0
)

/** 给 Compose 读的全局状态，值变了首页横幅会自动出现 */
object UpdateUi {
    val available = mutableStateOf<UpdateInfo?>(null)
}

object UpdateChecker {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastCheckAt: Long = 0L

    fun currentVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(1)

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    suspend fun check(context: Context, url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val body = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return@withContext null

        val info = runCatching { json.decodeFromString(UpdateInfo.serializer(), body) }.getOrNull()
            ?: return@withContext null

        lastCheckAt = System.currentTimeMillis()
        val newer = info.versionCode > currentVersionCode(context)
        UpdateUi.available.value = if (newer) info else null
        UpdateUi.available.value
    }

    /** 冷启动最多一天查一次，别每次开 App 都发请求 */
    suspend fun checkIfDue(context: Context, url: String): UpdateInfo? {
        if (url.isBlank()) return null
        if (System.currentTimeMillis() - lastCheckAt < 24 * 3600_000L) return UpdateUi.available.value
        return check(context, url)
    }
}
