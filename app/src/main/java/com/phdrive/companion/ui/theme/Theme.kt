package com.phdrive.companion.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    secondary = Amber500,
    tertiary = Green500,
    background = Navy900,
    surface = Navy800,
    surfaceVariant = Navy700,
    onPrimary = Navy900,
    onSecondary = Navy900,
    onBackground = Slate100,
    onSurface = Slate100,
    onSurfaceVariant = Slate300,
    error = Red500
)

private val LightColorScheme = lightColorScheme(
    primary = Cyan500,
    secondary = Amber500,
    tertiary = Green500,
    background = Slate50,
    surface = Slate100,
    surfaceVariant = Slate200,
    onPrimary = Slate50,
    onSecondary = Navy900,
    onBackground = Navy900,
    onSurface = Navy900,
    onSurfaceVariant = Navy700,
    error = Red500
)

@Composable
fun PhDriveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
