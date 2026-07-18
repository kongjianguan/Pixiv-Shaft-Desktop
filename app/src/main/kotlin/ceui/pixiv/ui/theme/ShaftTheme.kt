package ceui.pixiv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ceui.pixiv.di.AppContainer

private val DarkOnSurface = Color(0xFFE6E1E5)

enum class ShaftThemeMode(
    val storageValue: String,
    val label: String,
) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(value: String): ShaftThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

enum class ShaftThemePreset(
    val index: Int,
    val label: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
) {
    SHI_YIN_PURPLE(0, "矢尹紫", Color(0xFF686BDD), Color(0xFF4749A0)),
    CLASSIC_BLUE(1, "经典蓝", Color(0xFF56BAEC), Color(0xFF4BA2CE)),
    OFFICIAL_BLUE(2, "官方蓝", Color(0xFF008BF3), Color(0xFF0277CF)),
    SCALLION_GREEN(3, "浅葱绿", Color(0xFF03D0BF), Color(0xFF04A598)),
    SUMMER_YELLOW(4, "盛夏黄", Color(0xFFFEE65E), Color(0xFFDBC650)),
    PEACH_PINK(5, "樱桃粉", Color(0xFFFE83A2), Color(0xFFD67089)),
    ACTIVE_RED(6, "元气红", Color(0xFFF44336), Color(0xFFD32F2F)),
    CLASSIC_PURPLE(7, "基佬紫", Color(0xFF673AB7), Color(0xFF512DA8)),
    CLASSIC_GREEN(8, "老实绿", Color(0xFF4CAF50), Color(0xFF689F38)),
    GIRL_PINK(9, "少女粉", Color(0xFFE91E63), Color(0xFFC2185B));

    fun colorScheme(darkTheme: Boolean): ColorScheme {
        val primary = if (darkTheme) darkPrimary else lightPrimary
        val onPrimary = primary.contentColor()
        val secondary = if (darkTheme) {
            primary.mix(Color.White, 0.72f)
        } else {
            primary.mix(Color(0xFF4A4458), 0.62f)
        }
        val tertiary = if (darkTheme) {
            primary.mix(Color.White, 0.58f)
        } else {
            primary.mix(Color(0xFF7D5260), 0.55f)
        }
        val primaryContainer = if (darkTheme) {
            primary.mix(Color.Black, 0.52f)
        } else {
            primary.mix(Color.White, 0.16f)
        }
        val secondaryContainer = if (darkTheme) {
            secondary.mix(Color.Black, 0.48f)
        } else {
            secondary.mix(Color.White, 0.16f)
        }
        val tertiaryContainer = if (darkTheme) {
            tertiary.mix(Color.Black, 0.48f)
        } else {
            tertiary.mix(Color.White, 0.16f)
        }

        val scheme = if (darkTheme) darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryContainer.contentColor(),
            secondary = secondary,
            onSecondary = secondary.contentColor(),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = secondaryContainer.contentColor(),
            tertiary = tertiary,
            onTertiary = tertiary.contentColor(),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = tertiaryContainer.contentColor(),
        ) else lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryContainer.contentColor(),
            secondary = secondary,
            onSecondary = secondary.contentColor(),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = secondaryContainer.contentColor(),
            tertiary = tertiary,
            onTertiary = tertiary.contentColor(),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = tertiaryContainer.contentColor(),
        )
        return scheme.copy(onSurface = if (darkTheme) DarkOnSurface else scheme.onSurface)
    }

    companion object {
        fun fromIndex(index: Int): ShaftThemePreset =
            entries.firstOrNull { it.index == index } ?: SHI_YIN_PURPLE
    }
}

private fun Color.contentColor(): Color =
    if (luminance() > 0.52f) Color(0xFF1D1B20) else Color.White

private fun Color.mix(other: Color, thisWeight: Float): Color {
    val weight = thisWeight.coerceIn(0f, 1f)
    return Color(
        red = red * weight + other.red * (1f - weight),
        green = green * weight + other.green * (1f - weight),
        blue = blue * weight + other.blue * (1f - weight),
        alpha = alpha * weight + other.alpha * (1f - weight),
    )
}

@Composable
fun ShaftTheme(
    content: @Composable () -> Unit,
) {
    val themeColorIndex by AppContainer.settingsStore.themeColorIndexFlow.collectAsState()
    val themeModeValue by AppContainer.settingsStore.themeModeFlow.collectAsState()
    val themeMode = ShaftThemeMode.fromStorage(themeModeValue)
    val darkTheme = when (themeMode) {
        ShaftThemeMode.SYSTEM -> isSystemInDarkTheme()
        ShaftThemeMode.LIGHT -> false
        ShaftThemeMode.DARK -> true
    }
    val colorScheme = ShaftThemePreset.fromIndex(themeColorIndex).colorScheme(darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
