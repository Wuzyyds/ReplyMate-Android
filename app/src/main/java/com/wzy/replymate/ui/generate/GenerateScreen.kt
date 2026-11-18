package com.wzy.replymate.ui.generate

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.rounded.AddAPhoto
import com.wzy.replymate.GenSeed
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.core.EmojiFilter
import com.wzy.replymate.core.Images
import com.wzy.replymate.data.LlmException
import com.wzy.replymate.data.ProfileCodec
import com.wzy.replymate.data.humanizeLlmError
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.domain.EmojiPolicy
import com.wzy.replymate.domain.GenRequest
import com.wzy.replymate.domain.GenResult
import com.wzy.replymate.domain.Goal
import com.wzy.replymate.domain.PolishInput
import com.wzy.replymate.domain.PolishTone
import com.wzy.replymate.domain.PostLength
import com.wzy.replymate.domain.PostScene
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.domain.Source
import com.wzy.replymate.domain.Style
import com.wzy.replymate.domain.TaskType
import com.wzy.replymate.service.ReplyAccessibilityService
import com.wzy.replymate.ui.CandidateCard
import com.wzy.replymate.ui.ChipRow
import com.wzy.replymate.ui.GhostButton
import com.wzy.replymate.ui.NoticeBar
import com.wzy.replymate.ui.PrimaryButton
import com.wzy.replymate.ui.SectionLabel
import com.wzy.replymate.ui.SegTabs
import com.wzy.replymate.ui.theme.Amber
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Red
import com.wzy.replymate.ui.theme.RedSoft
import com.wzy.replymate.ui.theme.Sub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GenUiState(
    val task: TaskType = TaskType.REPLY,
    val input: String = "",
    val relation: Relation = Relation.BOSS,
    val goal: Goal = Goal.BARGAIN,
    val scene: PostScene = PostScene.DAILY,
    val length: PostLength = PostLength.SHORT,
    val tone: PolishTone = PolishTone.POLITE,
    val polishInput: PolishInput = PolishInput.REWRITE,
    val style: Style = Style.STEADY,
    val emoji: EmojiPolicy = EmojiPolicy.NONE,
    val contactId: Long? = null,
    val contactName: String? = null,
    val profilePrompt: String? = null,
    val imageB64: String? = null,
    val loading: Boolean = false,
    val streamed: String = "",
    val candidates: List<Candidate> = emptyList(),
    val source: Source? = null,
    val notice: String? = null,
    val error: String? = null,
    val latency: Long = 0L
)

class GenerateViewModel(app: Application) : AndroidViewModel(app) {

    private val core = app as ReplyMateApp

    private val _state = MutableStateFlow(
        GenUiState(
            relation = core.settings.defaultRelation,
            style = core.settings.defaultStyle,
            emoji = core.settings.defaultEmoji
        )
    )
    val state: StateFlow<GenUiState> = _state.asStateFlow()

    private var seeded = false

    fun seed(task: TaskType) {
        if (seeded) return
        seeded = true
        _state.update {
            it.copy(
                task = task,
                input = if (GenSeed.text.isNotBlank()) GenSeed.text else it.input,
                relation = GenSeed.relation ?: it.relation,
                goal = GenSeed.goal ?: it.goal
            )
        }
        GenSeed.clear()
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }
    fun onTask(v: TaskType) = _state.update { it.copy(task = v, candidates = emptyList(), notice = null, error = null, source = null) }
    fun onRelation(v: Relation) = _state.update { it.copy(relation = v) }
    fun onGoal(v: Goal) = _state.update { it.copy(goal = v) }
    fun onScene(v: PostScene) = _state.update { it.copy(scene = v) }
    fun onLength(v: PostLength) = _state.update { it.copy(length = v) }
    fun onTone(v: PolishTone) = _state.update { it.copy(tone = v) }
    fun onPolishInput(v: PolishInput) = _state.update { it.copy(polishInput = v) }
    fun onStyle(v: Style) = _state.update { it.copy(style = v) }
    fun onEmoji(v: EmojiPolicy) = _state.update { it.copy(emoji = v) }

    fun onContact(id: Long?, name: String?, summary: String?) =
        _state.update { it.copy(contactId = id, contactName = name, profilePrompt = summary) }

