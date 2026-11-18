package com.wzy.replymate.ui.contacts

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.wzy.replymate.data.ProfileCodec
import com.wzy.replymate.data.db.ContactEntity
import com.wzy.replymate.data.db.ProfileChangeEntity
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.ui.ChipRow
import com.wzy.replymate.ui.EmptyHint
import com.wzy.replymate.ui.GhostButton
import com.wzy.replymate.ui.PrimaryButton
import com.wzy.replymate.ui.SectionLabel
import com.wzy.replymate.ui.Tag
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.CardBg
import com.wzy.replymate.ui.theme.Faint
import com.wzy.replymate.ui.theme.Ink
import com.wzy.replymate.ui.theme.Line
import com.wzy.replymate.ui.theme.Muted
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.Sub
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ReplyMateApp
    val contacts by app.db.contactDao().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ContactEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("‹", fontSize = 20.sp, color = Sub)
            }
            Text("联系人档案", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.weight(1f))
            Text(
                "＋ 新建",
                fontSize = 12.sp,
                color = Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { creating = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Text(
            "建档后生成时自动带上语气与禁忌。画像只从本机记录提炼，一次只改一条，可撤销。",
            fontSize = 10.sp,
            color = Muted,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (contacts.isEmpty()) {
            EmptyHint("还没有档案 · 右上角新建一个\n比如「王总 · 领导 · 别提周末加班」")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(contacts, key = { it.id }) { c ->
                    ContactRow(c) { editing = c }
                }
            }
        }
    }

    val target = editing
    if (creating || target != null) {
        val flow = remember(target?.id) { app.db.contactDao().changes(target?.id ?: -1L) }
        val changes by flow.collectAsState(initial = emptyList())
        ContactDialog(
            initial = target,
            latestChange = changes.firstOrNull { !it.reverted },
            onDismiss = { creating = false; editing = null },
            onSave = { entity ->
                scope.launch {
                    if (entity.id == 0L) app.db.contactDao().insert(entity)
                    else app.db.contactDao().update(entity)
                }
                creating = false
                editing = null
            },
            onDelete = target?.let {
                {
                    scope.launch { app.db.contactDao().delete(it.id) }
                    editing = null
                }
            },
            onEvolve = target?.let {
                {
                    scope.launch {
                        val fresh = app.db.contactDao().byId(it.id)
                        if (fresh != null) app.profileEvolver.evolve(fresh)
                    }
                }
            },
            onRevert = {
                changes.firstOrNull { !it.reverted }?.let { ch ->
                    scope.launch { app.profileEvolver.revert(ch) }
                }
            }
        )
    }
}

@Composable
private fun ContactRow(c: ContactEntity, onClick: () -> Unit) {
    val summary = remember(c.summaryJson) { ProfileCodec.decode(c.summaryJson) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(30.dp).height(30.dp).clip(RoundedCornerShape(9.dp)).background(PrimarySoft),
                contentAlignment = Alignment.Center
            ) { Text(c.name.take(1), fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Spacer(Modifier.width(6.dp))
                    Tag(relationLabel(c.relation), Primary, PrimarySoft)
                }
                Text(
                    if (summary.isEmpty()) "画像还没提炼 · 点开可手动填禁忌与语气"
                    else summary.oneLine(),
                    fontSize = 10.sp,
                    color = Muted,
                    maxLines = 2,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Text("›", fontSize = 15.sp, color = Faint)
        }
    }
}

@Composable
private fun ContactDialog(
    initial: ContactEntity?,
    latestChange: ProfileChangeEntity?,
    onDismiss: () -> Unit,
    onSave: (ContactEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onEvolve: (() -> Unit)?,
    onRevert: () -> Unit
) {
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var relation by remember { mutableStateOf(initial?.relation ?: Relation.FRIEND.name) }
    var taboo by remember { mutableStateOf(initial?.taboo ?: "") }
    var tone by remember { mutableStateOf(initial?.tone ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    val summary = remember(initial?.summaryJson) { ProfileCodec.decode(initial?.summaryJson ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF242129),
        title = { Text(if (isNew) "新建档案" else initial!!.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MinField("称呼", name) { name = it }
                SectionLabel("关系")
                ChipRow(
                    Relation.entries.map { it.label },
                    Relation.entries.indexOfFirst { it.name == relation }.coerceAtLeast(0)
                ) { relation = Relation.entries[it].name }
                MinField("禁忌话题", taboo) { taboo = it }
                MinField("常用语气", tone) { tone = it }
                MinField("备注", notes) { notes = it }

                if (!isNew) {
                    SectionLabel("我对他/她的理解", "规则提炼，不调模型")
                    if (summary.isEmpty()) {
                        Text("还没有画像 · 点下面「重新提炼」", fontSize = 10.sp, color = Faint)
                    } else {
                        summary.lines().forEach { (k, v) ->
                            Row(Modifier.padding(top = 5.dp)) {
                                Text(k, fontSize = 9.5.sp, color = Faint, modifier = Modifier.width(58.dp))
                                Text(v, fontSize = 10.5.sp, color = Ink, lineHeight = 15.sp)
                            }
                        }
                    }
                    latestChange?.let { ch ->
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFF242129))
                                .padding(9.dp)
                        ) {
                            Column {
                                Text("最近更新 · ${ch.fieldLabel}", fontSize = 10.sp, color = Primary)
                                Text(ch.newValue, fontSize = 10.sp, color = Ink, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp))
                                Text(ch.evidence, fontSize = 9.sp, color = Faint, modifier = Modifier.padding(top = 3.dp))
                                Text(
                                    "撤销这条",
                                    fontSize = 9.5.sp,
                                    color = Primary,
                                    modifier = Modifier.padding(top = 5.dp).clickable { onRevert() }
                                )
                            }
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("重新提炼") { onEvolve?.invoke() }
                        onDelete?.let { del -> GhostButton("删除档案") { del() } }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton("保存", height = 36, enabled = name.isNotBlank()) {
                onSave(
                    (initial ?: ContactEntity(name = name)).copy(
                        name = name.trim(),
                        relation = relation,
                        taboo = taboo.trim(),
                        tone = tone.trim(),
                        notes = notes.trim()
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", fontSize = 12.sp, color = Sub) } }
    )
}

@Composable
private fun MinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = Ink),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFC9578C),
            unfocusedBorderColor = Line,
            focusedContainerColor = Color(0xFF242129),
            unfocusedContainerColor = Color(0xFF242129)
        )
    )
}

private fun relationLabel(name: String): String =
    Relation.entries.firstOrNull { it.name == name }?.label ?: "朋友"
