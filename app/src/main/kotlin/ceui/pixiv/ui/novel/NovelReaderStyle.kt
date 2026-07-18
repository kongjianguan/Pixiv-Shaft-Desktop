package ceui.pixiv.ui.novel

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class NovelReaderStyle(
    val fontSizeSp: Int,
    val lineSpacing: Float,
    val paragraphSpacing: Dp,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val divider: Color,
    val maxContentWidth: Dp = 720.dp,
)

enum class NovelReaderThemePreset(
    val id: String,
    val label: String,
) {
    SYSTEM("system", "跟随系统"),
    PAPER("paper", "纸张"),
    NIGHT("night", "夜间"),
    SAGE("sage", "护眼"),
    ;

    companion object {
        fun fromId(id: String): NovelReaderThemePreset =
            entries.firstOrNull { it.id == id } ?: PAPER
    }
}

@Composable
fun rememberNovelReaderStyle(
    themeId: String,
    fontSizeSp: Int,
    lineSpacing: Float,
    paragraphSpacingDp: Int,
): NovelReaderStyle {
    val colors = MaterialTheme.colorScheme
    val theme = NovelReaderThemePreset.fromId(themeId)
    val (background, text, secondary, accent, divider) = when (theme) {
        NovelReaderThemePreset.SYSTEM -> listOf(
            colors.background,
            colors.onBackground,
            colors.onSurfaceVariant,
            colors.primary,
            colors.outlineVariant,
        )
        NovelReaderThemePreset.PAPER -> listOf(
            Color(0xFFF7F0E2),
            Color(0xFF43382C),
            Color(0xFF887766),
            Color(0xFF8A5A2B),
            Color(0xFFD9CDBA),
        )
        NovelReaderThemePreset.NIGHT -> listOf(
            Color(0xFF202124),
            Color(0xFFE5E0D8),
            Color(0xFFA9A29A),
            Color(0xFF90CAF9),
            Color(0xFF3A3B40),
        )
        NovelReaderThemePreset.SAGE -> listOf(
            Color(0xFFEAF2E6),
            Color(0xFF304237),
            Color(0xFF66806C),
            Color(0xFF39724A),
            Color(0xFFC5D8C6),
        )
    }
    return NovelReaderStyle(
        fontSizeSp = fontSizeSp,
        lineSpacing = lineSpacing,
        paragraphSpacing = paragraphSpacingDp.dp,
        background = background,
        text = text,
        secondaryText = secondary,
        accent = accent,
        divider = divider,
    )
}
