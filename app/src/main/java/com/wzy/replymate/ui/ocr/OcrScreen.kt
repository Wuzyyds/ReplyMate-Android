package com.wzy.replymate.ui.ocr

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzy.replymate.GenSeed
import com.wzy.replymate.core.Images
import com.wzy.replymate.core.Ocr
import com.wzy.replymate.ui.GhostButton
import com.wzy.replymate.ui.PrimaryButton
import com.wzy.replymate.ui.SectionLabel
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.Red
import com.wzy.replymate.ui.theme.Sub
import com.wzy.replymate.ui.theme.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P-03 截图识别页：
 * 选图 → 本地 OCR → 可编辑文本 → 生成。
 * 识别失败/无文字不跳走，留在本页可以手改或粘贴。
 */
@Composable
fun OcrScreen(onBack: () -> Unit, onGoGenerate: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var uri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun recognize(u: Uri) {
        uri = u
        busy = true
        error = null
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Images.loadPreview(ctx, u) }
            bitmap = bmp
            val result = runCatching { Ocr.recognize(ctx, u) }
            busy = false
            result.onSuccess { t ->
                val clean = t.trim()
                if (clean.isBlank()) {
                    error = "没识别到文字。换张图，或直接在下面输入/粘贴。"
                } else {
                    text = clean
                }
            }.onFailure {
                error = "识别失败：" + (it.message ?: "图片读不出来") + "，可以换张图或手动输入"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> if (picked != null) recognize(picked) }

    // 首次进入直接打开相册；从生成页返回时只恢复预览，不再弹相册
    LaunchedEffect(uri) {
        val u = uri
        if (u != null && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { Images.loadPreview(ctx, u) }
        } else if (u == null && text.isBlank()) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    fun pasteFromClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        if (t.isNullOrBlank()) {
            Toast.makeText(ctx, "剪贴板是空的", Toast.LENGTH_SHORT).show()
        } else {
            text = t
            error = null
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {

        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("‹", fontSize = 20.sp, color = Sub) }
            Text("截图识别", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.weight(1f))
            if (text.isNotBlank()) Text("${text.length} 字", fontSize = 10.sp, color = Faint)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 图片预览
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(min = 120.dp, max = 240.dp)
                    .softShadow(10.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "待识别的截图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(6.dp)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.CropFree,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            if (busy) "正在识别…" else "选一张聊天截图",
                            fontSize = 11.5.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                if (busy) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.White.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("本地识别中…", fontSize = 11.5.sp, color = Sub)
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("重新选图") {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                GhostButton("粘贴文本") { pasteFromClipboard() }
                Spacer(Modifier.weight(1f))
                Text(
                    if (uri == null) "未选择图片" else "已选择 · 可重新选",
                    fontSize = 9.5.sp,
                    color = Faint,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            val err = error
            if (err != null) {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF331A1A))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(err, fontSize = 11.sp, color = Red, lineHeight = 16.sp)
                }
            }

            SectionLabel("识别结果（可编辑）", if (text.isBlank()) "手改错别字" else "改完再生成")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "识别出的对话会出现在这里，也可以直接粘贴或手打…",
                        fontSize = 12.sp,
                        color = Faint
                    )
                },
                minLines = 6,
                maxLines = 12,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Ink
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFC9578C),
                    unfocusedBorderColor = Line,
                    focusedContainerColor = Color(0xFF242129),
                    unfocusedContainerColor = Color(0xFF242129)
                )
            )

            Row(
                Modifier.padding(top = 10.dp, bottom = 18.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Faint,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("图片只在本机识别，不上传、不保存", fontSize = 10.sp, color = Faint)
            }
        }

        // 底部操作
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GhostButton("清空") { text = "" }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = "用这段去生成",
                modifier = Modifier.weight(1f),
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    GenSeed.clear()
                    GenSeed.text = text.trim()
                    onGoGenerate()
                }
            )
        }
    }
}
