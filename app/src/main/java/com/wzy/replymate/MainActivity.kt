package com.wzy.replymate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.wzy.replymate.ui.theme.Bg
import com.wzy.replymate.ui.theme.CardBg
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wzy.replymate.domain.TaskType
import com.wzy.replymate.service.OverlayService
import com.wzy.replymate.ui.contacts.ContactsScreen
import com.wzy.replymate.ui.generate.GenerateScreen
import com.wzy.replymate.ui.history.HistoryScreen
import com.wzy.replymate.ui.home.HomeScreen
import com.wzy.replymate.ui.ocr.OcrScreen
import com.wzy.replymate.ui.settings.SettingsScreen
import com.wzy.replymate.ui.templates.TemplatesScreen
import com.wzy.replymate.ui.theme.Primary
import com.wzy.replymate.ui.theme.PrimarySoft
import com.wzy.replymate.ui.theme.ReplyMateTheme
import com.wzy.replymate.ui.theme.Sub

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏区域显示的是窗口底色，直接刷深色，比改主题/edge-to-edge 更可靠
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(0xFF0E0A13.toInt())
        )
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            ReplyMateTheme {
                AppRoot()
            }
        }
    }

    // 回到自家 App 就把悬浮球收起来，否则会压住首页内容
    override fun onResume() {
        super.onResume()
        OverlayService.setAppForeground(this, true)
    }

    override fun onPause() {
        super.onPause()
        OverlayService.setAppForeground(this, false)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "首页", Icons.Rounded.Home),
    Tab("history", "历史", Icons.Rounded.History),
    Tab("settings", "设置", Icons.Rounded.Settings)
)

@Composable
fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Android 13+ 通知权限：不申请的话前台服务的通知不显示，
    // 用户就看不到「点这里取词」这个入口
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 冷启动静默查一次版本（一天最多一次；地址没配就什么都不做）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val app = ctx.applicationContext as ReplyMateApp
        runCatching { com.wzy.replymate.data.UpdateChecker.checkIfDue(ctx, app.settings.updateUrl) }
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route == "home" || route == "history" || route == "settings"

    Scaffold(
        // 必须用主题深色底：NavHost 切换时任何缝隙露出来的都是它，
        // 之前用了浅灰，切 tab 会闪一下白
        containerColor = Bg,
        bottomBar = {
            if (showBar) {
                SmoothBottomBar(current = route) { target ->
                    nav.navigate(target) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { pad ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            // 三个 tab 用瞬时切换：默认淡入淡出约 700ms，两页交替时会露出底色/重影
            composable(
                "home",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                HomeScreen(
                    onOpenGenerate = { task -> nav.navigate("generate/${task.name}") },
                    onOpenOcr = { nav.navigate("ocr") }
                )
            }
            composable("ocr") {
                OcrScreen(
                    onBack = { nav.popBackStack() },
                    onGoGenerate = { nav.navigate("generate/${TaskType.REPLY.name}") }
                )
            }
            composable(
                "history",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                HistoryScreen(onGoHome = { nav.navigate("home") })
            }
            composable(
                "settings",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
            ) {
                SettingsScreen(
                    onOpenTemplates = { nav.navigate("templates") },
                    onOpenContacts = { nav.navigate("contacts") }
                )
            }
            composable("templates") {
                TemplatesScreen(onBack = { nav.popBackStack() })
            }
            composable("contacts") {
                ContactsScreen(onBack = { nav.popBackStack() })
            }
            composable("generate/{mode}") { backEntry ->
                val mode = backEntry.arguments?.getString("mode") ?: TaskType.REPLY.name
                GenerateScreen(
                    mode = mode,
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate("settings") }
                )
            }
        }
    }
}

@Composable
private fun SmoothBottomBar(current: String?, onSelect: (String) -> Unit) {
    // 底部栏：不透明深底（半透明色叠在白窗口上会变白）
    Surface(color = Color(0xF20C0911), shadowElevation = 12.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEach { tab ->
                val selected = current == tab.route

                val tint by animateColorAsState(
                    targetValue = if (selected) Primary else Sub,
                    animationSpec = tween(durationMillis = 220),
                    label = "tabTint"
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.88f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 620f),
                    label = "tabScale"
                )
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 56.dp else 36.dp,
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
                    label = "tabPill"
                )
                val pillColor by animateColorAsState(
                    targetValue = if (selected) PrimarySoft else Color.Transparent,
                    animationSpec = tween(durationMillis = 220),
                    label = "tabPillColor"
                )
                // 按下时整块轻微缩一下，点 tab 有手感
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = tween(durationMillis = 90),
                    label = "tabPress"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .scale(pressScale)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { if (!selected) onSelect(tab.route) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(pillWidth)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(pillColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier
                                .size(24.dp)
                                .scale(iconScale)
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        fontSize = 11.5.sp,
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
