package com.wzy.replymate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 仅 debug 包可用：从 adb 触发一次读屏自检，把结果写进 logcat。
 *
 *   adb shell am broadcast -a com.wzy.replymate.DEBUG_SELFTEST
 *
 * 为什么需要它：悬浮球的触摸和通知都依赖真实点按，而 adb 注入的点击
 * 到不了 APPLICATION_OVERLAY 窗口，没法自动化验证读屏。这个入口绕开触摸，
 * 直接跑 readScreen()，让"读不到"这件事可以被远程定位。
 *
 * release 包（非 debuggable）直接 return，不留后门。
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return

        if (intent?.action == "com.wzy.replymate.PREVIEW_PANEL") {
            OverlayService.preview(context)
            Log.d("RM", "selftest: 已触发建议面板预览")
            return
        }

        if (intent?.action == "com.wzy.replymate.REGEN_PANEL") {
            val ok = OverlayService.regenForTest()
            Log.d("RM", "selftest: 换一批 ok=$ok")
            return
        }

        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            Log.d("RM", "selftest: 无障碍服务未连接")
            return
        }
        val snap = acc.readScreen()
        if (snap == null) {
            Log.d("RM", "selftest: NULL（一条都没读到）")
            return
        }
        Log.d("RM", "selftest: pkg=${snap.pkg} n=${snap.lines.size}")
        snap.lines.takeLast(6).forEach { l ->
            Log.d("RM", "selftest  ${if (l.fromMe) "我  " else "对方"} | ${l.text.take(40)}")
        }
    }
}
