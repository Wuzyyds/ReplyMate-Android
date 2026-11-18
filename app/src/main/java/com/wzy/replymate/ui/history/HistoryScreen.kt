package com.wzy.replymate.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.data.db.GenerationEntity
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.ui.EmptyHint
import com.wzy.replymate.ui.Tag
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Sub
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val candidateSerializer = ListSerializer(Candidate.serializer())
private val rowJson = Json { ignoreUnknownKeys = true }

@Composable
fun HistoryScreen(onGoHome: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ReplyMateApp
    // 初始给 null：先不画「还没有记录」，等数据库第一次回值再决定空态，避免闪一下空页面
    val rowsState by remember {
        app.db.generationDao().recent().map<List<GenerationEntity>, List<GenerationEntity>?> { it }
    }.collectAsState(initial = null)
    val rows = rowsState.orEmpty()
    val loading = rowsState == null
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Text(
            "历史",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp)
        )

        // 关系筛选：只出现在用过的关系上，不用就不显示一排空 chip
        val relations = rows.map { it.relation }.distinct()
        var filter by remember { mutableStateOf<String?>(null) }
        val shown = if (filter == null) rows else rows.filter { it.relation == filter }

        if (!loading && rows.isNotEmpty() && relations.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip("全部", filter == null) { filter = null }
                relations.forEach { r ->
                    FilterChip(relationLabel(r), filter == r) { filter = r }
                }
            }
        }

        if (loading) {
            // 数据库还没回值，保持深色底，不闪空态
        } else if (rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyArt()
                Spacer(Modifier.height(16.dp))
                Text("还没有记录 · 去首页粘贴一段对话开始", fontSize = 13.sp, color = Faint)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimarySoft)
                        .clickable { onGoHome() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "去首页",
                        fontSize = 14.sp,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { it.id }) { row ->
                    HistoryItem(
                        row = row,
                        time = fmt.format(Date(row.createdAt)),
                        onDelete = { scope.launch { app.db.generationDao().delete(row.id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Primary else Color(0xFF242129))
            .border(1.dp, if (selected) Primary else Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (selected) Color.White else Sub)
    }
}

@Composable
private fun HistoryItem(row: GenerationEntity, time: String, onDelete: () -> Unit) {
    val candidates: List<Candidate> = remember(row.id) {
        runCatching { rowJson.decodeFromString(candidateSerializer, row.candidatesJson) }
            .getOrDefault(emptyList())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(15.dp))
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(taskLabel(row.taskType), Primary, PrimarySoft)
            Spacer(Modifier.width(6.dp))
            Tag(relationLabel(row.relation), Sub, Color(0xFF2A2730))
            if (row.source == "TEMPLATE") {
                Spacer(Modifier.width(6.dp))
                Tag("模板", Color(0xFF8A7A5E), Color(0xFF2E2418))
            }
            Spacer(Modifier.weight(1f))
            Text(time, fontSize = 9.5.sp, color = Faint)
        }

        Text(
            row.input.replace("\n", " / ").take(60),
            fontSize = 11.5.sp,
            color = Sub,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 2
        )

        candidates.firstOrNull()?.let { first ->
            Text(
                first.text,
                fontSize = 11.5.sp,
                color = Ink,
                lineHeight = 17.sp,
                maxLines = 2,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF242129))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${row.latencyMs}ms · ${row.emojiPolicy}", fontSize = 9.5.sp, color = Faint)
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
        }
    }
}

private fun taskLabel(name: String): String = when (name) {
    "POST" -> "发圈"
    "POLISH" -> "润色"
    else -> "回复"
}

private fun relationLabel(name: String): String = when (name) {
    "BOSS" -> "领导"
    "FRIEND" -> "朋友"
    "FLIRT" -> "暧昧"
    "FAMILY" -> "家人"
    "HR" -> "HR"
    "COLLEAGUE" -> "同事"
    "CLIENT" -> "客户"
    else -> "未设"
}

@Composable
private fun EmptyArt(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 148.dp, height = 124.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.52f
        val r = w * 0.31f

        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF242129), Color(0xFFDDE7FF))
            ),
            radius = r,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color(0xFFC8D8FF),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(
                width = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
            )
        )

        val cardW = w * 0.40f
        val cardH = h * 0.56f
        val cardLeft = cx - cardW / 2f
        val cardTop = cy - cardH / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(cardLeft, cardTop),
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(w * 0.08f)
        )

        val lineX = cardLeft + cardW * 0.16f
        val lineH = h * 0.045f
        listOf(
            Triple(0.20f, 0.46f, Color(0xFFDDE6FF)),
            Triple(0.38f, 0.68f, Color(0xFFEDF2FF)),
            Triple(0.56f, 0.54f, Color(0xFFEDF2FF)),
            Triple(0.74f, 0.30f, Color(0xFFEDF2FF))
        ).forEach { (yFrac, widthFrac, lineColor) ->
            drawRoundRect(
                color = lineColor,
                topLeft = Offset(lineX, cardTop + cardH * yFrac),
                size = Size(cardW * widthFrac, lineH),
                cornerRadius = CornerRadius(lineH / 2f)
            )
        }

        val badgeR = w * 0.152f
        val badgeC = Offset(cardLeft + cardW * 0.96f, cardTop + cardH * 0.88f)
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF9C27D9), Color(0xFFE23C7E)),
                start = Offset(badgeC.x - badgeR, badgeC.y - badgeR),
                end = Offset(badgeC.x + badgeR, badgeC.y + badgeR)
            ),
            radius = badgeR,
            center = badgeC
        )

        val tick = Path().apply {
            moveTo(badgeC.x - badgeR * 0.42f, badgeC.y)
            lineTo(badgeC.x - badgeR * 0.08f, badgeC.y + badgeR * 0.34f)
            lineTo(badgeC.x + badgeR * 0.46f, badgeC.y - badgeR * 0.28f)
        }
        drawPath(
            path = tick,
            color = Color.White,
            style = Stroke(
                width = badgeR * 0.24f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawCircle(Color(0xCC12C9C0), radius = w * 0.028f, center = Offset(w * 0.87f, h * 0.22f))
        drawCircle(Color(0x592B6BFF), radius = w * 0.02f, center = Offset(w * 0.12f, h * 0.80f))
    }
}
