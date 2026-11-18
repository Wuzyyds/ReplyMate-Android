package com.wzy.replymate

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.wzy.replymate.data.ChatMemoryRepo
import com.wzy.replymate.data.LlmClient
import com.wzy.replymate.data.ProfileEvolver
import com.wzy.replymate.data.TemplateRepo
import com.wzy.replymate.data.db.AppDatabase
import com.wzy.replymate.data.db.MIGRATION_1_2
import com.wzy.replymate.data.db.MIGRATION_2_3
import com.wzy.replymate.data.db.MIGRATION_3_4
import com.wzy.replymate.data.prefs.SettingsStore
import com.wzy.replymate.domain.GenerateReply

class ReplyMateApp : Application() {

    val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "replymate.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val templates: TemplateRepo by lazy { TemplateRepo(this) }
    val llm: LlmClient by lazy { LlmClient() }
    val generate: GenerateReply by lazy { GenerateReply(llm, templates, settings, db) }
    val profileEvolver: ProfileEvolver by lazy { ProfileEvolver(db) }
    val chatMemory: ChatMemoryRepo by lazy { ChatMemoryRepo(db) }
}

/**
 * 悬浮球「读取剪贴板」的信号。
 * 球在 Service 里，主卡按钮在 Compose 里，两者靠这个单例通信：
 * 点了菜单就 fire 一次，主卡收到后把按钮切成绿色「已读取 ✓」。
 */
object PasteSignal {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun fire() {
        _events.tryEmit(Unit)
    }
}

/**
 * 首页 → 生成页的原文种子。
 * 长文本不塞进导航参数，用单例传递，进页面后立即取走并清空。
 */
object GenSeed {
    var text: String = ""

    /** 场景预设可顺带指定关系与目标，为空则用设置里的默认值 */
    var relation: com.wzy.replymate.domain.Relation? = null
    var goal: com.wzy.replymate.domain.Goal? = null

    fun clear() {
        text = ""
        relation = null
        goal = null
    }
}
