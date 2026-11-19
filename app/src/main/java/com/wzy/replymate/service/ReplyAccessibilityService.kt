package com.wzy.replymate.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.wzy.replymate.core.Ocr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.abs
import kotlin.coroutines.resume

/**
 * 读屏 + 回填。按技术方案 3.1 / 3.2：
 * - 不滑动、不滚屏，只读当前屏幕可见节点，上限 20 条
 * - 输入框锚点：isEditable 且可见，取面积最大者
 * - 从锚点向上回溯 2~4 层找消息容器
 * - 角色判定用水平位置（右侧 1/3 = 我）
 * - 回填 L1 ACTION_SET_TEXT → L2 剪贴板 + ACTION_PASTE
 */
class ReplyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ReplyAccessibilityService? = null
            private set

        /** 收集时排除的 UI 词汇，避免把「发送/表情」当成聊天气泡 */
        val CHROME_WORDS = setOf(
            "发送", "返回", "更多", "表情", "相册", "拍摄", "语音", "视频通话",
            "取消", "确定", "保存", "朋友圈", "通讯录", "发现", "我", "微信", "搜索"
        )

        fun isConnected(): Boolean = instance != null

        /** 最近一次读屏命中的聊天 App 与时间：App 内「回填」靠它判断能不能退回原聊天 */
        @Volatile
        var lastChatPkg: String? = null

        @Volatile
        var lastReadAt: Long = 0L

        /** 排队中的一次回填：用户点「回填」后先退回答器，窗口切回来再写入 */
        data class PendingFill(val text: String, val pkg: String, val until: Long)

        @Volatile
        var pendingFill: PendingFill? = null

        /**
         * App 内点「回填」时调用。10 分钟内有读屏记录才认为能退回原聊天，
         * 否则返回 false，由调用方走复制兜底。
         */
        fun requestFill(text: String): Boolean {
            val pkg = lastChatPkg ?: return false
            val acc = instance ?: return false
            if (SystemClock.elapsedRealtime() - lastReadAt > 10 * 60_000) return false
            pendingFill = PendingFill(text, pkg, SystemClock.elapsedRealtime() + 15_000)
            // 有些 ROM 退回答器时不一定发窗口事件，这里主动补两次
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ acc.tryFillPending() }, 800)
            h.postDelayed({ acc.tryFillPending() }, 1600)
            return true
        }

        fun isEnabled(ctx: Context): Boolean {
            // 直接读系统设置项：比 getEnabledAccessibilityServiceList 更贴近用户实际勾选状态
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val target = ctx.packageName + "/" + ReplyAccessibilityService::class.java.name
            return enabled.contains(target, ignoreCase = true)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 读屏单飞：同一时刻只扫一棵无障碍树，多个入口复用同一个结果 */
    private val readLock = Any()
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inFlightRead: Deferred<Snapshot?>? = null
    private var lastReadJob: Deferred<Snapshot?>? = null
    private var lastReadDoneAt = 0L

    /** 刚读完的这段时间内复用同一份结果：点球读屏后立刻点「聊天」不会再扫一遍 */
    private val readReuseWindowMs = 400L

    data class Line(val text: String, val fromMe: Boolean, val top: Int)

    data class Snapshot(
        val pkg: String,
        val lines: List<Line>,
        val scanned: Int,
        val hasInput: Boolean = false,
        /** 顶部标题（联系人/群名），用来给聊天记忆自动命名；读不到为空 */
        val title: String = "",
        /** 底部 45% 区域有输入框 = 更像聊天页，而不是列表页/搜索页 */
        val chatInput: Boolean = false
    ) {
        /** 拼成给模型的对话文本 */
        fun asPromptText(): String = lines.joinToString("\n") { l ->
            (if (l.fromMe) "我：" else "对方：") + l.text
        }
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                // 飞书/网页版聊天等 WebView 内容，需要这个标志才能读到 DOM 文字
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 平时不监听；只有「回填」排队时，等聊天窗口切回来再写入
        val pf = pendingFill ?: return
        if (SystemClock.elapsedRealtime() > pf.until) {
            pendingFill = null
            return
        }
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (ev.packageName?.toString() != pf.pkg) return
        main.postDelayed({ tryFillPending() }, 450)
    }

    /** 待回填任务：聊天窗口真的到前台了才写，避免写进搜索框或别的 App */
    fun tryFillPending() {
        val pf = pendingFill ?: return
        if (SystemClock.elapsedRealtime() > pf.until) {
            pendingFill = null
            return
        }
        if (rootInActiveWindow?.packageName?.toString() != pf.pkg) return
        pendingFill = null
        val ok = fillInput(pf.text, pf.pkg)
        if (ok) {
            toast("已填入，检查一下再发")
        } else {
            copyToClipboard(pf.text)
            toast("已复制，请长按输入框粘贴")
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        readScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 单飞读屏：已有读屏在跑时直接复用，不再并发扫第二遍。
     * 悬浮面板、轻聊、通知入口都走这里，避免两套 UI 同时读屏抢 IPC。
     */
    fun readScreenShared(): Deferred<Snapshot?> {
        synchronized(readLock) {
            inFlightRead?.let {
                if (it.isActive) {
                    Log.d("RM", "readScreenShared: 复用进行中的读屏")
                    return it
                }
            }
            // 上一轮刚结束（点球弹面板 → 点「聊天」）：窗口没变就复用，省掉第二次整树遍历
            lastReadJob?.let { done ->
                if (!done.isActive && SystemClock.elapsedRealtime() - lastReadDoneAt <= readReuseWindowMs) {
                    Log.d("RM", "readScreenShared: 复用 400ms 内结果")
                    return done
                }
            }
            val job = readScope.async {
                runCatching { readScreen() }
                    .onFailure { Log.e("RM", "readScreen 异常", it) }
                    .getOrNull()
            }
            inFlightRead = job
            lastReadJob = job
            job.invokeOnCompletion {
                synchronized(readLock) {
                    if (inFlightRead === job) inFlightRead = null
                    if (lastReadJob === job) lastReadDoneAt = SystemClock.elapsedRealtime()
                }
            }
            return job
        }
    }

    /** 读当前屏幕的聊天上下文；读不到返回 null。 */
    /**
     * 读屏。不再依赖「先找到输入框」——微信/QQ/飞书/BOSS 新版都可能找不到可编辑节点，
     * 一旦找不到就整条链路返回 null。现在改成：
     * 1) 把 rootInActiveWindow 和 windows 里所有根节点都试一遍；
     * 2) 输入框只用来确定「消息区的下边界」，找不到就用屏幕高度兜底；
     * 3) 在消息区内收集所有像气泡的文字节点，按 y 排序取最后 20 条。
     */
    fun readScreen(): Snapshot? {
        val startedAt = SystemClock.elapsedRealtime()
        val (w, h) = screenSize()
        val homePkg = homePackage()

        // 同一个窗口会同时出现在 rootInActiveWindow 和 windows 里，用 HashSet 去重，
        // 避免把同一棵树完整遍历两遍（节点多时这是最大的一笔浪费）。
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        val seen = HashSet<AccessibilityNodeInfo>(4)
        runCatching { rootInActiveWindow }.getOrNull()?.let {
            if (seen.add(it)) roots.add(it)
        }
        runCatching { windows }.getOrNull()?.forEach { win ->
            runCatching { win.root }.getOrNull()?.let { if (seen.add(it)) roots.add(it) }
        }
        if (roots.isEmpty()) {
            Log.d("RM", "readScreen: 没有任何窗口根节点")
            return null
        }

        var best: Snapshot? = null
        var bestScore = Int.MIN_VALUE
        for ((index, root) in roots.withIndex()) {
            val pkg = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
            // 系统栏、桌面、自家悬浮窗不可能是目标聊天页：先按包名跳过，省掉整棵树遍历。
            // pkg 为空时不能跳过，否则桌面包名解析失败会一条都读不到
            if (pkg.isNotEmpty() && (pkg == "com.android.systemui" || pkg == homePkg || pkg == packageName)) continue
            val snap = readFromRoot(root, w, h) ?: continue
            val score = (if (snap.chatInput) 2000 else if (snap.hasInput) 1000 else 0) + snap.lines.size
            if (score > bestScore) {
                bestScore = score
                best = snap
            }
            // 活跃窗口（index=0）已经是带输入框的聊天页，直接采用，不再扫输入法/悬浮窗
            if (index == 0 && snap.chatInput && snap.lines.isNotEmpty()) {
                Log.d("RM", "readScreen: 活跃窗口已是聊天页，提前返回")
                break
            }
        }
        val result = best
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (result == null) {
            Log.d("RM", "readScreen: ${roots.size} 个窗口都没收集到气泡，耗时=${cost}ms")
        } else {
            Log.d("RM", "readScreen: 采用 pkg=${result.pkg} 条数=${result.lines.size} 耗时=${cost}ms")
            lastChatPkg = result.pkg
            lastReadAt = SystemClock.elapsedRealtime()
        }
        return result
    }

    /** 桌面包名解析一次就够，别每次读屏都打一次 PackageManager */
    @Volatile
    private var homePkgCache: String? = null

    private fun homePackage(): String {
        homePkgCache?.let { return it }
        val pkg = runCatching {
            packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME),
                0
            )?.activityInfo?.packageName.orEmpty()
        }.getOrDefault("")
        homePkgCache = pkg
        return pkg
    }

    /** 服务里的 displayMetrics 在部分机型上不含状态栏/刘海，统一取更大的真实屏幕尺寸 */
    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val b = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .currentWindowMetrics.bounds
                if (b.width() > w) w = b.width()
                if (b.height() > h) h = b.height()
            }
        }
        return w to h
    }

    private fun readFromRoot(root: AccessibilityNodeInfo, w: Int, h: Int): Snapshot? {
        val pkg = root.packageName?.toString().orEmpty()
        val input = findInput(root, h)
        val inputTop = input?.let {
            val r = Rect(); it.getBoundsInScreen(r); r.top
        } ?: (h - (90f * resources.displayMetrics.density).toInt())
        // 底部输入框才算聊天页；列表页的顶部搜索框不能用来命名记忆
        val bottomInput = input != null && inputTop > h * 0.55f

        val strict = ArrayList<Line>(24)
        // 宽松兜底在同一次遍历里一起收集，主过滤全空时直接用，避免整棵树再走一遍
        val loose = ArrayList<Line>(24)
        // 顶部标题栏候选：给聊天记忆自动命名（微信/QQ/飞书都在 3%~16% 高度）
        val titles = ArrayList<Pair<Int, String>>(2)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var guard = 0
        while (stack.isNotEmpty() && guard < 2500) {
            guard++
            val n = stack.removeLast()
            n.getBoundsInScreen(r)

            val raw = runCatching { n.text?.toString() ?: n.contentDescription?.toString() }
                .getOrNull()?.trim().orEmpty()
            // 长消息以前直接被 200 字上限丢掉，导致「最后一句」读不到；改成截断保留
            val txt = if (raw.length > 500) raw.take(500) else raw
            if (txt.isNotEmpty()) {
                // 可见/可编辑/点击这些每次都是 IPC，缓存成局部变量，别重复取
                val visible = n.isVisibleToUser
                val editable = n.isEditable
                if (visible && !editable) {
                    // 严格带 10%~输入框；宽松带 18%~输入框，只用于兜底
                    val inBand = r.top >= h * 0.10f && r.bottom <= inputTop - 4
                    val looseBand = r.top >= h * 0.18f && r.bottom <= inputTop - 4 && r.width() > 8
                    if (inBand || looseBand) {
                        val chrome = isChrome(txt)
                        if (!chrome && !isChatStatus(txt)) {
                            // 严格结果出现后就不再收兜底副本，也不再为兜底跑 isStatusText
                            val looseOk = looseBand && strict.isEmpty() && !isStatusText(txt)
                            // 聊天页里不只有气泡：日期、已读、职位卡片、整块点击卡片都要过滤
                            val strictOk = inBand &&
                                r.width() > 8 && r.width() <= w * 0.92f &&
                                r.height() in 1..(h * 0.30f).toInt() &&
                                !(r.width() > w * 0.86f && r.height() < h * 0.05f) &&
                                !(abs(r.centerX() - w / 2f) < w * 0.11f && txt.length <= 24) &&
                                // 微信的消息气泡自带 isClickable（长按菜单），阈值得给到 18%：
                                // 用 10% 时稍长一点的消息会被当成「大块卡片」整条丢掉，QQ 不带这个属性所以没事
                                !(n.isClickable && r.height() > h * 0.18f && txt.length <= 80)
                            if (strictOk || looseOk) {
                                // 父子节点同文案时只收子节点；只对要收的候选查一次
                                if (!hasSameTextChild(n, txt)) {
                                    // 长气泡的中心点会落在屏幕中间，不能只看 centerX
                                    val fromMe = r.right > w * 0.84f && r.centerX() > w * 0.52f
                                    if (strictOk) strict.add(Line(txt, fromMe, r.top))
                                    if (looseOk) loose.add(Line(txt, fromMe, r.top))
                                }
                            }
                        }
                    }
                    // 标题栏在部分机型上会因为大状态栏/刘海下移，放宽到 18%
                    if (bottomInput && txt.length in 2..24 && r.width() > 8 &&
                        r.top >= h * 0.03f && r.bottom <= h * 0.18f &&
                        r.centerX() in (w * 0.10f).toInt()..(w * 0.80f).toInt() &&
                        // 标题以文字/数字/中文括号开头；微信返回键+未读角标会被读成「< 2」
                        startsLikeName(txt) && !isChrome(txt) && !isStatusText(txt)
                    ) {
                        titles.add(r.top to txt)
                    }
                }
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { stack.addLast(it) }
            }
        }

        fun clean(list: List<Line>): List<Line> = list
            .distinctBy { it.top to it.text }
            .sortedBy { it.top }
            // 同一句话父容器和子 TextView 会各报一次，只保留最上面的一条
            .distinctBy { it.text }
            .takeLast(20)

        var cleaned = clean(strict)
        if (cleaned.isEmpty() && loose.isNotEmpty()) {
            cleaned = clean(loose)
            if (cleaned.isNotEmpty()) Log.d("RM", "readFromRoot: 宽松兜底命中 ${cleaned.size} 条")
        }
        val title = titles.distinctBy { it.second }.minByOrNull { it.first }?.second.orEmpty()
        Log.d("RM", "readFromRoot: pkg=$pkg input=${input != null} bottom=$bottomInput raw=${strict.size} cleaned=${cleaned.size} title=$title")
        if (cleaned.isNotEmpty()) {
            Log.d("RM", "lines=" + cleaned.joinToString(" | ") {
                (if (it.fromMe) "我:" else "他:") + it.text.replace("\n", " ").take(24)
            })
        }
        if (cleaned.isEmpty()) return null
        return Snapshot(pkg, cleaned, cleaned.size, input != null, title, bottomInput)
    }

    /** 联系人/群名可能以中文括号开头，比如「【公司群】」；但「< 2」这种返回角标不算 */
    private fun startsLikeName(t: String): Boolean {
        val c = t.trim().firstOrNull() ?: return false
        return c.isLetterOrDigit() || c in "【「『《（(〈"
    }

    // 这些正则在读屏时会被调用成百上千次，必须预编译；否则每个节点都要新建 Regex
    private val RE_HHMM = Regex("^\\d{1,2}:\\d{2}$")
    private val RE_HHMM_AMPM = Regex("^\\d{1,2}:\\d{2}\\s*(AM|PM)$", RegexOption.IGNORE_CASE)
    private val RE_PERCENT = Regex("^[\\d.]+\\s*%$")
    private val RE_SPEED = Regex("^[\\d.]+\\s*[KMG]?/s$", RegexOption.IGNORE_CASE)
    private val RE_CN_DAY = Regex("^(昨天|今天|前天|刚刚|星期[一二三四五六日]|周[一二三四五六日])(\\s*\\d{0,2}:?\\d{0,2})?$")
    private val RE_CN_HHMM = Regex("^(上午|下午|凌晨|晚上|中午)\\s*\\d{1,2}:\\d{2}$")
    private val RE_CN_MD = Regex("^\\d{1,2}月\\d{1,2}日.*")
    private val RE_CN_YMD = Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日.*")
    private val RE_EN_DAY = Regex(
        "^(today|yesterday|just now|monday|tuesday|wednesday|thursday|friday|saturday|sunday)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_NUM_DATE = Regex("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}.*$")
    private val SIGNAL_TEXTS = setOf("5G", "4G", "3G", "5G+", "4G+", "HD", "VoLTE", "WiFi", "WLAN", "LTE", "NR")
    private val NON_CHAT_TEXTS = setOf("呼叫", "拨号", "视频通话", "语音通话", "信息", "菜单", "联系人")

    /** 状态栏时间/电量/信号，不是聊天标题 */
    private fun isStatusText(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        if (s.length <= 8) {
            if (RE_HHMM.matches(s)) return true
            if (RE_HHMM_AMPM.matches(s)) return true
            if (RE_PERCENT.matches(s)) return true
            if (RE_SPEED.matches(s)) return true
            if (s.equals("AM", true) || s.equals("PM", true)) return true
            if (SIGNAL_TEXTS.contains(s)) return true
            if (NON_CHAT_TEXTS.contains(s)) return true
        }
        if (s.contains("更多") || s.contains("搜索")) return true
        return false
    }

    /** 聊天页里的日期、已读、撤回、卡片按钮等，不是聊天内容 */
    private fun isChatStatus(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return true
        if (s.length <= 6 && CHAT_STATUS_WORDS.contains(s)) return true
        if (s.length <= 18 && EN_CHAT_STATUS.contains(s.lowercase())) return true
        if (s.startsWith("以下为") || s.contains("天内的聊天记录") || s.contains("以上是")) return true
        if (s.startsWith("对方已") && s.length <= 12) return true
        if (s.length <= 30) {
            // 中文日期/时间/星期：昨天 10:25、上午 9:03、周一、11月16日 周一 13:10
            if (RE_CN_DAY.matches(s)) return true
            if (RE_CN_HHMM.matches(s)) return true
            if (s.length <= 16 && RE_CN_MD.matches(s)) return true
            if (s.length <= 20 && RE_CN_YMD.matches(s)) return true
            // 英文日期/时间：10:25 AM、Yesterday、Monday、11/16/2025
            if (RE_HHMM_AMPM.matches(s)) return true
            if (RE_EN_DAY.matches(s)) return true
            if (s.length <= 20 && RE_NUM_DATE.matches(s)) return true
        }
        return false
    }

    /** 英文聊天里常见的日期、送达状态、媒体占位文字 */
    private val EN_CHAT_STATUS = setOf(
        "online", "typing", "typing…", "typing...", "last seen", "active now", "away",
        "sent", "sending", "delivered", "read", "failed", "edited", "forwarded",
        "you", "photo", "video", "voice message", "sticker", "gif", "file", "location",
        "contact card", "missed voice call", "missed video call", "message deleted",
        "this message was deleted", "end-to-end encrypted"
    )

    /** 父容器和子 TextView 文本相同，只收子节点，避免一条消息读两遍 */
    private fun hasSameTextChild(n: AccessibilityNodeInfo, text: String): Boolean {
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            val ct = (c.text?.toString() ?: c.contentDescription?.toString())?.trim()
            if (ct == text) return true
        }
        return false
    }

    private val CHAT_STATUS_WORDS = setOf(
        "已读", "未读", "送达", "已送达", "发送中", "发送失败", "撤回", "已撤回", "对方正在输入",
        "查看更多消息", "以上是历史消息", "以下为历史消息", "收藏", "复制", "转发",
        "删除", "多选", "引用", "回复", "语音", "图片", "视频", "文件", "位置",
        "转账", "红包", "名片", "拍一拍", "通话时长", "已取消", "未接听",
        "你撤回了一条消息", "对方撤回了一条消息", "该消息已过期", "网络异常"
    )

    private fun isChrome(t: String): Boolean {
        val s = t.trim()
        if (s.length <= 4 && CHROME_WORDS.contains(s)) return true
        if (RE_HHMM.matches(s)) return true
        if (s.length <= 3 && (s == "发送" || s == "返回" || s == "更多")) return true
        return false
    }

    /**
     * 回填到当前输入框。L1 → L2，全失败返回 false（由调用方转剪贴板兜底）。
     * 悬浮面板自己也是窗口，rootInActiveWindow 不一定是聊天 App，
     * 所以这里把所有窗口都扫一遍，优先指定包名。
     */
    fun fillInput(text: String, expectedPkg: String? = null): Boolean {
        val found = findTargetWithInput(expectedPkg) ?: return false
        val input = found.second
        input.refresh()

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("reply", text))
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** 在所有窗口里找「带输入框」的根节点；指定包名优先，其次非自家 App */
    private fun findTargetWithInput(expectedPkg: String?): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        val (_, h) = screenSize()
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        val seen = HashSet<AccessibilityNodeInfo>(4)
        runCatching { rootInActiveWindow }.getOrNull()?.let { if (seen.add(it)) roots.add(it) }
        runCatching { windows }.getOrNull()?.forEach { w ->
            runCatching { w.root }.getOrNull()?.let { if (seen.add(it)) roots.add(it) }
        }
        return roots.mapNotNull { root ->
            val pkg = root.packageName?.toString().orEmpty()
            if (expectedPkg != null && pkg != expectedPkg) return@mapNotNull null
            val input = findInput(root, h) ?: return@mapNotNull null
            val score = when {
                expectedPkg != null && pkg == expectedPkg -> 3
                pkg != packageName -> 2
                else -> 1
            }
            Triple(root, input, score)
        }.maxByOrNull { it.third }?.let { it.first to it.second }
    }

    /** 通知栏还开着时读到的全是 systemui；借无障碍把通知栏收起来 */
    fun collapseShade(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * 截当前屏幕并本地 OCR。
     * 读屏读不到的分身应用、WebView、自定义控件，都走这条兜底；
     * 图片只在本机识别，不上传。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshotText(): String? {
        val bitmap = runCatching {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hb = screenshot.hardwareBuffer
                            val bmp = runCatching {
                                Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            hb.close()
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.d("RM", "takeScreenshot failed=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }
        }.getOrNull() ?: return null
        val text = runCatching { Ocr.recognizeBitmap(this, bitmap) }.getOrNull()
        bitmap.recycle()
        return text?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 通知栏是否真的展开着。状态栏本身也是 systemui，用高度过半来区分 */
    fun isShadeOpen(): Boolean = runCatching {
        val (_, screenH) = screenSize()
        windows.any { w ->
            if (w.root?.packageName?.toString() != "com.android.systemui") return@any false
            val r = Rect()
            w.getBoundsInScreen(r)
            r.height() > screenH * 0.5f
        }
    }.getOrDefault(false)

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("reply", text))
    }

    private fun findInput(root: AccessibilityNodeInfo, screenH: Int): AccessibilityNodeInfo? {
        val bottomLine = screenH * 0.42f
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        // 顶部搜索框也常带 ACTION_SET_TEXT，以前会被当成聊天输入框，
        // 导致消息区下边界被压到屏幕顶部、一条都读不到。底部优先，没有再用兜底。
        var fallback: AccessibilityNodeInfo? = null
        var fallbackArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var guard = 0
        while (stack.isNotEmpty() && guard < 800) {
            guard++
            val n = stack.removeLast()
            n.getBoundsInScreen(r)
            if (n.isVisibleToUser && r.height() > 0) {
                // 微信/QQ 新版输入框常常 isEditable=false，所以要几条信号一起判
                val editable = n.isEditable
                val cls = runCatching { n.className?.toString().orEmpty() }.getOrDefault("")
                var looksInput = editable || cls.contains("EditText", true)
                if (!looksInput) {
                    val desc = n.contentDescription?.toString()
                    looksInput = desc != null && desc.contains("输入")
                }
                // actionList 是最贵的一步（每个节点都会分配一串 action），
                // 只对屏幕下半部、且前面信号都不匹配的节点查。
                if (!looksInput && r.top >= bottomLine) {
                    looksInput = runCatching {
                        n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    }.getOrDefault(false)
                }
                if (looksInput) {
                    val area = r.width() * r.height()
                    if (area > 0) {
                        if (r.top >= bottomLine) {
                            // 明确的底部输入框直接收工，不再扫完整棵树
                            if (editable) return n
                            if (area > bestArea) {
                                bestArea = area
                                best = n
                            }
                        } else if (area > fallbackArea) {
                            fallbackArea = area
                            fallback = n
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { stack.addLast(it) }
            }
        }
        return best ?: fallback
    }



    private fun findMessageContainer(input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = input.parent
        var depth = 0
        while (p != null && depth < 4) {
            if (p.childCount >= 2) return p
            p = p.parent
            depth++
        }
        return input.parent?.parent
    }

    private fun collectLeaves(
        root: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        out: MutableList<Line>
    ) {
        val inputRect = Rect()
        input.getBoundsInScreen(inputRect)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 900) {
            guard++
            val n = stack.removeLast()

            val r = Rect()
            n.getBoundsInScreen(r)
            val insideInput = r.top >= inputRect.top - 4 && r.bottom <= inputRect.bottom + 4
            if (insideInput) continue
            if (n.isEditable) continue

            val text = (n.text?.toString() ?: n.contentDescription?.toString())?.trim().orEmpty()
            if (text.isNotEmpty() && text.length in 1..200 &&
                r.left >= 0 && r.right <= screenW &&
                r.top >= screenH * 0.12f && r.bottom <= screenH
            ) {
                // 气泡特征：靠左（对方）或靠右（我）。居中的标题、副标题、时间戳一律排除
                val looksLikeBubble =
                    r.left <= screenW * 0.14f || r.right >= screenW * 0.86f
                if (looksLikeBubble) {
                    val cx = r.centerX()
                    val fromMe = cx > screenW * 0.62f
                    out.add(Line(text, fromMe, r.top))
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }
}
