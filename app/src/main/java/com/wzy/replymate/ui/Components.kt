package com.wzy.replymate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.domain.Source
import com.wzy.replymate.ui.theme.AmberSoft
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.AmberText
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimaryDisabled
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Sub
import com.wzy.replymate.ui.theme.softShadow

@Composable
fun SectionLabel(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 10.sp, color = Muted, letterSpacing = 0.4.sp)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(trailing, fontSize = 10.sp, color = Faint, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { i, s ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(12.dp),
                color = if (on) Primary else Color(0xFF242129),
                border = null,
                shadowElevation = if (on) 2.dp else 0.dp
            ) {
                Box(Modifier.height(34.dp).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text(
                        s,
                        fontSize = 12.5.sp,
                        color = if (on) Color.White else Sub,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun SegTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            // 深色主题：浅灰轨道换成半透明白，选中项用更亮的一档，不再用纯白底
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        labels.forEachIndexed { i, s ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(8.dp),
                color = if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                shadowElevation = 0.dp,
                modifier = Modifier.height(28.dp)
            ) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        s,
                        fontSize = 11.sp,
                        color = if (on) Ink else Sub,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Int = 40,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (height >= 40) 12.dp else 9.dp),
        color = if (enabled) Primary else PrimaryDisabled,
        modifier = modifier.height(height.dp)
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = if (height >= 40) 13.sp else 11.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        // 深色主题下必须是半透明白 + 淡描边；之前是白底配浅色字，等于看不见
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = modifier.height(34.dp)
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun Tag(text: String, color: Color, background: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.5.dp)
    ) {
        Text(text, fontSize = 9.5.sp, color = color)
    }
}

@Composable
fun CandidateCard(
    index: Int,
    candidate: Candidate,
    source: Source,
    fillLabel: String?,
    onCopy: () -> Unit,
    onFill: () -> Unit,
    modifier: Modifier = Modifier,
    copyLabel: String = "复制",
    whyPrefix: String = "为什么这样说"
) {
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(9.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(CardBg)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PrimarySoft),
                contentAlignment = Alignment.Center
            ) { Text("${index + 1}", fontSize = 9.5.sp, color = Primary, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(6.dp))
            if (candidate.styleLabel.isNotBlank()) Tag(candidate.styleLabel, Primary, PrimarySoft)
            Spacer(Modifier.weight(1f))
            if (source == Source.TEMPLATE) {
                Tag("离线模板 · 未调用模型", Color(0xFFFFC078), Color(0xFF2E2418))
            } else {
                Tag("模型", Muted, Color(0xFF242129))
            }
        }

        Text(
            candidate.text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Ink,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (candidate.why.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmberSoft)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("$whyPrefix · ${candidate.why}", fontSize = 10.sp, lineHeight = 15.sp, color = AmberText)
            }
        }

        Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(copyLabel, onClick = onCopy)
            if (fillLabel != null) {
                Spacer(Modifier.width(6.dp))
                PrimaryButton(fillLabel, Modifier.weight(1f), height = 34, onClick = onFill)
            }
        }
    }
}

@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AmberSoft)
            .border(1.dp, Color(0x33FFC078), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 11.sp, color = AmberText, lineHeight = 16.sp)
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Faint)
    }
}

val ContentPadding = PaddingValues(horizontal = 16.dp)
