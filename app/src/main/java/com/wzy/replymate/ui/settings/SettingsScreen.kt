package com.wzy.replymate.ui.settings

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.data.LlmException
import com.wzy.replymate.data.humanizeLlmError
import com.wzy.replymate.core.ModelCatalog
import com.wzy.replymate.data.UpdateChecker
import com.wzy.replymate.data.prefs.ModelConfig
import com.wzy.replymate.data.prefs.ModelPresets
import com.wzy.replymate.domain.EmojiPolicy
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.domain.Style
import com.wzy.replymate.ui.ChipRow
import com.wzy.replymate.ui.GhostButton
import com.wzy.replymate.ui.PrimaryButton
import com.wzy.replymate.ui.SectionLabel
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Green
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.Red
import com.wzy.replymate.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onOpenTemplates: () -> Unit = {}, onOpenContacts: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ReplyMateApp
    val store = app.settings
    val scope = rememberCoroutineScope()

    var base by remember { mutableStateOf(store.config.baseUrl) }
    var key by remember { mutableStateOf(store.config.apiKey) }
    var model by remember { mutableStateOf(store.config.model) }

    // 服务商清单 + 当前选中项（按已保存的 baseUrl 反查，切回来不用重选）
    val providers = remember { ModelCatalog.all(ctx) }
    var providerIdx by remember {
        mutableStateOf(
            ModelCatalog.matchByBase(ctx, store.config.baseUrl)
                ?.let { p -> providers.indexOf(p) } ?: -1
        )
    }

    var status by remember { mutableStateOf("未检测") }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }

    // Key 一变就自己认，用户什么都不用点（停手 800ms 才开始，避免逐字触发）
    var lastKey by remember { mutableStateOf(store.config.apiKey) }
    LaunchedEffect(key) {
        val k = key.trim()
        if (k.length < 16 || k == lastKey) return@LaunchedEffect
        delay(800)
        lastKey = k
        detecting = true
        val hit = withContext(Dispatchers.IO) {
            providers.firstOrNull { p ->
                runCatching { app.llm.ping(ModelConfig(p.baseUrl, k, p.model)) }.isSuccess
            }
        }
        detecting = false
        if (hit != null) {
            base = hit.baseUrl
            model = hit.model
            providerIdx = providers.indexOf(hit)
        }
    }

    // SP 不是可观察状态，本地镜像一份，改动后立刻回写
    var relation by remember { mutableStateOf(store.defaultRelation) }
    var style by remember { mutableStateOf(store.defaultStyle) }
    var emoji by remember { mutableStateOf(store.defaultEmoji) }
    var allowImg by remember { mutableStateOf(store.allowImageUpload) }
    var evolve by remember { mutableStateOf(store.autoEvolve) }

    var advanced by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf(store.updateUrl) }
    var updStatus by remember { mutableStateOf("") }

    // 复制 Key 必须先过系统密码/生物识别，验证通过才写剪贴板
    val confirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("api_key", key))
            Toast.makeText(ctx, "已复制 API Key", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "未通过验证，未复制", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestCopy() {
        if (key.isBlank()) {
            Toast.makeText(ctx, "还没有填 Key", Toast.LENGTH_SHORT).show()
            return
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(ctx, "手机没设锁屏密码，无法校验", Toast.LENGTH_LONG).show()
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent("验证后复制 API Key", "验证通过才会写入剪贴板")
        if (intent == null) {
            Toast.makeText(ctx, "当前设备不支持密码验证", Toast.LENGTH_SHORT).show()
            return
        }
        confirmLauncher.launch(intent)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

        // ---- API KEY：只让用户填这一项，厂家和地址全自动 ----
        SectionLabel("API Key", "粘贴即可，其余自动配好")

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("粘贴 API Key（sk-… 或 xxx.yyy）", fontSize = 11.sp, color = Faint) },
            singleLine = true,
            // 脱敏：永远只显示密码点，不提供“显示明文”开关
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "复制 Key",
                    tint = Muted,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { requestCopy() }
                        .padding(8.dp)
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = Ink),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )

        Text(
            "永不显示明文 · 复制需验证手机密码",
            fontSize = 9.5.sp,
            color = Faint,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )

        // 自动识别：粘上 Key 就自己认，不用点任何按钮
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = if (detecting) Primary else Faint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    detecting -> "正在识别这是哪家的 Key…"
                    key.isBlank() -> "粘上 Key 就行，厂家和地址我来认"
                    providerIdx in providers.indices -> "已识别为 ${providers[providerIdx].name}，地址和模型名都填好了"
                    base.isNotBlank() -> "当前是自定义地址，可在高级设置里核对"
                    else -> "这个 Key 没认出来，检查是否复制完整"
                },
                fontSize = 9.5.sp,
                color = if (detecting) Primary else Faint
            )
        }

        // 自检 + 保存 放同一行
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("连通性自检", height = 38, enabled = !checking) {
                if (checking) return@PrimaryButton
                checking = true
                status = "检测中…"
                statusOk = null
                scope.launch {
                    val cfg = ModelConfig(base, key, model)
                    val result = withContext(Dispatchers.IO) {
                        runCatching { app.llm.ping(cfg) }
                    }
                    checking = false
                    result.onSuccess {
                        status = "成功 · ${it}ms"
                        statusOk = true
                    }.onFailure { e ->
                        status = if (e is LlmException) humanizeLlmError(e) else (e.message ?: "失败")
                        statusOk = false
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("保存配置", Modifier.weight(1f), height = 38) {
                store.config = ModelConfig(base, key, model)
                status = "已保存"
                statusOk = true
            }
        }

        Text(
            status,
            fontSize = 10.sp,
            color = when (statusOk) {
                true -> Green
                false -> Red
                else -> Faint
            },
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )

        Text(
            if (advanced) "高级设置  ▴" else "高级设置  ▾",
            fontSize = 10.5.sp,
            color = Muted,
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { advanced = !advanced }
                .padding(horizontal = 4.dp, vertical = 4.dp)
        )

        if (advanced) {
            Spacer(Modifier.height(6.dp))
            Field("API Base", base) { base = it }
            Spacer(Modifier.height(8.dp))
            Field("模型名", model) { model = it }
            Spacer(Modifier.height(8.dp))
            Field("更新清单 URL（可选，留空=关闭）", updateUrl) {
                updateUrl = it
                store.updateUrl = it
            }
            Text(
                "这是一个返回 JSON 的网址，不是应用商店链接。留空就关闭自动检查，侧载覆盖安装即可。",
                fontSize = 9.5.sp,
                color = Faint,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "模板：{\"versionCode\":19,\"versionName\":\"1.0.19\",\"notes\":\"修复说明\",\"apkUrl\":\"https://你的地址/replymate-1.0.19.apk\"}",
                fontSize = 9.sp,
                color = Faint,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            GhostButton("复制清单模板") {
                val template = """
                    {
                      "versionCode": 19,
                      "versionName": "1.0.19",
                      "notes": "修复说明",
                      "apkUrl": "https://你的地址/replymate-1.0.19.apk",
                      "minSupported": 1
                    }
                """.trimIndent()
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("update_manifest", template))
                Toast.makeText(ctx, "模板已复制，传到任意静态地址后把 URL 填到上面", Toast.LENGTH_LONG).show()
            }
        }

        // ---- 话术库 ----
        SectionLabel("话术库", "断网可用 · 不调模型")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable { onOpenTemplates() }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("高频场景话术", fontSize = 11.5.sp, color = Ink)
                Text(
                    "被夸怎么回、拒绝加班、催进度…点开浏览复制",
                    fontSize = 9.5.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text("›", fontSize = 16.sp, color = Faint)
        }

        // ---- 联系人档案 ----
        SectionLabel("联系人档案", "建档后生成时自动带上")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable { onOpenContacts() }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("谁、什么关系、什么话不能说", fontSize = 11.5.sp, color = Ink)
                Text(
                    "聊久了会自己提炼画像，一次只改一条，可撤销",
                    fontSize = 9.5.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text("›", fontSize = 16.sp, color = Faint)
        }

        // ---- 数据与隐私 ----
        SectionLabel("数据与隐私")

        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("允许上传图片", fontSize = 11.5.sp, color = Ink)
                Text("仅朋友圈带图用，v1.1 上线；回复/OCR 永不上传", fontSize = 9.5.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(
                checked = allowImg,
                onCheckedChange = { allowImg = it; store.allowImageUpload = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = Color.White)
            )
        }

        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("画像自动演化", fontSize = 11.5.sp, color = Ink)
                Text("只在采纳后更新，一次一条，可撤销", fontSize = 9.5.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(
                checked = evolve,
                onCheckedChange = { evolve = it; store.autoEvolve = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = Color.White)
            )
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("导出记录") {
                scope.launch {
                    val rows = app.db.generationDao().recent().first()
                    val sb = StringBuilder("ReplyMate 导出 · ${rows.size} 条\n\n")
                    rows.forEach { r ->
                        sb.append("[").append(r.taskType).append("] ")
                            .append(r.relation).append(" / ").append(r.goal).append(" / ").append(r.style)
                            .append('\n')
                            .append("原文：").append(r.input.replace("\n", " / ")).append('\n')
                            .append("候选：").append(r.candidatesJson).append("\n\n")
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "ReplyMate 记录导出")
                        putExtra(Intent.EXTRA_TEXT, sb.toString())
                    }
                    ctx.startActivity(Intent.createChooser(send, "导出到"))
                }
            }
            GhostButton("清空全部记录") {
                scope.launch { app.db.generationDao().clear() }
            }
        }

        // ---- 关于 ----
        SectionLabel("关于")
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Column {
                Text(
                    "版本 ${UpdateChecker.currentVersionName(ctx)}（${UpdateChecker.currentVersionCode(ctx)}）",
                    fontSize = 11.5.sp,
                    color = Ink
                )
                val hasUpdateUrl = store.updateUrl.isNotBlank()
                Text(
                    if (hasUpdateUrl) "更新源已配置 · 冷启动每天检查一次"
                    else "侧载版：新 APK 直接覆盖安装即可，不需要更新源",
                    fontSize = 9.5.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (hasUpdateUrl) {
                    // 和首页横幅共用同一个「发现新版本」状态：这边检测到，那边也会出现
                    val pending by com.wzy.replymate.data.UpdateUi.available
                    var downloading by remember { mutableStateOf<Int?>(null) }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 三态：有新版 → 立即更新；正在下 → 显示百分比；否则 → 检查更新
                        when {
                            downloading != null -> GhostButton("下载中 ${downloading}%") { /* 下载中不可重复点 */ }
                            pending != null -> GhostButton("立即更新") {
                                val info = pending ?: return@GhostButton
                                if (info.apkUrl.isBlank()) {
                                    updStatus = "该版本没有提供安装包地址"
                                    return@GhostButton
                                }
                                if (!com.wzy.replymate.data.ApkInstaller.canInstall(ctx)) {
                                    updStatus = "请先允许「安装未知应用」"
                                    com.wzy.replymate.data.ApkInstaller.openInstallPermission(ctx)
                                    return@GhostButton
                                }
                                scope.launch {
                                    downloading = 0
                                    val err = com.wzy.replymate.data.ApkInstaller.downloadAndInstall(ctx, info.apkUrl) {
                                        downloading = it
                                    }
                                    downloading = null
                                    updStatus = err ?: "下载完成，请在系统弹窗点「更新」完成覆盖"
                                }
                            }
                            else -> GhostButton("检查更新") {
                                scope.launch {
                                    updStatus = "检查中…"
                                    val info = runCatching { UpdateChecker.check(ctx, store.updateUrl) }.getOrNull()
                                    updStatus = when {
                                        info != null -> "发现新版本 ${info.versionName}"
                                        else -> "已是最新（${UpdateChecker.currentVersionName(ctx)}）"
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // 有新版时优先显示「版本号 + 这次改了什么」，比只报一句"发现新版本"有用
                            when {
                                downloading != null ->
                                    "正在下载 ${pending?.versionName.orEmpty()}，完成后会自动弹出安装界面"
                                pending != null -> buildString {
                                    append("新版本 ").append(pending?.versionName.orEmpty())
                                    val n = pending?.notes.orEmpty()
                                    if (n.isNotBlank()) append(" · ").append(n)
                                }
                                else -> updStatus
                            },
                            fontSize = 9.5.sp,
                            color = if (pending != null) Primary else Faint,
                            lineHeight = 14.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        "想自动检查更新？在「高级设置」填一个返回 JSON 的网址（可选）",
                        fontSize = 9.5.sp,
                        color = Faint,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = Ink),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFFC9578C),
    unfocusedBorderColor = Line,
    focusedContainerColor = Color(0xFF242129),
    unfocusedContainerColor = Color(0xFF242129)
)
