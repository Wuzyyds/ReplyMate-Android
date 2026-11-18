package com.wzy.replymate.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wzy.replymate.MainActivity
import com.wzy.replymate.ReplyMateApp
import com.wzy.replymate.core.EmojiFilter
import com.wzy.replymate.data.LlmException
import com.wzy.replymate.data.db.ChatMemoryEntity
import com.wzy.replymate.data.db.ChatTurn
import com.wzy.replymate.domain.Candidate
import com.wzy.replymate.domain.EmojiPolicy
import com.wzy.replymate.domain.GenRequest
import com.wzy.replymate.domain.GenResult
import com.wzy.replymate.domain.Goal
import com.wzy.replymate.domain.Relation
import com.wzy.replymate.domain.Source
import com.wzy.replymate.domain.TaskType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮球：前台服务 + TYPE_APPLICATION_OVERLAY。
 * 当前只做「悬浮 + 拖拽 + 点击回到 App + 长按关闭」，读屏与回填属于 M2。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.wzy.replymate.SHOW_BALL"

        /** 通知栏点击：和点悬浮球走同一条逻辑 */
        const val ACTION_TAP = "com.wzy.replymate.TAP_FROM_NOTIFICATION"
        const val ACTION_PASTE = "com.wzy.replymate.PASTE_FROM_NOTIFICATION"
        const val ACTION_HIDE = "com.wzy.replymate.HIDE_BALL"

        /** 仅 debug：用一段假聊天预览建议面板，绕开「adb 点不进 overlay」的限制 */
        const val ACTION_PREVIEW = "com.wzy.replymate.PREVIEW_PANEL"

        fun preview(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_PREVIEW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        /** 当前前台是不是自家 App；服务晚启动时靠它补上状态 */
        @Volatile
        private var appForeground = false

        /** 当前实例。Activity 前后台切换直接调它，避免后台 startService 被系统拒绝后球再也回不来 */
        @Volatile
        private var instance: OverlayService? = null

        /** 仅 debug 自检用：等价于点面板上的「↻ 换一批」 */
        fun regenForTest(): Boolean {
            val svc = instance ?: return false
            svc.regenerate(forceRefresh = true)
            return true
        }
        private const val CHANNEL_ID = "overlay_ball"
        private const val NOTIF_ID = 1001
        /** 窗口尺寸：球体 52 + 两侧各 6dp，给拖拽光晕留出空间 */
        private const val BALL_DP = 64f

        /** 球体直径 */
        private const val BALL_CORE = 52f

        /** 用户发来的内容明显不是「帮我回聊天」时，给的使用提示 */
        private const val CHAT_GUIDE = "我是用来帮你回聊天消息的：\n" +
            "① 先打开具体聊天，点「读屏」；读不到就点「截图」\n" +
            "② 然后说「帮我回复最后一句」，或直接写要求，比如「帮我婉拒他的加班邀请」\n" +
            "③ 「回复 / 润色 / 翻译」页也能单独处理文案。"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_HIDE)
            ctx.startService(i)
        }

        /** 回到自家 App 时把球藏起来，否则会压住首页内容；退出去再显示。 */
        fun setAppForeground(ctx: Context, on: Boolean) {
            appForeground = on
            Log.d("RM", "setAppForeground=$on instance=${instance != null}")
            // 以前用 startService 发广播：App 退到后台时会被 Android 8+ 拦掉，
            // 球就永远停在 GONE。直接调服务实例，没有限制。
            instance?.applyMuted(on)
        }
    }

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var listBox: LinearLayout? = null
    private var statusView: TextView? = null
    private var batchButton: TextView? = null
    /** 上一批候选原文：换一批时作为 avoid 传给模型，避免换个语序又发一遍 */
    private var lastCandidates: List<String> = emptyList()
    private var relationRow: LinearLayout? = null
    private var relationExpanded = false
    private var relationManual = false
    private var roleOther: TextView? = null
    private var roleMe: TextView? = null
    private var emojiNone: TextView? = null
    private var emojiLight: TextView? = null
    private var panelEmoji: EmojiPolicy = EmojiPolicy.NONE
    private var currentRelation: Relation = Relation.BOSS
    private var lastSnapshot: ReplyAccessibilityService.Snapshot? = null
    private var lastInputText: String? = null
    /** 读屏进行中标记：避免连点球时叠几次读屏和生成 */
    private var reading = false
    private var busy = false
    private var appMuted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 记忆聊天窗 ====================
    private var chatWindow: View? = null
    private var chatParams: WindowManager.LayoutParams? = null
    private var chatScrollView: ScrollView? = null
    private var chatListBox: LinearLayout? = null
    private var chatMemoryScroll: ScrollView? = null
    private var chatMemoryListBox: LinearLayout? = null
    private var chatInput: EditText? = null
    private var chatTitleView: TextView? = null
    private var chatSubtitleView: TextView? = null
    private var chatSendButton: TextView? = null
    private var chatBody: FrameLayout? = null
    private var chatTools: LinearLayout? = null
    private var chatInputRow: LinearLayout? = null
    private var chatHint: TextView? = null
    private var chatMaxButton: TextView? = null
    private var chatMinButton: TextView? = null
    private var chatResizer: View? = null
    private var chatMaximized = false
    private var chatMinimized = false
    private var chatRestoreHeight = 0
    private var chatSizeStep = 1
    private var chatImeAdjusted = false
    private var chatPreImeY = 0
    private var chatPreImeH = 0
    /** 最小化/还原的高度动画；连点按钮时先取消上一次，避免两个动画抢窗口高度 */
    private var chatSizeAnimator: ValueAnimator? = null
    private var currentMemory: ChatMemoryEntity? = null
    private var chatContext: String = ""
    private var screenTitle: String = ""
    private var screenPkg: String = ""
    private var chatBusy = false
    /** 「AI 正在想…」占位气泡，答案回来时移除 */
    private var typingBubble: View? = null
    private var showingMemoryList = false

    /** 长按记忆后待确认删除的那条；避免误点右侧「删」把记忆直接删掉 */
    private var pendingDelete: ChatMemoryEntity? = null

    /** 连续切记忆时，只让最后一次加载生效，避免旧记录覆盖新记录 */
    private var bindSeq = 0

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    /** 「帮我回聊天」类请求的关键词，命中就不弹使用提示 */
    private val replyIntentWords = listOf(
        "回复", "回他", "回她", "回一下", "回一句", "怎么回", "怎么答", "接话", "说什么", "发什么",
        "帮我回", "帮我写", "代写", "话术", "婉拒", "拒绝", "回绝", "敷衍", "安慰", "夸",
        "道歉", "解释", "邀请", "邀约", "请假", "催", "怼", "沟通", "回应", "答复", "表态",
        "reply", "respond", "draft", "decline", "apologize", "invite", "answer"
    )

    /** 明显与「回聊天」无关的话题，用来触发使用提示 */
    private val offTopicWords = listOf(
        "天气", "新闻", "笑话", "股票", "基金", "汇率", "翻译", "百科", "你是谁", "你叫什么",
        "你能做什么", "怎么安装", "怎么开启", "怎么用", "几点了", "今天几号", "写代码", "编程",
        "数学", "计算", "星座", "运势", "电影", "音乐", "故事",
        "weather", "news", "joke", "stock", "translate", "who are you", "what can you do",
        "code", "math", "movie", "music", "story"
    )

    /** 联系人/群名可能以中文括号开头；「< 2」这种返回角标不算 */
    private fun startsLikeName(t: String): Boolean {
        val c = t.trim().firstOrNull() ?: return false
        return c.isLetterOrDigit() || c in "【「『《（(〈"
    }

    private fun looksOffTopic(q: String): Boolean {
        val s = q.trim()
        if (s.isEmpty()) return false
        if (replyIntentWords.any { s.contains(it, ignoreCase = true) }) return false
        if (offTopicWords.any { s.contains(it, ignoreCase = true) }) return true
        return s.length <= 2
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeBall()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PREVIEW -> {
                val demo = ReplyAccessibilityService.Snapshot(
                    pkg = "com.tencent.mm",
                    lines = listOf(
                        ReplyAccessibilityService.Line("这个方案明天能给吗？", false, 420),
                        ReplyAccessibilityService.Line("我今晚看一下", true, 540),
                        ReplyAccessibilityService.Line("明天上午之前给个准话就行", false, 660)
                    ),
                    scanned = 3
                )
                lastSnapshot = demo
                lastInputText = demo.asPromptText()
                relationManual = false
                currentRelation = inferRelation(demo)
                val t = demo.lines.lastOrNull { !it.fromMe }?.text?.take(16)
                showPanel(
                    if (t.isNullOrBlank()) "准备回复 · ${currentRelation.label}"
                    else "回复「$t」· ${currentRelation.label}"
                )
                generate(demo, forceRefresh = true)
                return START_STICKY
            }
            ACTION_TAP -> {
                // 从通知栏唤起：即使悬浮球点不动，这条路永远可用。
                // 通知栏如果还开着，先借无障碍收起，否则读到的全是通知文字。
                val acc = ReplyAccessibilityService.instance
                if (acc == null) {
                    // 通知栏还盖着，面板会被压在下面；直接打开 App 走粘贴/截图
                    toast("读屏未开启，打开 App 用粘贴或截图")
                    openApp()
                    return START_STICKY
                }
                val shade = acc.isShadeOpen()
                Log.d("RM", "ACTION_TAP acc=true shade=$shade")
                if (shade) acc.collapseShade()
                android.os.Handler(mainLooper).postDelayed({
                    if (running) openChat()
                }, 800)
                return START_STICKY
            }
            ACTION_PASTE -> {
                // 剪贴板兜底：不需要读屏权限，复制什么就生成什么
                val acc = ReplyAccessibilityService.instance
                if (acc == null) {
                    toast("打开 App，点「粘贴一段对话」即可生成")
                    openApp()
                    return START_STICKY
                }
                val delay = if (acc.isShadeOpen()) {
                    acc.collapseShade()
                    600L
                } else 200L
                android.os.Handler(mainLooper).postDelayed({
                    if (running) pasteAndGenerate(openPanelIfClosed = true)
                }, delay)
                return START_STICKY
            }
            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    toast("悬浮窗权限未开启，去 App 首页重新打开")
                    setEnabledPref(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBall()
                // 服务比 Activity 晚启动时，把前台状态补上
                if (appMuted || appForeground) ball?.visibility = View.GONE
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉 App 后 onPause 的静音状态可能残留，球会永远看不见
        appForeground = false
        applyMuted(false)
        super.onTaskRemoved(rootIntent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏/折叠屏切换后，把聊天窗收回可视区域
        chatParams?.let { cp ->
            clampChatParams(cp)
            chatWindow?.let { runCatching { wm.updateViewLayout(it, cp) } }
        }
        val p = params ?: return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        p.x = p.x.coerceIn(0, (w - dp(BALL_DP)).coerceAtLeast(0))
        p.y = p.y.coerceIn(dp(24f), (h - dp(BALL_DP) - dp(24f)).coerceAtLeast(dp(24f)))
        ball?.let { runCatching { wm.updateViewLayout(it, p) } }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeBall()
        hidePanel()
        closeChat()
        scope.cancel()
        super.onDestroy()
    }

    /** App 回到前台时收球，离开时放球。用实例直调，避免后台启动服务被拦。 */
    private fun applyMuted(muted: Boolean) {
        appMuted = muted
        Log.d("RM", "applyMuted muted=$muted ball=${ball?.visibility} alpha=${ball?.alpha} fg=$appForeground")
        if (muted) {
            hidePanel()
            closeChat()
            ball?.visibility = View.GONE
        } else {
            if (ball == null && Settings.canDrawOverlays(this)) showBall()
            ball?.let { b ->
                b.animate().cancel()
                b.alpha = 1f
                b.scaleX = 1f
                b.scaleY = 1f
                b.visibility = View.VISIBLE
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球常驻运行"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        // 点通知 = 直接读屏并弹面板（不依赖悬浮窗触摸，任何 ROM 都拦不住）
        val tapPi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pastePi = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_PASTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("点这里 · 3 条回复")
            .setContentText("在聊天页点通知读屏；读不到就用「粘贴剪贴板」")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapPi)
            .addAction(0, "粘贴剪贴板", pastePi)
            .addAction(0, "打开 App", openPi)
            .build()
    }

    private fun setEnabledPref(on: Boolean) {
        getSharedPreferences("replymate", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("overlay_enabled", on)
            .apply()
    }

    private fun showBall() {
        if (ball != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.d("RM", "showBall: 悬浮窗权限未授予")
            return
        }
        val size = dp(BALL_DP)
        val metrics = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val defX = (metrics.widthPixels - size - dp(6f)).coerceAtLeast(0)
            // 距底 92dp：贴在底部导航上方，不压住 tabBar
            val defY = (metrics.heightPixels - size - dp(92f))
                .coerceIn(dp(60f), (metrics.heightPixels - size - dp(24f)).coerceAtLeast(dp(60f)))
            // 记住上次拖到的位置，重开 App / 重启服务后球还在原处
            x = chatPrefs.getInt("ball_x", defX)
                .coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = chatPrefs.getInt("ball_y", defY)
                .coerceIn(dp(24f), (metrics.heightPixels - size - dp(24f)).coerceAtLeast(dp(24f)))
        }

        val v = BallView(this)
        attachTouch(v, p)
        runCatching { wm.addView(v, p) }.onFailure {
            Toast.makeText(this, "悬浮球添加失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        ball = v
        params = p
        running = true
        // 服务重启/更新后可能残留透明的球：这里强制恢复成正常状态
        v.alpha = 1f
        v.scaleX = 1f
        v.scaleY = 1f
        v.visibility = if (appForeground) View.GONE else View.VISIBLE
        Log.d("RM", "showBall vis=${v.visibility} appFg=$appForeground")
        setEnabledPref(true)
    }

    private fun removeBall() {
        running = false
        appMuted = false
        setEnabledPref(false)
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        params = null
    }

    private fun attachTouch(v: View, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var dragging = false
        var longPressed = false

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                longPressed = true
                val b = ball ?: return
                b.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                Toast.makeText(this@OverlayService, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
                // 收起动画结束再移除，别让球瞬间凭空消失；长按期间抬手也不再触发打开
                b.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
                    .withEndAction {
                        removeBall()
                        stopSelf()
                    }
                    .start()
            }
        })

        v.setOnTouchListener { view, e ->
            gesture.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    dragging = false
                    longPressed = false
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    // 5dp 太小：手指点一下的自然抖动经常超过它，
                    // 结果被当成拖动走 snapToEdge，onBallTap 永远不触发。
                    if (abs(dx) > dp(12f) || abs(dy) > dp(12f)) moved = true
                    if (moved) {
                        if (!dragging) {
                            dragging = true
                            (view as? BallView)?.halo = true
                            view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
                        }
                        p.x = (startX + dx).roundToInt()
                        p.y = (startY + dy).roundToInt()
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (view as? BallView)?.halo = false
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                    if (!moved && !longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onBallTap()
                    } else {
                        snapToEdge(p)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val size = dp(BALL_DP)
        // 窗口比球大 6dp/侧，margin 取 6 让球体边缘距屏边正好 12dp
        val margin = dp(6f)
        val target = if (p.x + size / 2 < screenW / 2) margin else screenW - size - margin
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                ball?.let { b -> runCatching { wm.updateViewLayout(b, p) } }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    saveBallPos()
                }
            })
            start()
        }
    }

    /** 记录球的位置，下次启动/服务重启停回原处 */
    private fun saveBallPos() {
        val p = params ?: return
        chatPrefs.edit().putInt("ball_x", p.x).putInt("ball_y", p.y).apply()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    // ==================== 悬浮面板 ====================

    private fun onBallTap() {
        if (chatWindow != null) {
            closeChat()
            return
        }
        if (panel != null) {
            hidePanel()
            return
        }
        // 主路线：点球 = 读屏 + 直接给 5 条建议，不进聊天窗、不弹菜单
        readAndShow()
    }

    /**
     * 读屏 → 弹面板。读不到也必须弹：给「粘贴剪贴板」这条不需要任何权限的路，
     * 否则用户点了球只看到一句 Toast，AI 还是用不上。
     */
    private fun readAndShow(allowShadeRetry: Boolean = true) {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            Log.d("RM", "readAndShow: 无障碍服务未连接")
            lastSnapshot = null
            lastInputText = null
            showPanel("读屏未开启")
            renderFallback("读屏没开，读不到聊天内容。先复制对方最后一句，点「粘贴剪贴板」直接生成。", true)
            return
        }
        if (reading) {
            Log.d("RM", "readAndShow: 上一次读屏还没结束，忽略这次")
            return
        }
        reading = true
        // 读屏要走几千次无障碍 IPC，放后台线程，别把主线程和悬浮窗卡住
        scope.launch {
            try {
                // 单飞读屏：如果轻聊/通知已经在读，直接复用那次结果
                val snap = acc.readScreenShared().await()
                if (snap == null || snap.lines.isEmpty()) {
                    // 通知栏还盖在上面时读不到聊天，收起后重试一次
                    if (allowShadeRetry && acc.isShadeOpen()) {
                        Log.d("RM", "readAndShow: 通知栏挡着，收起后重试")
                        acc.collapseShade()
                        android.os.Handler(mainLooper).postDelayed({
                            if (running) readAndShow(false)
                        }, 600)
                        return@launch
                    }
                    // 刚切到聊天页时无障碍窗口列表可能还没刷新，补读一次再判空
                    if (allowShadeRetry) {
                        Log.d("RM", "readAndShow: 首次读空，350ms 后补读")
                        android.os.Handler(mainLooper).postDelayed({
                            if (running) readAndShow(false)
                        }, 350)
                        return@launch
                    }
                    Log.d("RM", "readAndShow: readScreen 为空")
                    lastSnapshot = null
                    lastInputText = null
                    showPanel("没读到内容")
                    renderFallback("没读到聊天内容。先复制对方最后一句，再点「粘贴剪贴板」；也可以打开 App 截图识别。", false)
                    return@launch
                }
                Log.d("RM", "readAndShow: 读到 ${snap.lines.size} 条 pkg=${snap.pkg}")
                // 桌面/会话列表没有输入框，读出来的是图标文字，拿去生成只会答非所问
                if (!snap.chatInput) {
                    Log.d("RM", "readAndShow: 非聊天页，提示用户先打开具体聊天")
                    lastSnapshot = null
                    lastInputText = null
                    showPanel("这页不像聊天页")
                    renderFallback(
                        "这是桌面或会话列表，不是具体聊天。先打开某个人的聊天，再点球；也可以复制对方最后一句，点「粘贴剪贴板」直接生成。",
                        false
                    )
                    return@launch
                }
                lastSnapshot = snap
                relationExpanded = false
                // 用户没手动改过关系时，按聊天内容和来源 App 自动判断，首屏不再要求手选
                if (!relationManual) currentRelation = inferRelation(snap)
                // 状态行直接显示「在回哪一句」，比让用户选「对方/我」直观得多
                val target = snap.lines.lastOrNull { !it.fromMe }?.text?.take(16)
                showPanel(
                    if (target.isNullOrBlank()) "准备回复 · ${currentRelation.label}"
                    else "回复「$target」· ${currentRelation.label}"
                )
                generate(snap)
            } finally {
                reading = false
            }
        }
    }

    private fun showPanel(statusText: String) {
        if (panel != null) {
            statusView?.text = statusText
            return
        }
        val v = buildPanel(statusText)
        val width = dp(322f)
        val screenH = resources.displayMetrics.heightPixels
        val p = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((resources.displayMetrics.widthPixels - width) / 2).coerceAtLeast(dp(8f))
            y = if ((params?.y ?: 0) < screenH / 2) (screenH * 0.40f).roundToInt()
            else (screenH * 0.12f).roundToInt()
        }
        runCatching { wm.addView(v, p) }.onFailure {
            toast("面板添加失败")
            return
        }
        panel = v
        panelParams = p

        // 点面板外面直接收起；不用非得找右上角的 ×
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        // 面板淡入放大，别硬邦邦地盖上来
        v.alpha = 0f
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // 面板打开时收起球，避免互相遮挡
        ball?.visibility = View.GONE
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        listBox = null
        statusView = null
        batchButton = null
        relationRow = null
        roleOther = null
        roleMe = null
        emojiNone = null
        emojiLight = null
        if (!appMuted && chatWindow == null) ball?.visibility = View.VISIBLE
    }

    // ==================== 记忆聊天窗 ====================

    /** 点悬浮球：读当前聊天 → 按「标题 + 包名」接上记忆 → 打开聊天窗 */
    private fun openChat() {
        if (chatWindow != null) {
            closeChat()
            return
        }
        hidePanel()
        val acc = ReplyAccessibilityService.instance
        // 先把窗口弹出来，读屏放后台，读完再补标题和记忆；别让打开动作被读屏卡住
        screenTitle = ""
        screenPkg = ""
        chatContext = ""
        lastSnapshot = null
        showChatWindow()
        if (acc == null) {
            bindLatestMemory()
            return
        }
        scope.launch {
            // 和点球弹面板共用同一次读屏，避免两套 UI 同时扫无障碍树
            val snap = acc.readScreenShared().await()
            if (chatWindow == null) return@launch
            if (snap == null && acc.isShadeOpen()) {
                acc.collapseShade()
            }
            lastSnapshot = snap
            // 只有底部带输入框的聊天页才拿来当上下文；列表页/搜索页的文字会把记忆带偏
            val chatPage = snap?.chatInput == true
            screenPkg = if (chatPage) snap?.pkg.orEmpty() else ""
            screenTitle = if (chatPage) snap?.title.orEmpty() else ""
            chatContext = if (chatPage) snap?.asPromptText().orEmpty() else ""
            val title = screenTitle.takeIf { it.isNotBlank() }
            if (title != null) {
                bindMemory(title, screenPkg)
            } else {
                // 分身应用读不到节点时，回到最近一份「未绑定应用」的记忆，保证下次还接得上
                bindLatestMemory()
            }
            if (snap == null) {
                // 通知栏刚收起时第一遍常读空，补读一次；读不到也不挡着聊天
                android.os.Handler(mainLooper).postDelayed({
                    if (chatWindow != null) refreshChatFromScreen(silent = true)
                }, 700)
            }
        }
    }

    private fun showChatWindow() {
        if (chatWindow != null) return
        val v = buildChatWindow()
        val g = loadChatGeometry()
        val p = WindowManager.LayoutParams(
            g[2],
            g[3],
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 NOT_FOCUSABLE，输入框才能调起键盘；NOT_TOUCH_MODAL 让窗外还能操作聊天 App
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 不让状态栏 inset 把内容往下推，否则画面和触摸热区差一个状态栏
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 改成左上角定位，窗口才能被拖动到屏幕任意位置
            gravity = Gravity.TOP or Gravity.START
            x = g[0]
            y = g[1]
            // 用 RESIZE 让窗口随键盘变矮。PAN 会把画面整体上移，
            // 但触摸坐标不跟着移，弹键盘后点按钮会点到别的位置。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        runCatching { wm.addView(v, p) }.onFailure {
            Log.d("RM", "chat addView failed: ${it.message}")
            toast("聊天窗打开失败")
            return
        }
        // 打开时轻微放大淡入，点完球能明确看到「窗出来了」
        v.alpha = 0.5f
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        chatWindow = v
        chatParams = p
        chatMaximized = false
        chatMinimized = false
        chatRestoreHeight = 0
        // 球缩一下淡出，给「窗从球里长出来」的感觉
        ball?.let { b ->
            b.animate().cancel()
            b.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).setDuration(120)
                .withEndAction {
                    if (chatWindow != null) {
                        b.visibility = View.GONE
                        b.alpha = 1f
                        b.scaleX = 1f
                        b.scaleY = 1f
                    }
                }
                .start()
        }
    }

    private fun buildChatWindow(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFFFFFFFF.toInt(), 20f, 0xFFEFE8EE.toInt())
            setPadding(dp(12f), dp(6f), dp(12f), dp(8f))
            // 不要设 elevation：悬浮窗的阴影会给窗口加 surfaceInsets，
            // 触摸坐标会整体偏移约 100px，点「填入」会点到上面的文字。
        }

        // 顶部拖动条：按住这里可以把整个窗口拖到任意位置
        val grab = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14f)
            )
            setOnTouchListener(dragTouch())
        }
        grab.addView(
            View(this).apply { background = roundedBg(0xFFB03A72.toInt(), 3f) },
            FrameLayout.LayoutParams(dp(46f), dp(4f), Gravity.CENTER)
        )
        content.addView(grab)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(dragTouch())
        }
        val title = TextView(this).apply {
            text = "记忆 · 新对话"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14161C.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chatTitleView = title
        head.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(chatChip("切换") { toggleMemoryList(true) })
        val minBtn = windowControl("−") { toggleMinimize() }
        chatMinButton = minBtn
        head.addView(minBtn)
        val maxBtn = windowControl("□") { toggleMaximize() }
        chatMaxButton = maxBtn
        head.addView(maxBtn)
        head.addView(windowControl("×") { dismissChat() })
        content.addView(head)

        val subtitle = TextView(this).apply {
            text = "正在读取当前聊天…"
            textSize = 10.5f
            setTextColor(0xFF9AA0B0.toInt())
            setPadding(0, dp(2f), 0, dp(6f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chatSubtitleView = subtitle
        content.addView(subtitle)

        // 消息区和记忆列表共用一个位置，切换时只换可见性
        val body = FrameLayout(this)
        chatBody = body
        val msgScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            // 点/滑消息区就收起键盘，窗口顺便恢复到原来的大小
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN && chatImeAdjusted) {
                    chatInput?.clearFocus()
                    hideChatKeyboard()
                }
                false
            }
        }
        chatScrollView = msgScroll
        val msgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2f), 0, dp(2f))
        }
        chatListBox = msgBox
        msgScroll.addView(
            msgBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        body.addView(
            msgScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val memScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        chatMemoryScroll = memScroll
        val memBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatMemoryListBox = memBox
        memScroll.addView(
            memBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        body.addView(
            memScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5f), 0, dp(5f))
        }
        chatTools = tools
        tools.addView(chatChip("读屏") { refreshChatFromScreen() })
        tools.addView(chatChip("截图") { screenshotToChat() })
        tools.addView(chatChip("建议") { openSuggestPanel() })
        tools.addView(chatChip("大小") { cycleChatSize() })
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(chatChip("新对话") { newChat() })
        content.addView(tools)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chatInputRow = inputRow
        val input = EditText(this).apply {
            hint = "问问 AI，或直接说：帮我回复最后一句"
            textSize = 13f
            setTextColor(0xFF14161C.toInt())
            setHintTextColor(0xFF9AA0B0.toInt())
            background = roundedBg(0xFFF3F5FA.toInt(), 12f)
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            // 单行 + 键盘发送键：输入问题后直接回车就能发，不用去够按钮
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // 双保险：部分输入法会看这两个标志决定是否弹「提取视图」
            imeOptions = EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendChat()
                    true
                } else {
                    false
                }
            }
            // 手指按下就先缩窗，赶在输入法弹出之前把输入框露出来
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    chatWindow?.let { fitChatAboveKeyboard() }
                }
                false
            }
            // 键盘不会再帮悬浮窗 resize，聚焦后自己把窗口收到键盘上方
            setOnFocusChangeListener { _, hasFocus ->
                val root = chatWindow ?: return@setOnFocusChangeListener
                if (hasFocus) {
                    // 悬浮窗不会被系统自动 resize，键盘会直接盖住输入框；
                    // 先按估算高度立刻缩窗，再按真实 IME inset 校正。
                    root.post { fitChatAboveKeyboard() }
                    // 键盘弹出动画结束前后各测一次，避免用动画中途的 inset 缩不到位
                    listOf(350L, 650L).forEach { delay ->
                        root.postDelayed({ fitChatAboveKeyboard() }, delay)
                    }
                } else {
                    root.postDelayed({ restoreChatAfterKeyboard() }, 200)
                }
            }
        }
        chatInput = input
        inputRow.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val send = pillButton("发送", primary = true) { sendChat() }
        chatSendButton = send
        inputRow.addView(
            send,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6f) }
        )
        content.addView(inputRow)

        val hint = TextView(this).apply {
            text = "顶部拖动 · 「大小」调窗口 · 读不到就换截图"
            textSize = 9.5f
            setTextColor(0xFF9AA0B0.toInt())
            setPadding(dp(2f), dp(4f), dp(8f), 0)
        }
        chatHint = hint

        // 缩放手柄必须放在内容层里：WindowManager 的窗口 frame 和可见区域
        // 在部分 ROM 上会差一个状态栏，挂在最外层会让手柄触摸位置对不上。
        val hintRow = FrameLayout(this)
        hintRow.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val resizer = TextView(this).apply {
            // 视觉上隐藏，但保留 44dp 透明触摸区：右下角拖动仍然能缩放
            text = ""
            alpha = 0f
            setOnTouchListener(resizeTouch())
        }
        chatResizer = resizer
        hintRow.addView(
            resizer,
            FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(2f)
                rightMargin = dp(2f)
            }
        )
        content.addView(hintRow)
        return content
    }

    // ---------- 窗口拖动 / 缩放 / 最大化 / 最小化 ----------

    private val chatPrefs get() = getSharedPreferences("replymate", Context.MODE_PRIVATE)

    private fun screenW(): Int = resources.displayMetrics.widthPixels

    private fun screenH(): Int = resources.displayMetrics.heightPixels

    private fun minChatW(): Int = minOf(dp(260f), screenW() / 2).coerceAtLeast(dp(160f))

    private fun minChatH(): Int = minOf(dp(240f), screenH() / 3).coerceAtLeast(dp(140f))

    private fun defaultChatGeometry(): IntArray {
        val w = minOf(dp(360f), screenW() - dp(12f)).coerceAtLeast(minChatW())
        val h = minOf((screenH() * 0.72f).toInt(), dp(660f)).coerceAtLeast(minChatH())
        val x = ((screenW() - w) / 2).coerceAtLeast(0)
        val y = (screenH() - h - dp(14f)).coerceAtLeast(dp(24f))
        return intArrayOf(x, y, w, h)
    }

    /** 上次拖动/缩放的位置和大小，重新打开还停在原处 */
    private fun loadChatGeometry(): IntArray {
        val d = defaultChatGeometry()
        val w = chatPrefs.getInt("chat_w", d[2]).coerceIn(minChatW(), screenW())
        val h = chatPrefs.getInt("chat_h", d[3]).coerceIn(minChatH(), screenH())
        val x = chatPrefs.getInt("chat_x", d[0])
            .coerceIn(0, (screenW() - w).coerceAtLeast(0))
        val y = chatPrefs.getInt("chat_y", d[1])
            .coerceIn(0, (screenH() - h).coerceAtLeast(0))
        return intArrayOf(x, y, w, h)
    }

    private fun saveChatGeometry() {
        val p = chatParams ?: return
        // 最大化/最小化只是临时状态，不能覆盖用户正常的大小
        if (chatMaximized || chatMinimized) return
        chatPrefs.edit()
            .putInt("chat_x", p.x)
            .putInt("chat_y", p.y)
            .putInt("chat_w", p.width)
            .putInt("chat_h", p.height)
            .apply()
    }

    private fun clampChatParams(p: WindowManager.LayoutParams) {
        p.width = p.width.coerceIn(minChatW(), screenW())
        p.height = p.height.coerceIn(minChatH(), screenH())
        p.x = p.x.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
    }

    /** 按住顶部横条/标题栏拖动整个窗口 */
    private fun dragTouch(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, e ->
            val p = chatParams ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (!moved && (abs(dx) > dp(4f) || abs(dy) > dp(4f))) {
                        moved = true
                        // 真正开始拖了才震一下，纯点击标题栏不打扰
                        chatWindow?.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK
                        )
                    }
                    if (moved) {
                        p.x = (startX + dx).coerceIn(
                            0, (screenW() - p.width).coerceAtLeast(0)
                        )
                        p.y = (startY + dy).coerceIn(
                            0, (screenH() - p.height).coerceAtLeast(0)
                        )
                        if (chatMaximized) {
                            chatMaximized = false
                            chatMaxButton?.text = "□"
                        }
                        runCatching { chatWindow?.let { wm.updateViewLayout(it, p) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && chatMinimized && e.actionMasked == MotionEvent.ACTION_UP) {
                        // 最小化后整条窄条都能点开，不用非去戳那个小小的「▾」
                        toggleMinimize()
                    } else if (moved) {
                        saveChatGeometry()
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** 右下角「◢」拖动改窗口大小 */
    private fun resizeTouch(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0
        var resizing = false
        return View.OnTouchListener { _, e ->
            val p = chatParams ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startW = p.width
                    startH = p.height
                    resizing = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (!resizing && (abs(dx) > dp(3f) || abs(dy) > dp(3f))) {
                        resizing = true
                        chatWindow?.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK
                        )
                    }
                    if (resizing) {
                        val maxW = (screenW() - p.x).coerceAtLeast(minChatW())
                        val maxH = (screenH() - p.y).coerceAtLeast(minChatH())
                        p.width = (startW + dx).coerceIn(minChatW(), maxW)
                        p.height = (startH + dy).coerceIn(minChatH(), maxH)
                        if (chatMaximized) {
                            chatMaximized = false
                            chatMaxButton?.text = "□"
                        }
                        runCatching { chatWindow?.let { wm.updateViewLayout(it, p) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) saveChatGeometry()
                    true
                }
                else -> true
            }
        }
    }

    private fun windowControl(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(0xFF6E7486.toInt())
        background = roundedBg(0xFFF3F5FA.toInt(), 9f)
        minWidth = dp(36f)
        minHeight = dp(34f)
        setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(7f) }
    }.also { pressFx(it) }

    private fun toggleMaximize() {
        chatImeAdjusted = false
        if (chatMinimized) toggleMinimize()
        val p = chatParams ?: return
        val w = chatWindow ?: return
        if (!chatMaximized) {
            chatPrefs.edit()
                .putInt("chat_nx", p.x)
                .putInt("chat_ny", p.y)
                .putInt("chat_nw", p.width)
                .putInt("chat_nh", p.height)
                .apply()
            chatMaximized = true
            p.width = (screenW() - dp(16f)).coerceAtLeast(minChatW())
            p.height = (screenH() * 0.88f).toInt().coerceAtLeast(minChatH())
            p.x = ((screenW() - p.width) / 2).coerceAtLeast(0)
            p.y = dp(24f)
            clampChatParams(p)
            chatMaxButton?.text = "❐"
        } else {
            chatMaximized = false
            p.width = chatPrefs.getInt("chat_nw", p.width)
            p.height = chatPrefs.getInt("chat_nh", p.height)
            p.x = chatPrefs.getInt("chat_nx", p.x)
            p.y = chatPrefs.getInt("chat_ny", p.y)
            clampChatParams(p)
            chatMaxButton?.text = "□"
        }
        runCatching { wm.updateViewLayout(w, p) }
    }

    private fun toggleMinimize() {
        chatImeAdjusted = false
        val p = chatParams ?: return
        val w = chatWindow ?: return
        if (!chatMinimized) {
            chatRestoreHeight = p.height
            chatMinimized = true
            setChatContentVisible(false)
            chatMinButton?.text = "▾"
            toast("已最小化，点 ▾ 还原")
            animateChatHeight(dp(72f))
        } else {
            chatMinimized = false
            setChatContentVisible(true)
            chatMinButton?.text = "−"
            animateChatHeight(chatRestoreHeight.coerceAtLeast(minChatH())) {
                clampChatParams(p)
                runCatching { wm.updateViewLayout(w, p) }
            }
        }
    }

    /** 最小化/还原别瞬移：高度用 200ms 缓动过渡，收放都看得见 */
    private fun animateChatHeight(target: Int, onEnd: (() -> Unit)? = null) {
        val p = chatParams ?: return
        val w = chatWindow ?: return
        chatSizeAnimator?.cancel()
        val from = p.height
        if (from == target) {
            onEnd?.invoke()
            return
        }
        val anim = ValueAnimator.ofInt(from, target).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                p.height = a.animatedValue as Int
                p.y = p.y.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(w, p) }
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (chatSizeAnimator === anim) chatSizeAnimator = null
                onEnd?.invoke()
            }
        })
        chatSizeAnimator = anim
        anim.start()
    }

    private fun setChatContentVisible(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        chatSubtitleView?.visibility = vis
        chatBody?.visibility = vis
        chatTools?.visibility = vis
        chatInputRow?.visibility = vis
        chatHint?.visibility = vis
        chatResizer?.visibility = vis
    }

    /** 收起键盘。只 clearFocus 不会让输入法自己走，窗口会重新长到键盘下面 */
    private fun hideChatKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = chatInput?.windowToken ?: chatWindow?.windowToken ?: return
        runCatching { imm.hideSoftInputFromWindow(token, 0) }
    }

    /** 键盘弹出后把窗口缩到键盘上方，避免输入框被挡住，也不依赖 ADJUST_PAN 的坐标偏移 */
    private fun fitChatAboveKeyboard() {
        val p = chatParams ?: return
        val w = chatWindow ?: return
        if (chatMinimized || chatMaximized) return
        // 不能读窗口自己的 IME inset：窗口越缩，报告值越被裁剪，会越算越矮。
        // 用整个显示区域的 metrics 读；再留一个 38% 屏高的保守下限。
        val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            }.getOrDefault(0)
        } else 0
        val kb = maxOf(ime, (screenH() * 0.38f).toInt())
        // 窗口带 FLAG_LAYOUT_IN_SCREEN，贴到 0 会压住状态栏；至少让出状态栏高度
        val statusTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                w.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            }.getOrDefault(0)
        } else 0
        val topMargin = maxOf(dp(10f), (if (statusTop > 0) statusTop else dp(28f)) + dp(6f))
        val keyboardTop = (screenH() - kb - dp(8f))
            .coerceAtLeast(topMargin + minChatH())
        if (p.y + p.height <= keyboardTop) return
        if (!chatImeAdjusted) {
            chatPreImeY = p.y
            chatPreImeH = p.height
            chatImeAdjusted = true
        }
        if (p.height <= keyboardTop - topMargin) {
            p.y = (keyboardTop - p.height).coerceAtLeast(topMargin)
        } else {
            p.y = topMargin
            p.height = (keyboardTop - topMargin).coerceAtLeast(minChatH())
        }
        p.x = p.x.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(w, p) }
    }

    private fun restoreChatAfterKeyboard() {
        if (!chatImeAdjusted) return
        chatImeAdjusted = false
        if (chatMinimized || chatMaximized) return
        val p = chatParams ?: return
        val w = chatWindow ?: return
        p.y = chatPreImeY
        p.height = chatPreImeH
        clampChatParams(p)
        runCatching { wm.updateViewLayout(w, p) }
    }

    /** 大按钮的「大小」：小/中/大循环，够不到右下角手柄时也能改大小 */
    private fun cycleChatSize() {
        chatImeAdjusted = false
        if (chatMinimized) toggleMinimize()
        if (chatMaximized) toggleMaximize()
        val p = chatParams ?: return
        val w = chatWindow ?: return
        chatSizeStep = (chatSizeStep + 1) % 3
        val sizes = arrayOf(
            intArrayOf(dp(300f), dp(430f)),
            intArrayOf(
                minOf(dp(360f), screenW() - dp(12f)),
                minOf((screenH() * 0.62f).toInt(), dp(620f))
            ),
            intArrayOf((screenW() * 0.92f).toInt(), (screenH() * 0.82f).toInt())
        )
        val s = sizes[chatSizeStep]
        p.width = s[0].coerceIn(minChatW(), screenW())
        p.height = s[1].coerceIn(minChatH(), screenH())
        p.x = ((screenW() - p.width) / 2).coerceAtLeast(0)
        p.y = ((screenH() - p.height) / 2).coerceAtLeast(dp(24f))
        clampChatParams(p)
        runCatching { wm.updateViewLayout(w, p) }
        saveChatGeometry()
        toast("窗口 " + arrayOf("小", "中", "大")[chatSizeStep])
        // 键盘还开着时，改完大小要重新收到键盘上方。
        // 键盘还在做弹出动画时 IME inset 会偏小，隔几个时间点补测一次。
        if (chatInput?.hasFocus() == true) {
            listOf(120L, 400L, 700L).forEach { delay ->
                chatWindow?.postDelayed({ fitChatAboveKeyboard() }, delay)
            }
        }
    }

    private fun closeChat() {
        saveChatGeometry()
        chatSizeAnimator?.cancel()
        chatSizeAnimator = null
        chatWindow?.let { runCatching { wm.removeView(it) } }
        chatWindow = null
        chatParams = null
        chatScrollView = null
        chatListBox = null
        chatMemoryScroll = null
        chatMemoryListBox = null
        chatInput = null
        chatTitleView = null
        chatSubtitleView = null
        chatSendButton = null
        chatBody = null
        chatTools = null
        chatInputRow = null
        chatHint = null
        chatMaxButton = null
        chatMinButton = null
        chatResizer = null
        chatMaximized = false
        chatMinimized = false
        chatRestoreHeight = 0
        chatImeAdjusted = false
        showingMemoryList = false
        pendingDelete = null
        typingBubble = null
        if (!appMuted && panel == null) {
            // 球从聊天窗背后回来时淡入，别凭空出现
            ball?.let { b ->
                b.animate().cancel()
                if (b.visibility != View.VISIBLE) {
                    b.visibility = View.VISIBLE
                    b.alpha = 0f
                    b.scaleX = 0.82f
                    b.scaleY = 0.82f
                }
                if (b.alpha < 1f || b.scaleX < 1f || b.scaleY < 1f) {
                    b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
                }
            }
        }
    }

    /**
     * 点关闭/填入时不要立刻 removeView：
     * 窗口在 ACTION_UP 里被移除，同一个抬手事件会漏到下面的聊天 App，
     * 容易顺手点开职位详情或发送。延迟一帧再关。
     */
    private fun dismissChat() {
        val w = chatWindow ?: return
        chatSizeAnimator?.cancel()
        // 收起前缩小淡出，和「最小化」的收成窄条区分开
        w.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(130)
            .withEndAction { if (chatWindow === w) closeChat() }
            .start()
    }

    /** 找/建记忆并渲染。名字来自聊天页标题，读不到就叫「新对话」 */
    private fun bindMemory(name: String, pkg: String) {
        val seq = ++bindSeq
        val app = applicationContext as ReplyMateApp
        scope.launch {
            val m = runCatching { app.chatMemory.findOrCreate(name, pkg) }.getOrNull()
            if (seq != bindSeq || chatWindow == null) return@launch
            currentMemory = m
            showingMemoryList = false
            chatMemoryScroll?.visibility = View.GONE
            chatScrollView?.visibility = View.VISIBLE
            renderChatHistory()
            updateChatHeader()
        }
    }

    /** 读不到标题（分身应用/列表页）时复用最近一份未绑定应用的记忆，避免每次开新坑 */
    private fun bindLatestMemory() {
        val seq = ++bindSeq
        val app = applicationContext as ReplyMateApp
        scope.launch {
            val list = runCatching { app.chatMemory.all() }.getOrDefault(emptyList())
            val m = list.firstOrNull { it.appPkg.isBlank() }
                ?: runCatching { app.chatMemory.findOrCreate("新对话", "") }.getOrNull()
            if (seq != bindSeq || chatWindow == null) return@launch
            currentMemory = m
            showingMemoryList = false
            chatMemoryScroll?.visibility = View.GONE
            chatScrollView?.visibility = View.VISIBLE
            renderChatHistory()
            updateChatHeader()
        }
    }

    private fun updateChatHeader() {
        val m = currentMemory
        chatTitleView?.text = "记忆 · " + (m?.name ?: "新对话")
        val app = applicationContext as ReplyMateApp
        val count = app.chatMemory.turnCount(m)
        chatSubtitleView?.text = buildString {
            append(if (screenPkg.isBlank()) "未绑定聊天" else appLabel(screenPkg))
            if (screenTitle.isNotBlank()) append(" · ").append(screenTitle)
            append(" · ").append(count).append(" 条记忆")
            if (chatContext.isNotBlank()) append(" · 上下文 ").append(chatContext.length).append(" 字")
        }
    }

    private fun renderChatHistory() {
        val box = chatListBox ?: return
        box.removeAllViews()
        typingBubble = null
        val app = applicationContext as ReplyMateApp
        val m = currentMemory
        val turns = app.chatMemory.turns(m)
        if (turns.isEmpty()) {
            box.addView(
                systemBubble(
                    "这是「${m?.name ?: "新对话"}」的记忆。点「读屏」抓当前聊天，" +
                        "或者直接问我要怎么回；发出去的句子我也会记住。"
                )
            )
        } else {
            turns.takeLast(60).forEach { t ->
                when (t.role) {
                    ChatTurn.ROLE_USER -> box.addView(userBubble(t.text))
                    ChatTurn.ROLE_ASSISTANT -> {
                        val list = t.candidates.ifEmpty { listOf(t.text) }
                        if (list.any { it.isNotBlank() }) box.addView(assistantBubble(list))
                    }
                    ChatTurn.ROLE_ADOPTED -> box.addView(adoptedBubble(t.text))
                    else -> if (t.text.isNotBlank()) box.addView(systemBubble(t.text))
                }
            }
        }
        scrollChatToBottom()
    }

    private fun userBubble(text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(5f), 0, dp(1f))
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(dp(2f).toFloat(), 1f)
            background = roundedBg(0xFFB03A72.toInt(), 12f)
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            maxWidth = (resources.displayMetrics.widthPixels * 0.64f).roundToInt()
        })
        return row
    }

    private fun adoptedBubble(text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(2f), 0, dp(3f))
        }
        row.addView(TextView(this).apply {
            this.text = "已填入 · $text"
            textSize = 11f
            setTextColor(0xFF2F6B4F.toInt())
            background = roundedBg(0xFFEAF7F0.toInt(), 10f)
            setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
            maxWidth = (resources.displayMetrics.widthPixels * 0.68f).roundToInt()
        })
        return row
    }

    private fun systemBubble(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF6E7486.toInt())
        setLineSpacing(dp(2f).toFloat(), 1f)
        setPadding(dp(2f), dp(7f), dp(2f), dp(3f))
    }

    private fun assistantBubble(candidates: List<String>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFFFDF7FA.toInt(), 14f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        }
        card.addView(TextView(this).apply {
            text = "AI 建议 · 点「填入」直接进聊天框"
            textSize = 9.5f
            setTextColor(0xFF8A8FA3.toInt())
            setPadding(0, 0, 0, dp(3f))
        })
        candidates.filter { it.isNotBlank() }.forEachIndexed { index, txt ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5f), 0, dp(3f))
            }
            item.addView(TextView(this).apply {
                text = txt
                textSize = 13f
                setTextColor(0xFF14161C.toInt())
                setLineSpacing(dp(2f).toFloat(), 1f)
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(5f), 0, 0)
            }
            actions.addView(
                pillButton("复制") { copyText(txt) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6f) }
            )
            actions.addView(pillButton("填入", primary = index == 0) { fillFromChat(txt) })
            item.addView(actions)
            card.addView(item)
        }
        return card
    }

    private fun scrollChatToBottom() {
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    /** 新消息上浮淡入；只给实时新增用，整屏恢复历史时别调，避免几十个一起动 */
    private fun animateIn(v: View): View {
        v.alpha = 0f
        v.translationY = dp(10f).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(170)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        return v
    }

    /** 整屏重渲染后，让最后一条（刚生成的那条）有出现反馈 */
    private fun animateLastBubble() {
        val box = chatListBox ?: return
        if (box.childCount > 0) animateIn(box.getChildAt(box.childCount - 1))
    }

    /** 用户发问：把当前聊天上下文一起给模型，答案 3 条留在记忆里 */
    private fun sendChat() {
        if (chatBusy) {
            toast("正在生成，稍等")
            return
        }
        if (showingMemoryList) {
            toast("先返回对话再发送")
            return
        }
        val m = currentMemory
        if (m == null) {
            toast("记忆还在准备，稍等")
            return
        }
        val typed = chatInput?.text?.toString()?.trim().orEmpty()
        // 输入法里还没上屏（拼音/英文候选未确认）时，别把空内容当成「帮我回复最后一句」
        if (typed.isBlank() && chatInput?.hasFocus() == true) {
            toast("输入还没上屏，先在键盘上点候选字或空格确认")
            return
        }
        if (typed.isBlank() && chatContext.isBlank()) {
            toast("先点「读屏」抓当前聊天，或直接输入问题")
            return
        }
        // 明显不是「帮我回聊天」的内容：不调模型，直接给使用提示
        if (typed.isNotBlank() && looksOffTopic(typed)) {
            chatInput?.setText("")
            chatInput?.clearFocus()
            hideChatKeyboard()
            chatListBox?.addView(animateIn(userBubble(typed)))
            chatListBox?.addView(animateIn(systemBubble(CHAT_GUIDE)))
            scrollChatToBottom()
            toast("我是回复助手：先读屏 / 截图，再问怎么回")
            return
        }
        val question = typed.ifBlank { "帮我回复最后一句" }
        val context = chatContext.trim()
        chatInput?.setText("")
        // 发完收起键盘，窗口恢复原大小；答案出来时能看全
        chatInput?.clearFocus()
        hideChatKeyboard()
        chatListBox?.addView(animateIn(userBubble(question)))
        // 占位气泡：生成期间让用户看到「它在处理」，答案回来就换成真消息
        val typing = systemBubble("AI 正在想…").also { typingBubble = it }
        typingStartAt = System.currentTimeMillis()
        typing.post(typingTicker)
        chatListBox?.addView(animateIn(typing))
        scrollChatToBottom()

        val fullInput = buildString {
            append(question)
            if (context.isNotBlank()) {
                append("\n\n当前聊天内容（最后一句是对方说的）：\n")
                append(context.take(1400))
            }
        }
        chatBusy = true
        chatSendButton?.isEnabled = false
        chatSendButton?.alpha = 0.5f
        chatSubtitleView?.text = "正在生成…"

        val app = applicationContext as ReplyMateApp
        scope.launch {
            runCatching { app.chatMemory.append(m.id, ChatTurn(ChatTurn.ROLE_USER, question)) }
        }
        val req = GenRequest(
            taskType = TaskType.REPLY,
            input = fullInput,
            relation = currentRelation,
            goal = Goal.KEEP,
            style = app.settings.defaultStyle,
            emoji = panelEmoji,
            memory = app.chatMemory.memoryText(m, 10)
        )
        scope.launch {
            try {
                val res = app.generate.generate(req)
                // 模板兜底/缓存命中的说明要带上，否则用户以为模型就这水平
                appendAssistant(m.id, res.candidates.map { it.text }, res.notice)
            } catch (e: LlmException) {
                if (e.code == 0) {
                    val texts = EmojiFilter.applyAll(app.templates.pick(req), panelEmoji).map { it.text }
                    appendAssistant(m.id, texts, "还没配 Key，先用离线模板顶上")
                } else {
                    chatFail(m.id, com.wzy.replymate.data.humanizeLlmError(e))
                }
            } catch (e: Exception) {
                chatFail(m.id, e.message ?: "生成失败")
            } finally {
                chatBusy = false
                chatSendButton?.isEnabled = true
                chatSendButton?.alpha = 1f
                if (!showingMemoryList) updateChatHeader()
            }
        }
    }

    private fun appendAssistant(memoryId: Long, texts: List<String>, notice: String? = null) {
        val clean = texts.filter { it.isNotBlank() }
        if (clean.isEmpty()) {
            chatFail(memoryId, "模型没有返回内容")
            return
        }
        val app = applicationContext as ReplyMateApp
        scope.launch {
            val updated = runCatching {
                app.chatMemory.append(
                    memoryId,
                    ChatTurn(ChatTurn.ROLE_ASSISTANT, clean.first(), clean)
                )
            }.getOrNull()
            if (currentMemory?.id != memoryId) return@launch
            if (updated != null) currentMemory = updated
            renderChatHistory()
            animateLastBubble()
            if (notice != null) {
                chatListBox?.addView(animateIn(systemBubble(notice)))
                scrollChatToBottom()
            }
            updateChatHeader()
        }
    }

    private fun chatFail(memoryId: Long, msg: String) {
        clearTypingBubble()
        if (currentMemory?.id != memoryId) return
        chatListBox?.addView(animateIn(systemBubble("生成失败：$msg")))
        scrollChatToBottom()
    }

    private fun clearTypingBubble() {
        typingBubble?.let { v -> runCatching { chatListBox?.removeView(v) } }
        typingBubble = null
    }

    /** 模型偶尔要等十几秒，占位气泡里带上已等秒数，用户知道它还在跑 */
    private var typingStartAt = 0L
    private val typingTicker = object : Runnable {
        override fun run() {
            val tv = typingBubble as? TextView ?: return
            if (tv.parent == null) return
            val sec = (System.currentTimeMillis() - typingStartAt) / 1000
            if (sec >= 5) tv.text = "AI 正在想… 已等 ${sec}s，网络慢时会久一点"
            tv.postDelayed(this, 1000)
        }
    }

    /** 读当前聊天页；记忆名还是「新对话」时顺手改成真实标题 */
    private fun refreshChatFromScreen(silent: Boolean = false, retried: Boolean = false) {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            if (!silent) toast("读屏未开启")
            return
        }
        // 遍历节点慢的时候像没反应，先给个状态
        if (!silent) chatSubtitleView?.text = "正在读屏…"
        // 遍历节点在部分 ROM 上很慢（上千次 IPC），放后台线程，别把悬浮窗卡死
        scope.launch {
            // 同样走单飞：面板/轻聊正在读屏时复用那次结果，不再并发扫第二遍
            val snap = acc.readScreenShared().await()
            if (snap == null || snap.lines.isEmpty()) {
                if (!retried && acc.isShadeOpen()) {
                    acc.collapseShade()
                    android.os.Handler(mainLooper).postDelayed({
                        refreshChatFromScreen(silent, true)
                    }, 700)
                    return@launch
                }
                if (!silent) {
                    // 部分 ROM（如 Honor）只把焦点窗口暴露给无障碍，悬浮窗开着时
                    // 读不到后面的聊天；自动降级成截图识别，别让用户自己再点一次。
                    toast("读屏读不到，改用截图识别")
                    screenshotToChat()
                }
                return@launch
            }
            if (!snap.chatInput) {
                // 列表页/搜索页读出来的是标题和摘要，不是对话，不能当上下文
                if (!silent) {
                    toast("这页不像聊天页，先打开具体聊天")
                    updateChatHeader()
                }
                return@launch
            }
            lastSnapshot = snap
            screenPkg = snap.pkg
            screenTitle = snap.title
            chatContext = snap.asPromptText()
            val m = currentMemory
            val app = applicationContext as ReplyMateApp
            if (m != null && snap.title.isNotBlank() &&
                (m.name == "新对话" || m.name.startsWith("新对话 "))
            ) {
                val upd = runCatching { app.chatMemory.rename(m.id, snap.title) }.getOrNull()
                if (upd != null && currentMemory?.id == m.id) {
                    currentMemory = upd
                    renderChatHistory()
                }
                updateChatHeader()
            } else {
                updateChatHeader()
            }
            if (!silent) {
                chatListBox?.addView(
                    animateIn(
                        systemBubble(
                            "已读取当前聊天 · ${snap.lines.size} 条" +
                                (if (snap.title.isNotBlank()) " · ${snap.title}" else "")
                        )
                    )
                )
                scrollChatToBottom()
            }
        }
    }

    /** 分身应用读不到节点时：隐窗截屏 → 本地 OCR → 当上下文 */
    private fun screenshotToChat() {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            toast("无障碍服务未连接")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("系统版本不支持截图取词")
            return
        }
        val win = chatWindow ?: return
        // 只设 INVISIBLE 时窗口表面还留着上一帧，OCR 会把悬浮窗自己的字读进来；
        // 用窗口 alpha=0 让合成器真正隐藏这个窗口。
        val p = chatParams
        val oldAlpha = p?.alpha ?: 1f
        p?.alpha = 0f
        p?.let { runCatching { wm.updateViewLayout(win, it) } }
        chatSubtitleView?.text = "截屏识别中…"
        android.os.Handler(mainLooper).postDelayed({
            scope.launch {
                val raw = runCatching { acc.screenshotText() }.getOrNull()
                p?.alpha = oldAlpha
                p?.let { runCatching { wm.updateViewLayout(win, it) } }
                if (raw.isNullOrBlank()) {
                    toast("截图识别失败，可能被系统限制")
                    // 有些机型不给无障碍截图：把聊天内容复制/粘贴进来也能当上下文
                    chatListBox?.addView(
                        animateIn(
                            systemBubble("这台手机限制了截图取词。把对方的话复制下来，直接粘贴到输入框发给我，我也能按它回。")
                        )
                    )
                    scrollChatToBottom()
                    updateChatHeader()
                    return@launch
                }
                val text = cleanOcr(raw)
                lastSnapshot = null
                chatContext = text
                // 截图路径没有标题，或者节点把返回键读成了标题：猜一个联系人，
                // 按它切换/接上记忆
                val titleOk = screenTitle.length in 2..20 &&
                    startsLikeName(screenTitle) &&
                    !screenTitle.matches(Regex("^\\d{1,2}:\\d{2}.*"))
                if (!titleOk) {
                    val guess = guessChatTitle(raw)
                    if (guess.isNotBlank()) {
                        screenTitle = guess
                        screenPkg = ""
                        val app = applicationContext as ReplyMateApp
                        val old = currentMemory
                        currentMemory = app.chatMemory.findOrCreate(guess, "")
                        // 原来那条空的占位/误识别记忆留着只会让记忆列表变脏
                        val junkName = old?.name?.let {
                            it == "新对话" || it.startsWith("新对话 ") ||
                                it.matches(Regex("^\\d{1,2}:\\d{2}.*")) ||
                                !startsLikeName(it)
                        } ?: false
                        if (old != null && old.id != currentMemory?.id && junkName &&
                            app.chatMemory.turnCount(old) == 0
                        ) {
                            runCatching { app.chatMemory.delete(old.id) }
                        }
                        renderChatHistory()
                    }
                }
                chatListBox?.addView(
                    animateIn(
                        systemBubble("截图识别 ${text.length} 字，已作为上下文。直接点发送就能回。")
                    )
                )
                scrollChatToBottom()
                updateChatHeader()
            }
        }, 400)
    }

    private fun openSuggestPanel() {
        dismissChat()
        android.os.Handler(mainLooper).postDelayed({ readAndShow() }, 400)
    }

    // ---------- 记忆切换列表 ----------

    private fun toggleMemoryList(show: Boolean) {
        showingMemoryList = show
        if (!show) pendingDelete = null
        if (show) {
            // 切记忆时先把键盘收掉，否则列表会被输入法压住，还会留着半截输入
            chatInput?.clearFocus()
            hideChatKeyboard()
            chatScrollView?.visibility = View.GONE
            chatMemoryScroll?.visibility = View.VISIBLE
            renderMemoryList()
        } else {
            chatMemoryScroll?.visibility = View.GONE
            chatScrollView?.visibility = View.VISIBLE
            renderChatHistory()
            updateChatHeader()
        }
    }

    private fun renderMemoryList() {
        val box = chatMemoryListBox ?: return
        box.removeAllViews()
        addMemoryListTop(box)
        val app = applicationContext as ReplyMateApp
        scope.launch {
            val list = runCatching { app.chatMemory.all() }.getOrDefault(emptyList())
            if (chatMemoryListBox !== box) return@launch
            box.removeAllViews()
            addMemoryListTop(box)
            if (list.isEmpty()) {
                box.addView(systemBubble("还没有记忆。点「读屏」后聊一句就会自动建一份。"))
            } else {
                list.forEach { m -> box.addView(memoryRow(m)) }
            }
        }
    }

    private fun addMemoryListTop(box: LinearLayout) {
        box.addView(memoryActionRow("← 返回当前对话") { toggleMemoryList(false) })
        box.addView(memoryActionRow("＋ 新建一个对话记忆") { newChat() })
        val pending = pendingDelete
        if (pending != null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(0xFFFFF1F1.toInt(), 10f)
                setPadding(dp(9f), dp(5f), dp(4f), dp(5f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6f) }
            }
            bar.addView(TextView(this).apply {
                text = "删除「${pending.name}」？"
                textSize = 11.5f
                setTextColor(0xFFB44848.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(chatChip("取消") {
                pendingDelete = null
                renderMemoryList()
            })
            bar.addView(chatChip("删除") { confirmDeleteMemory(pending) })
            box.addView(bar)
        }
        box.addView(TextView(this).apply {
            text = "长按一条记忆可删除"
            textSize = 9.5f
            setTextColor(0xFF9AA0B0.toInt())
            setPadding(dp(4f), dp(6f), dp(4f), 0)
        })
    }

    private fun memoryActionRow(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFFB03A72.toInt())
            setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
            setOnClickListener { onClick() }
        }.also { pressFx(it) }

    private fun memoryRow(m: ChatMemoryEntity): View {
        val app = applicationContext as ReplyMateApp
        val count = app.chatMemory.turnCount(m)
        val selected = currentMemory?.id == m.id
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(
                if (selected) 0xFFFBEAF2.toInt() else 0xFFFDF7FA.toInt(),
                12f,
                if (selected) 0xFFC9CEF8.toInt() else null
            )
            setPadding(dp(10f), dp(8f), dp(6f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = m.name
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14161C.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = appLabel(m.appPkg) + " · " + count + " 条 · " + fmtTime(m.updatedAt)
            textSize = 9.5f
            setTextColor(0xFF8A8FA3.toInt())
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(
            info,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.setOnClickListener { switchMemory(m) }
        row.setOnLongClickListener {
            pendingDelete = m
            renderMemoryList()
            true
        }
        return pressFx(row)
    }

    private fun switchMemory(m: ChatMemoryEntity) {
        pendingDelete = null
        val sameChat = m.name == screenTitle &&
            (m.appPkg.isBlank() || screenPkg.isBlank() || m.appPkg == screenPkg)
        if (!sameChat) {
            // 切到别的联系人时，当前屏幕读到的上下文不能带过去
            chatContext = ""
            lastSnapshot = null
            lastInputText = null
        }
        currentMemory = m
        screenTitle = m.name
        screenPkg = m.appPkg
        toggleMemoryList(false)
    }

    private fun newChat() {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        val name = if (screenTitle.isNotBlank() && !screenTitle.startsWith("新对话")) {
            "$screenTitle · $stamp"
        } else {
            "新对话 $stamp"
        }
        bindMemory(name, screenPkg)
    }

    private fun confirmDeleteMemory(m: ChatMemoryEntity) {
        pendingDelete = null
        val app = applicationContext as ReplyMateApp
        scope.launch {
            runCatching { app.chatMemory.delete(m.id) }
            if (currentMemory?.id == m.id) {
                currentMemory = null
                bindMemory("新对话", screenPkg)
            } else {
                renderMemoryList()
            }
        }
    }

    private fun fillFromChat(text: String) {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            toast("无障碍未连接")
            return
        }
        val pkg = lastSnapshot?.pkg ?: screenPkg.takeIf { it.isNotBlank() }
        markAdopted(text)
        dismissChat()
        android.os.Handler(mainLooper).postDelayed({
            val ok = if (pkg != null) acc.fillInput(text, pkg) else acc.fillInput(text)
            if (ok) {
                toast("已填入，检查一下再发")
            } else {
                acc.copyToClipboard(text)
                toast("已复制，长按输入框粘贴")
            }
        }, 450)
    }

    private fun markAdopted(text: String) {
        val m = currentMemory ?: return
        val app = applicationContext as ReplyMateApp
        scope.launch {
            runCatching { app.chatMemory.append(m.id, ChatTurn(ChatTurn.ROLE_ADOPTED, text)) }
        }
    }

    private fun chatChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10.5f
        setTextColor(0xFF3B4154.toInt())
        background = roundedBg(0xFFF3F5FA.toInt(), 8f)
        setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(5f) }
    }.also { pressFx(it) }

    private fun appLabel(pkg: String): String = when {
        pkg.isBlank() -> "未绑定聊天"
        pkg.contains("tencent.mm") -> "微信"
        pkg.contains("wework") -> "企业微信"
        pkg.contains("mobileqq") -> "QQ"
        pkg.contains("feishu") || pkg.contains("lark") -> "飞书"
        pkg.contains("dingtalk") -> "钉钉"
        pkg.contains("whatsapp", true) -> "WhatsApp"
        pkg.contains("telegram", true) -> "Telegram"
        else -> pkg.substringAfterLast('.')
    }

    private fun fmtTime(ts: Long): String {
        if (ts <= 0) return ""
        val fmt = if (System.currentTimeMillis() - ts < 24 * 3600_000L) {
            SimpleDateFormat("HH:mm", Locale.CHINA)
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        }
        return fmt.format(Date(ts))
    }

    private fun cleanOcr(raw: String): String = raw.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { Regex("^\\d{1,2}:\\d{2}$").matches(it) }
        .joinToString("\n")

    /** 截图识别拿不到包名/标题，用标题栏那一行猜联系人，记忆才不会全挤在「新对话」 */
    private fun guessChatTitle(raw: String): String {
        val skip = setOf("5G", "4G", "5G+", "4G+", "WiFi", "WIFI", "HD", "高清通话",
            "中国移动", "中国联通", "中国电信", "短信", "彩信", "微信")
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.length in 2..20 }
            // 过滤时间/电量这类纯数字行，但保留 555-1234 这种号码标题
            .filterNot { line -> line.matches(Regex("^[\\d\\s.,:%+]+$")) }
            // 状态栏可能被 OCR 成「23:41 7.0K/s 5G」，不能当联系人
            .filterNot { line -> line.matches(Regex("^\\d{1,2}:\\d{2}.*")) }
            .filterNot { line -> line.contains("/s") || line.contains("/S") || line.contains("%") }
            // 微信标题栏左边的「< 2」返回+未读角标也会被 OCR 成一行；
            // 正常联系人/号码以文字、数字或中文括号开头
            .filter { line -> startsLikeName(line) }
            .firstOrNull { it !in skip }
            .orEmpty()
    }

    /**
     * 读屏失败时的兜底内容：粘贴剪贴板 / 再读一次 / 去开读屏 / 打开 App。
     * 这条路径不需要无障碍，保证「点了球一定有结果」。
     */
    /**
     * 读不到聊天内容时的兜底面板。
     * 此时没有原文可推断关系，所以把关系 chips 展开给用户手动兜底——
     * 正常读到内容时则是收起的（自动判断，不让用户选）。
     */
    private fun renderFallback(message: String, showSettings: Boolean) {
        val box = listBox ?: return
        batchButton?.visibility = View.GONE
        box.removeAllViews()

        relationExpanded = true
        renderRelationChips()

        box.addView(TextView(this).apply {
            text = message
            textSize = 11.5f
            setTextColor(0xFF545B6E.toInt())
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(0, dp(10f), 0, dp(8f))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(pillButton("粘贴剪贴板", primary = true) { pasteAndGenerate(openPanelIfClosed = false) })
        row.addView(pillButton("再读一次") { readAndShow() })
        box.addView(row)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        row2.addView(pillButton("截图取词") { screenshotAndGenerate() })
        if (showSettings) {
            row2.addView(pillButton("去开启读屏") { openAccessibilitySettings() })
        }
        box.addView(row2)

        box.addView(TextView(this).apply {
            text = "打开 App 可截图识别 / 手动输入 ›"
            textSize = 10.5f
            setTextColor(0xFFB03A72.toInt())
            setPadding(0, dp(10f), 0, 0)
            setOnClickListener { hidePanel(); openApp() }
        })
    }

    private fun pasteAndGenerate(openPanelIfClosed: Boolean) {
        val text = readClipboard()
        if (openPanelIfClosed && panel == null) {
            lastSnapshot = null
            lastInputText = null
            showPanel(if (text.isNullOrBlank()) "剪贴板为空" else "已读取剪贴板")
            if (text.isNullOrBlank()) {
                renderFallback("剪贴板是空的。先在聊天里复制对方最后一句，再点「粘贴剪贴板」。", false)
                return
            }
        }
        if (text.isNullOrBlank()) {
            toast("剪贴板是空的，先在聊天里复制一条消息")
            return
        }
        lastSnapshot = null
        statusView?.text = "已读取剪贴板"
        generateFromText(text)
    }

    /**
     * 读屏读不到（分身/WebView/自定义控件）时：先把面板藏起来，截当前屏幕，
     * 本地 OCR 出文字再生成。图片不出本机。
     */
    private fun screenshotAndGenerate() {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            toast("无障碍服务未连接")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("系统版本不支持截图取词")
            return
        }
        val panelView = panel
        // 同聊天窗：INVISIBLE 会残留上一帧，必须让窗口本身透明
        val pp = panelParams
        val oldAlpha = pp?.alpha ?: 1f
        pp?.alpha = 0f
        pp?.let { runCatching { wm.updateViewLayout(panelView ?: return, it) } }
        statusView?.text = "截屏识别中…"
        android.os.Handler(mainLooper).postDelayed({
            scope.launch {
                val raw = runCatching { acc.screenshotText() }.getOrNull()
                pp?.alpha = oldAlpha
                pp?.let { runCatching { wm.updateViewLayout(panelView ?: return@launch, it) } }
                if (raw.isNullOrBlank()) {
                    toast("截图识别失败，可能被系统限制")
                    statusView?.text = "截图识别失败"
                    return@launch
                }
                val text = raw.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .filterNot { Regex("^\\d{1,2}:\\d{2}$").matches(it) }
                    .joinToString("\n")
                lastSnapshot = null
                statusView?.text = "截图识别 · ${text.length} 字"
                generateFromText(text)
            }
        }, 250)
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun buildPanel(statusText: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFFFFFFFF.toInt(), 20f, 0xFFEFE8EE.toInt())
            setPadding(dp(14f), dp(13f), dp(14f), dp(12f))
            // 同上：阴影会让触摸热区偏移，这里用描边代替阴影
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "回复建议"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14161C.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "聊天"
            textSize = 11f
            setTextColor(0xFFB03A72.toInt())
            background = roundedBg(0xFFFBEAF2.toInt(), 8f)
            setPadding(dp(9f), dp(3f), dp(9f), dp(3f))
            setOnClickListener {
                hidePanel()
                openChat()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(10f) })
        titleRow.addView(TextView(this).apply {
            text = "×"
            textSize = 19f
            setTextColor(0xFF9AA0B0.toInt())
            setPadding(dp(8f), 0, dp(2f), dp(2f))
            setOnClickListener { hidePanel() }
        })
        root.addView(titleRow)

        statusView = TextView(this).apply {
            text = statusText
            textSize = 10.5f
            setTextColor(0xFF9AA0B0.toInt())
            setPadding(0, dp(3f), 0, 0)
        }
        root.addView(statusView)

        // 关系：默认只显示自动判断的结果，点开才展开选项。
        // 首屏不放任何「要用户先选」的控件——聊天中途弹面板，多一行选择就多一分打断。
        relationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, 0)
        }
        root.addView(relationRow)
        renderRelationChips()

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 限高：面板最多占 42% 屏高，再多就内部滚动，别把原文全盖住
        val scroller = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // 5 条候选比 3 条长得多，给到 62% 屏高，尽量一屏看全；再多才内部滚动
                val maxPx = (resources.displayMetrics.heightPixels * 0.62f).roundToInt()
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST))
            }
        }
        scroller.isVerticalScrollBarEnabled = false
        scroller.addView(
            listBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, 0)
        }
        val bb = pillButton("↻ 换一批") { regenerate(forceRefresh = true) }
        batchButton = bb
        bottom.addView(bb)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(TextView(this).apply {
            // 次要路线：情况复杂、五条都不合适时，进聊天窗像 DeepSeek 那样追问
            text = "问得更细 ›"
            textSize = 11f
            setTextColor(0xFFB03A72.toInt())
            setPadding(dp(6f), dp(5f), 0, dp(5f))
            setOnClickListener {
                hidePanel()
                openChat()
            }
        })
        root.addView(bottom)
        return root
    }

    private fun miniLabel(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 9.5f
        setTextColor(0xFF9AA0B0.toInt())
        setPadding(dp(2f), 0, dp(5f), 0)
    }

    private fun miniChip(t: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 10f
        setTextColor(0xFF545B6E.toInt())
        background = roundedBg(0xFFF2F0F4.toInt(), 7f)
        setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(4f) }
    }

    private fun styleChip(tv: TextView?, on: Boolean) {
        if (tv == null) return
        tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF545B6E.toInt())
        tv.background = roundedBg(if (on) 0xFFB03A72.toInt() else 0xFFF2F0F4.toInt(), 7f)
    }

    private fun renderRoleChips() {
        val fromMe = lastSnapshot?.lines?.lastOrNull()?.fromMe ?: false
        styleChip(roleMe, fromMe)
        styleChip(roleOther, !fromMe)
    }

    private fun renderEmojiChips() {
        styleChip(emojiNone, panelEmoji == EmojiPolicy.NONE)
        styleChip(emojiLight, panelEmoji == EmojiPolicy.LIGHT)
    }

    /** 判错角色时一键修正：翻掉最后一句的归属后重新生成 */
    private fun setRole(fromMe: Boolean) {
        val snap = lastSnapshot ?: return
        if (snap.lines.isEmpty()) return
        val fixed = snap.copy(
            lines = snap.lines.mapIndexed { i, l ->
                if (i == snap.lines.lastIndex) l.copy(fromMe = fromMe) else l
            }
        )
        lastSnapshot = fixed
        renderRoleChips()
        generate(fixed)
    }

    private fun setEmoji(p: EmojiPolicy) {
        if (panelEmoji == p) return
        panelEmoji = p
        renderEmojiChips()
        regenerate()
    }

    /**
     * 重跑：换关系/表情时先查话术库；「换一批」强制绕过缓存，
     * 并把上一批原文作为 avoid 传下去，保证新一批不重复但不跑题。
     */
    private fun regenerate(forceRefresh: Boolean = false) {
        val avoid = if (forceRefresh) lastCandidates else emptyList()
        val snap = lastSnapshot
        if (snap != null) {
            generate(snap, forceRefresh, avoid)
        } else {
            lastInputText?.let { generateFromText(it, forceRefresh = forceRefresh, avoid = avoid) }
        }
    }

    /**
     * 关系默认收起：只显示自动判断结果 + 一个「▾」暗示可改。
     * 用户不点就永远不用管它；点了才展开四个选项。
     */
    private fun renderRelationChips() {
        val row = relationRow ?: return
        row.removeAllViews()

        if (!relationExpanded) {
            row.addView(TextView(this).apply {
                text = "${currentRelation.label} ▾"
                textSize = 11f
                setTextColor(0xFFB03A72.toInt())
                background = roundedBg(0xFFFBEAF2.toInt(), 9f)
                setPadding(dp(11f), dp(6f), dp(11f), dp(6f))
                setOnClickListener {
                    relationExpanded = true
                    renderRelationChips()
                }
            })
            return
        }

        listOf(Relation.BOSS, Relation.COLLEAGUE, Relation.FRIEND, Relation.FLIRT).forEach { r ->
            val on = r == currentRelation
            row.addView(TextView(this).apply {
                text = r.label
                textSize = 11f
                setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF545B6E.toInt())
                background = roundedBg(
                    if (on) 0xFFB03A72.toInt() else 0xFFF2F0F4.toInt(),
                    9f
                )
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                setOnClickListener {
                    relationManual = true
                    if (currentRelation != r) {
                        currentRelation = r
                        regenerate()
                    }
                    relationExpanded = false
                    renderRelationChips()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6f) })
        }
        row.addView(TextView(this).apply {
            text = "收起"
            textSize = 11f
            setTextColor(0xFF8A8FA3.toInt())
            setPadding(dp(8f), dp(5f), dp(4f), dp(5f))
            setOnClickListener {
                relationExpanded = false
                renderRelationChips()
            }
        })
    }

    /**
     * 从聊天内容 + 来源 App 自动判断关系，省掉用户手选。
     * 判错也没关系：关系 chip 点开就能改，改过之后不再自动覆盖。
     */
    private fun inferRelation(snap: ReplyAccessibilityService.Snapshot): Relation {
        val text = snap.lines.joinToString(" ") { it.text }
        fun hit(vararg keys: String) = keys.any { text.contains(it, ignoreCase = true) }

        return when {
            hit("HR", "人事", "薪资", "面试", "简历", "offer", "入职", "社保", "试用期") -> Relation.HR
            hit("王总", "李总", "张总", "刘总", "老板", "领导", "经理", "总监", "老大", "主任") -> Relation.BOSS
            hit("甲方", "尾款", "合同", "报价单", "乙方") -> Relation.CLIENT
            hit("方案", "报告", "进度", "交付", "排期", "需求文档", "复盘", "评审") -> Relation.COLLEAGUE
            hit("亲爱的", "宝宝", "想你", "么么", "抱抱", "晚安呀") -> Relation.FLIRT
            hit("妈", "爸", "奶奶", "外婆", "舅舅", "姑姑", "家里") -> Relation.FAMILY
            snap.pkg.contains("dingtalk") || snap.pkg.contains("rimet") ||
                snap.pkg.contains("lark") || snap.pkg.contains("wework") -> Relation.COLLEAGUE
            else -> Relation.FRIEND
        }
    }

    private fun generate(
        snap: ReplyAccessibilityService.Snapshot,
        forceRefresh: Boolean = false,
        avoid: List<String> = emptyList()
    ) {
        generateFromText(snap.asPromptText(), snap.lines.size, forceRefresh, avoid)
    }

    private fun generateFromText(
        text: String,
        lineCount: Int? = null,
        forceRefresh: Boolean = false,
        avoid: List<String> = emptyList()
    ) {
        if (busy || text.isBlank()) return
        lastInputText = text
        busy = true
        batchButton?.visibility = View.VISIBLE
        statusView?.text = if (lineCount != null) "正在生成 · 读到 $lineCount 条" else "正在生成…"
        listBox?.removeAllViews()
        listBox?.addView(TextView(this).apply {
            this.text = "…"
            textSize = 20f
            setTextColor(0xFFB4B9C7.toInt())
            setPadding(0, dp(14f), 0, dp(14f))
            gravity = Gravity.CENTER
        })

        val app = applicationContext as ReplyMateApp
        val req = GenRequest(
            taskType = TaskType.REPLY,
            input = text,
            relation = currentRelation,
            goal = Goal.KEEP,
            style = app.settings.defaultStyle,
            emoji = panelEmoji,
            avoid = avoid
        )

        scope.launch {
            try {
                val res = app.generate.generate(req, forceRefresh)
                renderResult(res)
            } catch (e: LlmException) {
                if (e.code == 0) {
                    // 还没配 Key：不空手，先给离线模板并明确告知
                    val list = EmojiFilter.applyAll(app.templates.pick(req), panelEmoji)
                    renderResult(
                        GenResult(
                            list,
                            Source.TEMPLATE,
                            0,
                            "还没配 Key，先给你 3 条离线模板"
                        )
                    )
                } else {
                    renderError(com.wzy.replymate.data.humanizeLlmError(e))
                }
            } catch (e: Exception) {
                renderError(e.message ?: "生成失败")
            } finally {
                busy = false
            }
        }
    }

    private fun renderResult(res: GenResult) {
        val box = listBox ?: return
        // 有原文时关系是自动判断的，收起选择器；没有原文（粘贴路径）则保持展开供手动兜底
        if (lastSnapshot?.lines?.isNotEmpty() == true && relationExpanded) {
            relationExpanded = false
            renderRelationChips()
        }
        box.removeAllViews()
        // 来源只在卡片角标与 notice 里出现一次，状态行不再重复
        val suffix = when {
            res.fromCache -> " · 话术库命中"
            res.source == Source.MODEL -> " · ${res.latencyMs}ms"
            else -> ""
        }
        val count = lastSnapshot?.lines?.size
        // 状态行优先说「在回哪一句」，让用户一眼确认系统抓对了目标
        val target = lastSnapshot?.lines?.lastOrNull { !it.fromMe }?.text
            ?.let { if (it.length > 12) it.take(12) + "…" else it }
        statusView?.text = when {
            !target.isNullOrBlank() -> "回复「$target」· ${currentRelation.label}$suffix"
            count != null -> "读到 $count 条 · ${currentRelation.label}$suffix"
            else -> "${currentRelation.label}$suffix"
        }
        lastCandidates = res.candidates.map { it.text }
        res.candidates.take(3).forEachIndexed { index, c ->
            box.addView(candidateCard(index, c, res.source))
        }
        if (res.notice != null) {
            box.addView(TextView(this).apply {
                text = res.notice
                textSize = 10f
                setTextColor(0xFF8A6A2F.toInt())
                setPadding(0, dp(6f), 0, 0)
            })
        }
    }

    private fun renderError(msg: String) {
        val box = listBox ?: return
        box.removeAllViews()
        statusView?.text = "生成失败"
        box.addView(TextView(this).apply {
            text = msg
            textSize = 11.5f
            setTextColor(0xFFC0392B.toInt())
            setPadding(0, dp(12f), 0, dp(6f))
        })
        // 模型欠费/网络断时也给一条出路：手动切离线模板，不空手
        box.addView(
            pillButton("先用 3 条离线模板", primary = true) { renderTemplates() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        )
    }

    /** 用当前输入（读屏或剪贴板）走离线模板，整批标记为离线模板 */
    private fun renderTemplates() {
        val text = lastInputText ?: lastSnapshot?.asPromptText() ?: return
        val app = applicationContext as ReplyMateApp
        val req = GenRequest(
            taskType = TaskType.REPLY,
            input = text,
            relation = currentRelation,
            goal = Goal.KEEP,
            style = app.settings.defaultStyle,
            emoji = panelEmoji
        )
        val list = EmojiFilter.applyAll(app.templates.pick(req), panelEmoji)
        renderResult(GenResult(list, Source.TEMPLATE, 0, "离线模板 · 未调用模型"))
    }

    private fun candidateCard(index: Int, c: Candidate, source: Source): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFFFDF7FA.toInt(), 14f)
            setPadding(dp(10f), dp(9f), dp(10f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "${index + 1}"
            textSize = 9.5f
            setTextColor(0xFFB03A72.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(0xFFFBEAF2.toInt(), 7f)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        })
        if (c.styleLabel.isNotBlank()) {
            head.addView(tagView(c.styleLabel, 0xFFB03A72.toInt(), 0xFFFBEAF2.toInt()))
        }
        head.addView(
            tagView(
                if (source == Source.TEMPLATE) "离线模板" else "模型",
                0xFF8A8FA3.toInt(),
                0xFFEFF1F6.toInt()
            )
        )
        card.addView(head)

        card.addView(TextView(this).apply {
            text = c.text
            textSize = 13.5f
            setTextColor(0xFF14161C.toInt())
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(6f), 0, 0)
        })

        if (c.why.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "为什么 · ${c.why}"
                textSize = 10.5f
                setTextColor(0xFF8A6A2F.toInt())
                background = roundedBg(0xFFFDF6E8.toInt(), 8f)
                setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7f) })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f), 0, 0)
        }
        actions.addView(pillButton("复制") { copyText(c.text) })
        actions.addView(pillButton("填入输入框", primary = true) { fillText(c.text) })
        card.addView(actions)
        return card
    }

    private fun fillText(text: String) {
        val acc = ReplyAccessibilityService.instance
        if (acc == null) {
            toast("无障碍未连接")
            return
        }
        if (acc.fillInput(text)) {
            toast("已填入，检查一下再发")
            hidePanel()
        } else {
            acc.copyToClipboard(text)
            toast("已复制，长按输入框粘贴")
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
        toast("已复制")
    }

    private fun tagView(text: String, fg: Int, bg: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 9.5f
        setTextColor(fg)
        background = roundedBg(bg, 6f)
        setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(5f) }
    }

    private fun pillButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF3B4154.toInt())
            background = roundedBg(
                if (primary) 0xFFB03A72.toInt() else 0xFFFFFFFF.toInt(),
                9f,
                if (primary) null else 0xFFDCE0EA.toInt()
            )
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6f) }
        }.also { pressFx(it) }

    private fun roundedBg(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1f).coerceAtLeast(1), stroke)
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 统一的按压反馈：按下轻微缩小变淡，松手弹回并轻震一下；不拦截点击 */
    private fun pressFx(v: View): View {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().alpha(0.72f).scaleX(0.95f).scaleY(0.95f)
                        .setDuration(60).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(140).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
            false
        }
        return v
    }

    private inner class BallView(ctx: Context) : View(ctx) {

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C1626.toInt()
            // 不要 setShadowLayer：窗口只有球这么大，阴影会被窗口边界裁成方块
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f).toFloat()
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.7f).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val bubbleRect = RectF()
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** 拖拽时点亮外扩光晕 */
        var halo = false
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val s = dp(BALL_DP)
            setMeasuredDimension(s, s)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = dp(BALL_CORE / 2f).toFloat()

            // 拖拽光晕：粉色外扩，提示「球被抓住了」
            if (halo) {
                val hr = r + dp(6f)
                haloPaint.shader = RadialGradient(
                    cx, cy, hr,
                    intArrayOf(0x80E8567A.toInt(), 0x33C77DFF.toInt(), 0x00E8567A),
                    floatArrayOf(0.62f, 0.84f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, hr, haloPaint)
            }

            // 球体：深色实心，沉进页面而不是浮在上面
            canvas.drawCircle(cx, cy, r, bodyPaint)

            // 描边：140deg 渐变，紫 → 玫红(55%) → 橙
            ringPaint.shader = LinearGradient(
                cx - r * 0.64f, cy - r * 0.77f,
                cx + r * 0.77f, cy + r * 0.64f,
                intArrayOf(0xFFC77DFF.toInt(), 0xFFE8567A.toInt(), 0xFFF59B3C.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r - dp(0.75f).toFloat(), ringPaint)

            // 图标：圆角气泡 + 尾巴，1.7dp 白描边
            val bw = dp(9.5f).toFloat()
            val bh = dp(7.5f).toFloat()
            val oy = dp(1.2f).toFloat()
            bubbleRect.set(cx - bw, cy - bh + oy, cx + bw, cy + bh + oy)
            canvas.drawRoundRect(bubbleRect, dp(4.2f).toFloat(), dp(4.2f).toFloat(), iconPaint)

            val tail = Path().apply {
                moveTo(cx - dp(4.4f).toFloat(), cy + bh + oy)
                lineTo(cx - dp(5.8f).toFloat(), cy + bh + oy + dp(3.2f).toFloat())
                lineTo(cx - dp(0.9f).toFloat(), cy + bh + oy)
            }
            canvas.drawPath(tail, iconPaint)

            // 右上角星：实心白，作为「AI」标记
            val sr = dp(4.6f).toFloat()
            val sx = cx + dp(7.4f).toFloat()
            val sy = cy - dp(7.4f).toFloat()
            val star = Path().apply {
                moveTo(sx, sy - sr)
                quadTo(sx + sr * 0.2f, sy - sr * 0.2f, sx + sr, sy)
                quadTo(sx + sr * 0.2f, sy + sr * 0.2f, sx, sy + sr)
                quadTo(sx - sr * 0.2f, sy + sr * 0.2f, sx - sr, sy)
                quadTo(sx - sr * 0.2f, sy - sr * 0.2f, sx, sy - sr)
                close()
            }
            canvas.drawPath(star, starPaint)
        }
    }
}
