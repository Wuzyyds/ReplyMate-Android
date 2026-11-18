package com.wzy.replymate.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：下载 APK → 交给系统安装器覆盖安装。
 *
 * 关于「自动覆盖」的边界，必须说清楚：
 * Android 不允许普通 App 静默安装或静默覆盖自己，否则任何应用都能偷偷把自己换掉。
 * 所以能做到的极限是「下载完自动拉起系统安装界面，用户点一次『更新』完成覆盖」。
 * 这一下点击无法绕过，除非应用被装成系统应用（需要 root 或定制 ROM）。
 */
object ApkInstaller {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 是否已被允许「安装未知来源应用」 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳转系统授权页，让用户为本应用打开「安装未知应用」 */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 下载并拉起安装器。
     * @return null 表示已成功拉起安装器；否则返回给用户看的错误文案。
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext "更新地址为空"

        val dir = File(context.externalCacheDir ?: context.cacheDir, "update").apply { mkdirs() }
        // 覆盖旧包，避免缓存占满
        val apk = File(dir, "replymate-new.apk")
        if (apk.exists()) apk.delete()

        val failure = runCatching {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("空响应体")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }.exceptionOrNull()

        if (failure != null) {
            return@withContext "下载失败：${failure.message ?: "网络异常"}"
        }
        // 44MB 的包，明显过小就是下到了错误页（比如 404 的 HTML）
        if (!apk.exists() || apk.length() < 1024 * 1024) {
            return@withContext "安装包不完整（${apk.length() / 1024} KB），可能地址失效"
        }

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .exceptionOrNull()
            ?.let { return@withContext "无法启动安装器：${it.message}" }

        null
    }
}
