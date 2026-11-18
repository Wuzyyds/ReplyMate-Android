package com.wzy.replymate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** 重启后把用户开着的悬浮球恢复回来（系统省电策略杀掉服务后，这是最后一道保险） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val on = context.getSharedPreferences("replymate", Context.MODE_PRIVATE)
            .getBoolean("overlay_enabled", false)
        if (!on || !Settings.canDrawOverlays(context)) return
        runCatching { OverlayService.start(context) }
            .onFailure { Log.d("RM", "BootReceiver 启动悬浮球失败: ${it.message}") }
    }
}
