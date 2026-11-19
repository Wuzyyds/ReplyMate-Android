package com.wzy.replymate.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wzy.replymate.GenSeed
import com.wzy.replymate.PasteSignal
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.data.ScenarioGen
import com.wzy.replymate.data.ScenarioStore
import com.wzy.replymate.domain.Goal
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.domain.TaskType
import com.wzy.replymate.service.OverlayService
import com.wzy.replymate.service.ReplyAccessibilityService
import com.wzy.replymate.ui.theme.AmberSoft
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Green
import com.wzy.replymate.ui.theme.GreenSoft
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Sub
import com.wzy.replymate.ui.theme.softShadow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenGenerate: (TaskType) -> Unit, onOpenOcr: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ReplyMateApp
    val rows by app.db.generationDao().recent().collectAsState(initial = emptyList())
    val weekCount = rows.count { it.createdAt >= System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
    var overlayOn by remember { mutableStateOf(OverlayService.running) }
    var refreshTick by remember { mutableStateOf(0) }
    var pendingEnable by remember { mutableStateOf(false) }
    var accOn by remember { mutableStateOf(ReplyAccessibilityService.isEnabled(ctx)) }
    // 悬浮球菜单里点了「读取剪贴板」，主卡按钮短暂变成绿色「已读取 ✓」
    var pasted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        PasteSignal.events.collect {
            pasted = true
            kotlinx.coroutines.delay(2000)
            pasted = false
        }
    }
    var scenarios by remember { mutableStateOf(ScenarioStore.load(ctx)) }
    var scenarioBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accOn = ReplyAccessibilityService.isEnabled(ctx)
                val granted = Settings.canDrawOverlays(ctx)
                val want = ctx.getSharedPreferences("replymate", Context.MODE_PRIVATE)
                    .getBoolean("overlay_enabled", false)
                if (granted && (pendingEnable || (want && !OverlayService.running))) {
                    OverlayService.start(ctx)
                    overlayOn = true
                    pendingEnable = false
                } else {
                    overlayOn = OverlayService.running
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
    // 顶部品牌光晕：对应设计稿 .phone::before，固定不随滚动
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0x4DB23BD9), Color(0x1FE8567A), Color(0x00000000)),
                        center = Offset(size.width / 2f, -size.height * 0.15f),
                        radius = size.width * 1.05f
                    )
                )
            }
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // 有新版本才出现的一条窄横幅：点一下下载并拉起安装器覆盖当前版本
        val upd by com.wzy.replymate.data.UpdateUi.available
        upd?.let { u ->
            var progress by remember { mutableStateOf<Int?>(null) }
            val scope = rememberCoroutineScope()
            Row(
                Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PrimarySoft)
                    .clickable {
                        if (progress != null) return@clickable
                        if (u.apkUrl.isBlank()) {
                            Toast.makeText(ctx, "更新地址为空", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        // 没有「安装未知应用」权限就先带用户去开，回来再点一次
                        if (!com.wzy.replymate.data.ApkInstaller.canInstall(ctx)) {
                            Toast.makeText(ctx, "请先允许「安装未知应用」", Toast.LENGTH_LONG).show()
                            com.wzy.replymate.data.ApkInstaller.openInstallPermission(ctx)
                            return@clickable
                        }
                        progress = 0
                        scope.launch {
                            val err = com.wzy.replymate.data.ApkInstaller.downloadAndInstall(ctx, u.apkUrl) {
                                progress = it
                            }
                            progress = null
                            if (err != null) {
                                Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (progress != null) "更新 ${progress}%" else "新版本 ${u.versionName}",
                    fontSize = 11.5.sp,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        progress != null -> "正在下载安装包，完成后会弹出安装界面"
                        u.notes.isNotBlank() -> u.notes
                        else -> "点这里下载安装"
                    },
                    fontSize = 10.sp,
                    color = Primary.copy(alpha = 0.72f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (progress != null) "$progress%" else "更新 ›",
                    fontSize = 10.5.sp,
                    color = Primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("下午好", fontSize = 27.sp, fontWeight = FontWeight.Medium, color = Ink, letterSpacing = 0.2.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.13f), CircleShape)
                    .clickable {
                        // 刷新：重新读一次无障碍/悬浮球状态与服务存活情况
                        accOn = ReplyAccessibilityService.isEnabled(ctx)
                        overlayOn = OverlayService.running
                        refreshTick++
                    }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新状态",
                    tint = Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            if (rows.isEmpty()) "把刚才没回的那条消息，粘进来试试"
            else "这周已经练了 $weekCount 次表达",
            fontSize = 11.5.sp,
            color = Muted,
            modifier = Modifier.padding(top = 5.dp)
        )

        Spacer(Modifier.height(16.dp))

        // 主入口：严格按设计稿 —— 线性底 + 右上紫光 + 左下玫红光 + 顶部内高光
        Column(
            Modifier
                .fillMaxWidth()
                .softShadow(18.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF241A36), Color(0xFF191123), Color(0xFF141019))
                    )
                )
                .drawBehind {
                    val c1 = Offset(size.width * 0.88f, -size.height * 0.08f)
                    val r1 = size.width * 1.25f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color(0x52B23BD9),
                            0.58f to Color(0x00000000),
                            center = c1,
                            radius = r1
                        ),
                        radius = r1,
                        center = c1
                    )
                    val c2 = Offset(size.width * 0.04f, size.height * 1.08f)
                    val r2 = size.width * 0.95f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color(0x38E8567A),
                            0.56f to Color(0x00000000),
                            center = c2,
                            radius = r2
                        ),
                        radius = r2,
                        center = c2
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.07f),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.085f), RoundedCornerShape(22.dp))
                .clickable {
                    GenSeed.text = readClipboard(ctx)
                    onOpenGenerate(TaskType.REPLY)
                }
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "QUICK START",
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp,
                    color = Color(0xFFF2EEF7).copy(alpha = 0.42f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 7.dp)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFC77DFF), Color(0xFFE8567A), Color(0xFFF59B3C))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(11.dp))

            // 主卡文案轮播：同一张卡里换痛点场景，风格不变，每 4.5 秒一句
            var heroIndex by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(4500)
                    heroIndex = (heroIndex + 1) % HERO_COPIES.size
                }
            }
            val copy = HERO_COPIES[heroIndex]
            // 固定高度：文案长短不一，不固定会让主卡高度跳动
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                androidx.compose.animation.Crossfade(
                    targetState = copy,
                    animationSpec = androidx.compose.animation.core.tween(520),
                    label = "hero"
                ) { c ->
                    Column {
                        Text(
                            c.line1,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            lineHeight = 32.sp,
                            letterSpacing = 0.25.sp
                        )
                        Text(
                            c.line2,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 32.sp,
                            letterSpacing = 0.25.sp,
                            style = TextStyle(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFFC77DFF), Color(0xFFFF8FA3), Color(0xFFFFC078))
                                )
                            )
                        )
                        Text(
                            c.desc,
                            fontSize = 12.sp,
                            color = Color(0xFFF2EEF7).copy(alpha = 0.52f),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            Box(
                Modifier
                    .padding(top = 15.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (pasted) {
                            Modifier
                                .background(Color(0x1A5ED6B4))
                                .border(1.dp, Color(0x8C5ED6B4), RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                                .background(Color.White.copy(alpha = 0.055f))
                                .border(1.dp, Color.White.copy(alpha = 0.17f), RoundedCornerShape(14.dp))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (pasted) "已读取 ✓" else "读取剪贴板",
                    fontSize = 14.sp,
                    color = if (pasted) Color(0xFF7FE3C4) else Color.White,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                )
            }
        }

        // 三宫格：图标在上、标题在下，对应设计稿 .tile
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SmallEntry("截图", "识别后可改", Icons.Rounded.CropFree, Color(0xFFC77DFF), Modifier.weight(1f)) {
                onOpenOcr()
            }
            SmallEntry("朋友圈", "配图写文案", Icons.Rounded.PhotoCamera, Color(0xFFFFB067), Modifier.weight(1f)) {
                onOpenGenerate(TaskType.POST)
            }
            SmallEntry("润色", "缺话变体面", Icons.Rounded.AutoFixHigh, Color(0xFF5ED6B4), Modifier.weight(1f)) {
                onOpenGenerate(TaskType.POLISH)
            }
        }

        // 悬浮球状态
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .softShadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .clickable(enabled = !accOn) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            !overlayOn -> Muted
                            !accOn -> Color(0xFFE0A03A)
                            else -> Green
                        }
                    )
            )
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Text(
                    if (overlayOn) "悬浮球已开启" else "开启悬浮球",
                    fontSize = 11.5.sp,
                    color = Ink,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    when {
                        !accOn -> "读屏未开启 · 点此去开启（否则读不到聊天）"
                        overlayOn -> "点球取词 · 下拉通知栏也能用 · 读不到可粘贴"
                        else -> "在任意界面一键唤出"
                    },
                    fontSize = 10.sp,
                    color = if (!accOn) Color(0xFFC08A2E) else Muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            GradientSwitch(
                checked = overlayOn,
                onToggle = { want ->
                    if (want) {
                        if (Settings.canDrawOverlays(ctx)) {
                            OverlayService.start(ctx)
                            overlayOn = true
                        } else {
                            pendingEnable = true
                            Toast.makeText(ctx, "请允许「显示在其他应用上层」", Toast.LENGTH_SHORT).show()
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + ctx.packageName)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else {
                        OverlayService.stop(ctx)
                        overlayOn = false
                    }
                }
            )
        }

        // 场景预设：解决「不知道输入什么」；AI 按常用 App 生成，换一批可重刷
        Column(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .softShadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "不知道输入什么？点一个试试",
                    fontSize = 11.sp,
                    color = Muted,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !scenarioBusy) {
                            scenarioBusy = true
                            scope.launch {
                                val list = runCatching {
                                    ScenarioGen.next(ctx, app.llm, app.settings)
                                }.getOrDefault(emptyList())
                                scenarioBusy = false
                                if (list.isNotEmpty()) {
                                    scenarios = list
                                } else {
                                    Toast.makeText(
                                        ctx,
                                        "没生成成功，先用备用场景；检查设置里的 Key",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "换一批场景",
                        tint = if (scenarioBusy) Faint else Primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (scenarioBusy) "生成中…" else "换一批",
                        fontSize = 10.sp,
                        color = if (scenarioBusy) Faint else Primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            scenarios.take(3).forEach { s ->
                val r = runCatching { Relation.valueOf(s.relation) }.getOrDefault(Relation.FRIEND)
                val g = runCatching { Goal.valueOf(s.goal) }.getOrDefault(Goal.KEEP)
                ScenarioRow(s.title, "${r.label} · ${g.label}") {
                    GenSeed.text = s.input
                    GenSeed.relation = r
                    GenSeed.goal = g
                    onOpenGenerate(TaskType.REPLY)
                }
            }
        }

        // 本周数据：只有真的用过才出现，首次使用不摆一排 0 和 —
        if (rows.isNotEmpty()) {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .softShadow(8.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .padding(14.dp)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("本周", fontSize = 10.5.sp, color = Muted, letterSpacing = 0.4.sp, modifier = Modifier.weight(1f))
                    Text("本地统计，不上传", fontSize = 10.5.sp, color = Faint)
                }
                Row(Modifier.padding(top = 10.dp)) {
                    Stat("$weekCount", "次生成", Modifier.weight(1f))
                    Stat("${rows.size}", "累计记录", Modifier.weight(1f))
                    Stat("${rows.firstOrNull()?.latencyMs ?: 0}ms", "最近耗时", Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
    }
}

@Composable
private fun ScenarioRow(title: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, color = Ink)
            Text(tag, fontSize = 10.sp, color = Faint, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 15.sp, color = Faint)
    }
}

@Composable
private fun SmallEntry(
    title: String,
    sub: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.038f))
            .border(1.dp, Color.White.copy(alpha = 0.072f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 14.dp)
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
            letterSpacing = 0.1.sp,
            modifier = Modifier.padding(top = 11.dp)
        )
        Text(
            sub,
            fontSize = 10.5.sp,
            color = Muted,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.4).sp)
        Text(label, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 主卡轮播的一句文案：两行标题 + 一行说明 */
private data class HeroCopy(val line1: String, val line2: String, val desc: String)

/**
 * 首页主卡的轮播文案。都是同一类痛点——「想表达但怕说错」，
 * 轮换只是换场景，不换语气，避免像广告条。
 */
private val HERO_COPIES = listOf(
    HeroCopy("粘贴一段对话", "回得体面", "提取对话 → 生成 3 条建议 → 一键填入输入框"),
    HeroCopy("不想答应", "又怕撕破脸", "把拒绝说得体面，不伤关系也不委屈自己"),
    HeroCopy("想安慰他", "又怕说错话", "共情但不越界，暖到点上不尴尬"),
    HeroCopy("被追问", "不知道怎么接", "三句话把场面圆回来，不冷场也不失礼"),
    HeroCopy("想催进度", "又怕显得急", "催得客气又不含糊，事办成关系还在")
)

private fun readClipboard(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx).toString()
}

/** 渐变开关：设计稿里开着是紫→玫红渐变，Material3 的 Switch 只支持单色轨道 */
@Composable
private fun GradientSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (checked) {
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xFFB23BD9), Color(0xFFE8567A)))
                    )
                } else {
                    Modifier.background(Color.White.copy(alpha = 0.1f))
                }
            )
            .clickable { onToggle(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