    fun onImage(b64: String?) = _state.update { it.copy(imageB64 = b64) }
    fun onImageError() =
        _state.update { it.copy(error = "图片读取失败，换一张试试") }

    private fun toRequest(cur: GenUiState): GenRequest = GenRequest(
        taskType = cur.task,
        input = cur.input,
        relation = cur.relation,
        goal = cur.goal,
        scene = cur.scene,
        length = cur.length,
        tone = cur.tone,
        polishInput = cur.polishInput,
        style = cur.style,
        emoji = cur.emoji,
        profileSummary = cur.profilePrompt,
        imageBase64 = cur.imageB64
    )

    /** forceRefresh = true 用于「换一批」，绕过话术库缓存；首次生成优先命中缓存 */
    fun generate(forceRefresh: Boolean = false) {
        val cur = _state.value
        if (cur.input.isBlank() || cur.loading) return
        val req = toRequest(cur)
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, streamed = "", candidates = emptyList(), notice = null, error = null, source = null)
            }
            try {
                val res = core.generate.generate(req, forceRefresh) { partial ->
                    _state.update { s -> s.copy(streamed = partial) }
                }
                _state.update {
                    it.copy(
                        loading = false,
                        streamed = "",
                        candidates = res.candidates,
                        source = res.source,
                        notice = res.notice,
                        latency = res.latencyMs
                    )
                }
            } catch (e: LlmException) {
                _state.update { it.copy(loading = false, streamed = "", error = humanizeLlmError(e)) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, streamed = "", error = e.message ?: "生成失败") }
            }
        }
    }

    /** 模型不可用（欠费/Key/网络）时，用户点一下就能拿到 3 条离线模板，不是静默降级 */
    fun useTemplates() {
        val cur = _state.value
        if (cur.input.isBlank()) return
        val req = toRequest(cur)
        val res = GenResult(
            EmojiFilter.applyAll(core.templates.pick(req), cur.emoji),
            Source.TEMPLATE,
            0,
            "这次用离线模板 · 未调用模型"
        )
        _state.update {
            it.copy(
                loading = false,
                streamed = "",
                candidates = res.candidates,
                source = res.source,
                notice = res.notice,
                error = null,
                latency = 0
            )
        }
        viewModelScope.launch { core.generate.saveOffline(req, res) }
    }

}

