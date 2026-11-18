package com.wzy.replymate.ui.templates

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.data.TplItem
import com.wzy.replymate.data.db.GenerationEntity
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.ui.EmptyHint
import com.wzy.replymate.ui.SegTabs
import com.wzy.replymate.ui.Tag
import com.wzy.replymate.ui.theme.AmberText
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Sub
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val candidateSerializer = ListSerializer(Candidate.serializer())
private val rowJson = Json { ignoreUnknownKeys = true }

private val TAB_LABELS = listOf("我的生成", "收藏", "内置模板")
private const val TAB_SAVED = 0
private const val TAB_FAV = 1
private const val TAB_BUILTIN = 2

private val FILTERS = listOf(
    "ALL" to "全部",
    "BOSS" to "领导",
    "COLLEAGUE" to "同事",
    "CLIENT" to "客户",
    "FRIEND" to "朋友",
    "FLIRT" to "暧昧",
    "FAMILY" to "家人",
    "HR" to "HR"
)

@Composable
fun TemplatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ReplyMateApp
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    val saved by app.db.generationDao().saved().collectAsState(initial = emptyList())
    val favorites by app.db.generationDao().favorites().collectAsState(initial = emptyList())
    val builtIn = remember { app.templates.all() }

    var tab by remember { mutableStateOf(TAB_SAVED) }
    var filter by remember { mutableStateOf("ALL") }

    val builtInFiltered = remember(filter, builtIn) {
        if (filter == "ALL") builtIn else builtIn.filter { it.relation == filter }
    }
    val savedSource = if (tab == TAB_FAV) favorites else saved
    val savedFiltered = remember(tab, filter, savedSource) {
        if (filter == "ALL") savedSource else savedSource.filter { it.relation == filter }
    }

    val count = if (tab == TAB_BUILTIN) builtInFiltered.size else savedFiltered.size

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("‹", fontSize = 20.sp, color = Sub) }
            Text(
                "话术库",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$count 条 · " + if (tab == TAB_BUILTIN) "断网可用" else "本地保存",
                fontSize = 9.5.sp,
                color = Faint
            )
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SegTabs(TAB_LABELS, tab) { tab = it }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FILTERS.forEach { (key, label) ->
                val on = filter == key
                Text(
                    label,
                    fontSize = 11.5.sp,
                    color = if (on) Color.White else Sub,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) Primary else Color(0xFF242129))
                        .border(1.dp, if (on) Primary else Line, RoundedCornerShape(9.dp))
                        .clickable { filter = key }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        if (tab == TAB_BUILTIN) {
            if (builtInFiltered.isEmpty()) {
                EmptyHint("这个分类还没有内置话术")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(builtInFiltered, key = { it.text }) { t ->
                        TplCard(t) { copy(ctx, t.text) }
                    }
                }
            }
        } else {
            if (savedFiltered.isEmpty()) {
                EmptyHint(
                    if (tab == TAB_FAV) "还没有收藏。点话术卡右上角的星标即可收藏"
                    else "还没有 AI 生成的话术。去首页生成一次，会自动存到这里"
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedFiltered, key = { it.id }) { row ->
                        SavedBatchCard(
                            row = row,
                            time = fmt.format(Date(row.createdAt)),
                            onFavorite = {
                                scope.launch {
                                    app.db.generationDao().setFavorite(row.id, !row.favorite)
                                }
                            },
                            onCopyOne = { copy(ctx, it) },
                            onCopyAll = { list -> copy(ctx, list.joinToString("\n") { it.text }) },
                            onDelete = {
                                scope.launch { app.db.generationDao().delete(row.id) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedBatchCard(
    row: GenerationEntity,
    time: String,
    onFavorite: () -> Unit,
    onCopyOne: (String) -> Unit,
    onCopyAll: (List<Candidate>) -> Unit,
    onDelete: () -> Unit
) {
    val candidates: List<Candidate> = remember(row.id) {
        runCatching { rowJson.decodeFromString(candidateSerializer, row.candidatesJson) }
            .getOrDefault(emptyList())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(taskLabel(row.taskType), Primary, PrimarySoft)
            Spacer(Modifier.width(6.dp))
            Tag(relLabel(row.relation), Sub, Color(0xFF2A2730))
            Spacer(Modifier.weight(1f))
            Text(time, fontSize = 9.5.sp, color = Faint)
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (row.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (row.favorite) "取消收藏" else "收藏",
                tint = if (row.favorite) Color(0xFFE0A03A) else Faint,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onFavorite)
                    .padding(3.dp)
            )
        }

        Text(
            row.input.replace("\n", " / ").take(70),
            fontSize = 11.sp,
            color = Sub,
            lineHeight = 16.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp)
        )

        candidates.forEachIndexed { i, c ->
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PrimarySoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1}", fontSize = 9.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).padding(start = 7.dp)) {
                    Text(c.text, fontSize = 12.5.sp, lineHeight = 19.sp, color = Ink)
                    if (c.why.isNotBlank()) {
                        Text(
                            "为什么 · ${c.why}",
                            fontSize = 9.5.sp,
                            lineHeight = 14.sp,
                            color = AmberText,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                Text(
                    "复制",
                    fontSize = 10.sp,
                    color = Primary,
                    modifier = Modifier
                        .padding(start = 6.dp, top = 1.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(PrimarySoft)
                        .clickable { onCopyOne(c.text) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Row(
            Modifier.padding(top = 9.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (row.source == "MODEL") "模型" else "离线模板") + " · ${row.latencyMs}ms",
                fontSize = 9.sp,
                color = Faint
            )
            Spacer(Modifier.weight(1f))
            Text(
                "删除",
                fontSize = 9.5.sp,
                color = Faint,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "复制 3 条",
                fontSize = 10.5.sp,
                color = Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrimarySoft)
                    .clickable { onCopyAll(candidates) }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun TplCard(t: TplItem, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(15.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(relLabel(t.relation), Primary, PrimarySoft)
            Spacer(Modifier.width(6.dp))
            Tag(goalLabel(t.goal), Sub, Color(0xFF2A2730))
            Spacer(Modifier.weight(1f))
            Text(
                "复制",
                fontSize = 10.5.sp,
                color = Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(PrimarySoft)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        Text(
            t.text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Ink,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (t.why.isNotBlank()) {
            Text(
                "为什么 · ${t.why}",
                fontSize = 10.sp,
                color = AmberText,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun relLabel(name: String): String = when (name) {
    "BOSS" -> "领导"
    "COLLEAGUE" -> "同事"
    "CLIENT" -> "客户"
    "FRIEND" -> "朋友"
    "FLIRT" -> "暧昧"
    "FAMILY" -> "家人"
    "HR" -> "HR"
    else -> "通用"
}

private fun goalLabel(name: String): String = when (name) {
    "KEEP" -> "维持"
    "PUSH" -> "推进"
    "REFUSE" -> "拒绝"
    "APOLOGY" -> "致歉"
    "BARGAIN" -> "谈条件"
    "END" -> "结束对话"
    else -> "通用"
}

private fun taskLabel(name: String): String = when (name) {
    "POST" -> "发圈"
    "POLISH" -> "润色"
    else -> "回复"
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("replymate", text))
    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}
