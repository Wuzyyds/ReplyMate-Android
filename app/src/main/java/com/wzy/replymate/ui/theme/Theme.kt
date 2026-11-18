package com.wzy.replymate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ═══════════════════════════════════════════════════════════
 *  设计令牌 · Design Tokens
 *  作用等同 SCSS 的 _variables.scss：
 *  组件只引用语义名，换主题只改 Brand 与 Tone 两组。
 * ═══════════════════════════════════════════════════════════ */

/** 品牌三色：与 App 图标（ic_launcher_background）完全同源，任何情况下不要改 */
object Brand {
    val violet = Color(0xFF9C27D9)
    val rose = Color(0xFFE23C7E)
    val amber = Color(0xFFF7A21C)

    /** 品牌渐变：主卡、启动页、图标、插画统一用它 */
    val gradient = listOf(violet, rose, amber)
}

/** 主色调 · 方案 D「沉玫瑰」 */
object Tone {
    val primary = Color(0xFFB03A72)
    val primaryPressed = Color(0xFF96305F)
    val primarySoft = Color(0xFF331A26)
    val primaryDisabled = Color(0xFF3A2A33)
    val onPrimary = Color(0xFFFFFFFF)
}

/** 中性色 · 严格对齐设计稿 qingliao-home-dark：卡片是半透明白叠在近黑底上 */
object Neutral {
    val ink = Color(0xFFF2EEF7)
    val sub = Color(0x8FF2EEF7)      // 56%
    val muted = Color(0x75F2EEF7)    // 46%
    val faint = Color(0x42F2EEF7)    // 26%
    val line = Color(0x12FFFFFF)     // 7.2%
    val bg = Color(0xFF0E0A13)
    val surface = Color(0x0AFFFFFF)   // 3.8%
    val surfaceAlt = Color(0x07FFFFFF) // 2.6%
}

/** 语义色：只表状态，不做装饰 */
object Semantic {
    val success = Color(0xFF2C8F5E)
    val successSoft = Color(0xFF14281F)
    val warning = Color(0xFFF07A2B)
    val warningSoft = Color(0xFF2E2418)
    val warningText = Color(0xFFFFC078)
    val error = Color(0xFFFF6B5E)
    val errorSoft = Color(0xFF331A1A)
}

/* ── 兼容别名：现有组件引用的名字保持不变，值统一走上面三组 ── */

val Ink = Neutral.ink
val Sub = Neutral.sub
val Muted = Neutral.muted
val Faint = Neutral.faint
val Line = Neutral.line
val Bg = Neutral.bg
val CardBg = Neutral.surface

val Primary = Tone.primary
val PrimaryPressed = Tone.primaryPressed
val PrimaryDisabled = Tone.primaryDisabled
val PrimarySoft = Tone.primarySoft

val Amber = Semantic.warning
val AmberSoft = Semantic.warningSoft
val AmberText = Semantic.warningText
val Green = Semantic.success
val GreenSoft = Semantic.successSoft
val Red = Semantic.error
val RedSoft = Semantic.errorSoft

val BrandGradient = Brand.gradient

private val Colors = darkColorScheme(
    primary = Tone.primary,
    onPrimary = Tone.onPrimary,
    primaryContainer = Color(0xFF3A1A2A),
    onPrimaryContainer = Color(0xFFFFD9E7),
    secondary = Brand.amber,
    onSecondary = Color(0xFF1A1208),
    background = Neutral.bg,
    onBackground = Neutral.ink,
    surface = Neutral.surface,
    onSurface = Neutral.ink,
    surfaceVariant = Neutral.surfaceAlt,
    onSurfaceVariant = Neutral.sub,
    outline = Neutral.line,
    outlineVariant = Color(0xFF221F27),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF2A0A06)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** 统一投影：替代描边框。投影带一点玫红，和暖色主题同温 */
fun Modifier.softShadow(radius: Dp = 10.dp, shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    this.shadow(
        elevation = radius,
        shape = shape,
        clip = false,
        ambientColor = Color(0x0D3B1023),
        spotColor = Color(0x143B1023)
    )

@Composable
fun ReplyMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
}
