package com.wzy.replymate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.wzy.replymate.data.prefs.ModelConfig

/**
 * 仅 debug 包：adb 广播写模型配置。
 * 模拟器/真机的输入法会把 adb input text 自动纠错成中文，用这个通道避免手动敲 Key。
 */
class DebugConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ReplyMateApp ?: return
        val cur = app.settings.config
        val base = intent.getStringExtra("base")
        val key = intent.getStringExtra("key")
        val model = intent.getStringExtra("model")
        app.settings.config = ModelConfig(
            baseUrl = base ?: cur.baseUrl,
            apiKey = key ?: cur.apiKey,
            model = model ?: cur.model
        )
        val saved = app.settings.config
        Log.d("RM", "debug config saved base=${saved.baseUrl} model=${saved.model} keyLen=${saved.apiKey.length}")
        Toast.makeText(context, "调试配置已写入", Toast.LENGTH_SHORT).show()
    }
}
