package io.github.fgozxy.await.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 日程卡片可选的主题色板 */
val EventColors = listOf(
    Color(0xFF2196F3), // 蓝
    Color(0xFF4CAF50), // 绿
    Color(0xFFFF9800), // 橙
    Color(0xFFE91E63), // 粉红
    Color(0xFF9C27B0), // 紫
    Color(0xFF00BCD4)  // 青
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCEE5FF),
    secondary = Color(0xFF526069),
    surface = Color(0xFFFCFCFF),
    background = Color(0xFFF7F9FE)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF97CBFF),
    onPrimary = Color(0xFF003355),
    primaryContainer = Color(0xFF004A78),
    secondary = Color(0xFFBAC8D3),
    surface = Color(0xFF1A1C1E),
    background = Color(0xFF111318)
)

@Composable
fun AwaitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