@Composable
fun GenerateScreen(mode: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm: GenerateViewModel = viewModel()
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current

    // 发圈配图：系统相册选择 → 压缩 → base64
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val b64 = Images.toBase64(ctx, uri)
            vm.onImage(b64)
            if (b64 == null) vm.onImageError()
        }
    }
    val app = ctx.applicationContext as ReplyMateApp
    val contacts by app.db.contactDao().all().collectAsState(initial = emptyList())

    LaunchedEffect(mode) {
        vm.seed(runCatching { TaskType.valueOf(mode) }.getOrDefault(TaskType.REPLY))
    }

    Column(Modifier.fillMaxSize().background(Bg)) {

        // ---- 顶栏：模式切换常驻 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("‹", fontSize = 20.sp, color = Sub) }
            Box(Modifier.padding(start = 4.dp)) {
                SegTabs(
                    labels = TaskType.entries.map { it.label },
                    selected = TaskType.entries.indexOf(s.task),
                    onSelect = { vm.onTask(TaskType.entries[it]) }
                )
            }
            Spacer(Modifier.weight(1f))
            if (s.latency > 0) Text("${s.latency}ms", fontSize = 9.5.sp, color = Faint)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 润色有两种输入：有原话就改写；只有意图就直接写话
            if (s.task == TaskType.POLISH) {
                SegTabs(
                    labels = PolishInput.entries.map { it.label },
                    selected = PolishInput.entries.indexOf(s.polishInput),
                    modifier = Modifier.padding(top = 10.dp),
                    onSelect = { vm.onPolishInput(PolishInput.entries[it]) }
                )
            }

            // ---- 原文 ----
            SectionLabel(
                text = when (s.task) {
                    TaskType.REPLY -> "对话原文（最后一句是对方说的）"
                    TaskType.POST -> "我想说点啥（可留空）"
                    TaskType.POLISH ->
                        if (s.polishInput == PolishInput.INTENT) "我想要达到的效果" else "我原本想说的（可以很糙）"
                },
                trailing = "${s.input.length} / 3000"
            )
            OutlinedTextField(
                value = s.input,
                onValueChange = vm::onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when (s.task) {
                            TaskType.REPLY -> "粘贴微信 / BOSS / 飞书里的对话…"
                            TaskType.POST -> "周末去了趟山里，人少"
                            TaskType.POLISH ->
                                if (s.polishInput == PolishInput.INTENT) "不想跟他聊了，想委婉点又不失礼"
                                else "这活儿我不干，你别找我了"
                        },
                        fontSize = 12.sp,
                        color = Faint
                    )
                },
                minLines = 4,
                maxLines = 8,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = Ink),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFC9578C),
                    unfocusedBorderColor = Line,
                    focusedContainerColor = Color(0xFF242129),
                    unfocusedContainerColor = Color(0xFF242129)
                )
            )
            Text(
                "发送给模型前会自动隐藏手机号 / 身份证 / 银行卡 / 邮箱，本机显示不受影响",
                fontSize = 9.5.sp,
                color = Faint,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
            )

            // ---- 约束 ----
            when (s.task) {
                TaskType.REPLY -> {
                    SectionLabel("关系", "决定语气")
                    ChipRow(Relation.entries.map { it.label }, Relation.entries.indexOf(s.relation)) {
                        vm.onRelation(Relation.entries[it])
                    }
                    SectionLabel("目标", "缺一不可")
                    ChipRow(Goal.entries.map { it.label }, Goal.entries.indexOf(s.goal)) {
                        vm.onGoal(Goal.entries[it])
                    }
                    if (contacts.isNotEmpty()) {
                        SectionLabel("参考档案", if (s.contactName == null) "可选" else "已参考：${s.contactName}")
                        ChipRow(
                            listOf("不参考") + contacts.map { it.name },
                            selected = contacts.indexOfFirst { it.id == s.contactId } + 1
                        ) { i ->
                            if (i == 0) {
                                vm.onContact(null, null, null)
                            } else {
                                val c = contacts[i - 1]
                                val sm = ProfileCodec.decode(c.summaryJson)
                                val extra = listOfNotNull(
                                    c.taboo.takeIf { it.isNotBlank() }?.let { "禁忌：$it" },
                                    c.tone.takeIf { it.isNotBlank() }?.let { "语气：$it" },
                                    c.notes.takeIf { it.isNotBlank() }
                                ).joinToString("；")
                                vm.onContact(
                                    c.id,
                                    c.name,
                                    listOf(sm.toPrompt(), extra).filter { it.isNotBlank() }.joinToString("；")
                                )
                            }
                        }
                    }
                }
                TaskType.POST -> {
                    SectionLabel("配图", if (s.imageB64 != null) "已选图，会一起发给模型" else "可选")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardBg)
                            .clickable {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = "选择配图",
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (s.imageB64 != null) "已选配图 · 点这里换一张" else "从相册选一张，文案会围绕图写",
                                fontSize = 11.5.sp,
                                color = Ink,
                                modifier = Modifier.weight(1f)
                            )
                            if (s.imageB64 != null) {
                                Text("移除", fontSize = 11.sp, color = Red, modifier = Modifier.clickable { vm.onImage(null) })
                            }
                        }
                    }
                    SectionLabel("场景")
                    ChipRow(PostScene.entries.map { it.label }, PostScene.entries.indexOf(s.scene)) {
                        vm.onScene(PostScene.entries[it])
                    }
                    SectionLabel("篇幅")
                    ChipRow(PostLength.entries.map { it.label }, PostLength.entries.indexOf(s.length)) {
                        vm.onLength(PostLength.entries[it])
                    }
                }
                TaskType.POLISH -> {
                    SectionLabel("改成什么语气", "单选")
                    ChipRow(PolishTone.entries.map { it.label }, PolishTone.entries.indexOf(s.tone)) {
                        vm.onTone(PolishTone.entries[it])
                    }
                    Spacer(Modifier.height(10.dp))
                    NoticeBar(
                        if (s.polishInput == PolishInput.INTENT)
                            "只把话说到位，不编造时间 / 金额 / 承诺"
                        else
                            "只改说法，不改事实 · 不替你加承诺、加时间"
                    )
                }
            }

            // ---- 风格 + 表情 ----
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("风格")
                    SegTabs(Style.entries.map { it.label }, Style.entries.indexOf(s.style)) {
                        vm.onStyle(Style.entries[it])
                    }
                }
                Column(Modifier.width(120.dp)) {
                    SectionLabel("表情")
                    SegTabs(EmojiPolicy.entries.map { it.label }, EmojiPolicy.entries.indexOf(s.emoji)) {
                        vm.onEmoji(EmojiPolicy.entries[it])
                    }
                }
            }

            // ---- 错误 / 提示 ----
            val err = s.error
            if (err != null) {
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RedSoft)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Column {
                        Text(err, fontSize = 11.sp, color = Red, lineHeight = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "去设置 ›",
                                fontSize = 11.sp,
                                color = Red,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBg)
                                    .clickable { onOpenSettings() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "先用 3 条离线模板 ›",
                                fontSize = 11.sp,
                                color = Primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBg)
                                    .clickable { vm.useTemplates() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            val notice = s.notice
            if (notice != null) {
                Box(Modifier.padding(top = 12.dp)) { NoticeBar(notice) }
            }

            // ---- 生成中 ----
            if (s.loading) {
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg)
                        .padding(12.dp)
                ) {
                    Text("生成中…", fontSize = 10.sp, color = Amber)
                    Text(
                        s.streamed.ifBlank { "正在想 3 条说法…" },
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = Ink,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ---- 结果 ----
            if (s.candidates.isNotEmpty()) {
                SectionLabel(
                    "3 条候选",
                    if (s.source == Source.TEMPLATE) "离线模板" else "模型"
                )
                s.candidates.forEachIndexed { i, c ->
                    CandidateCard(
                        index = i,
                        candidate = c,
                        source = s.source ?: Source.MODEL,
                        // 发圈没有目标输入框，隐藏回填；其余模式退回聊天后自动填入
                        fillLabel = if (s.task == TaskType.POST) null else "回填",
                        copyLabel = if (s.task == TaskType.POST) "复制整段" else "复制",
                        whyPrefix = if (s.task == TaskType.POLISH) "改了什么" else "为什么这样说",
                        onCopy = { copyToClipboard(ctx, c.text) },
                        onFill = { fillToChat(ctx, c.text) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    when (s.task) {
                        TaskType.REPLY ->
                            "「回填」会把你带回聊天窗口并自动填入；没有读屏记录时自动复制（永不自动发送）"
                        TaskType.POLISH ->
                            "「回填」需要先读屏取过词；否则会复制，去聊天框长按粘贴"
                        TaskType.POST ->
                            "复制后去微信粘贴，图片只在选图时上传一次"
                    },
                    fontSize = 9.5.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
        }

        // ---- 底部 ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GhostButton(if (s.candidates.isEmpty()) "清空" else "换一批") {
                if (s.candidates.isEmpty()) vm.onInput("") else vm.generate(forceRefresh = true)
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = when {
                    s.loading -> "生成中…"
                    s.candidates.isEmpty() -> "生成 3 条"
                    else -> "再生成一批"
                },
                modifier = Modifier.weight(1f),
                enabled = s.input.isNotBlank() && !s.loading,
                onClick = {
                    // 已有结果时视为「再生成一批」，明确绕过缓存
                    vm.generate(forceRefresh = s.candidates.isNotEmpty())
                }
            )
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    setClipboard(ctx, text)
    Toast.makeText(ctx, "已复制，请长按粘贴", Toast.LENGTH_SHORT).show()
}

private fun setClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("replymate", text))
}

/**
 * App 内「回填」：
 * 先把文本放剪贴板兜底，再排一次无障碍回填并退回原聊天 App；
 * 无障碍服务检测到聊天窗口恢复后写入输入框。没有读屏记录就只复制。
 */
private fun fillToChat(ctx: Context, text: String) {
    setClipboard(ctx, text)
    if (!ReplyAccessibilityService.requestFill(text)) {
        Toast.makeText(ctx, "已复制，请长按输入框粘贴", Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(ctx, "已复制 · 回到聊天窗口会自动填入", Toast.LENGTH_SHORT).show()
    ctx.findActivity()?.moveTaskToBack(true)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
