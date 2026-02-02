package com.anotepad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B2B2B),
    onPrimary = Color.White,
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color.White,
    surface = Color(0xFFF5F4F2),
    onSurface = Color(0xFF1F1F1F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0DFDC),
    onPrimary = Color(0xFF1B1B1B),
    secondary = Color(0xFFBEBDBA),
    onSecondary = Color(0xFF1B1B1B),
    surface = Color(0xFF171717),
    onSurface = Color(0xFFF1F1F1),
)

@Composable
fun ANotepadTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    SideEffect {
        val window = WindowCompat.getInsetsController(view.context.findActivity().window, view)
        val useDarkIcons = colors.surface.luminance() > 0.5f
        window.isAppearanceLightStatusBars = useDarkIcons
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

@Composable
fun ANotepadTheme(content: @Composable () -> Unit) {
    androidx.compose.foundation.isSystemInDarkTheme().let { isDark ->
        ANotepadTheme(darkTheme = isDark, content = content)
    }
}

private fun android.content.Context.findActivity(): androidx.activity.ComponentActivity {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is androidx.activity.ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    throw IllegalStateException("Activity not found")
}
