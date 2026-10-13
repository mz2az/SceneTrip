package com.mz2az.scenetrip.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mz2az.scenetrip.data.AppThemeStore

/** 테마 변경으로 화면을 새로 만들지 않아 열린 시트와 편집 상태를 유지한다. */
@Composable
fun SceneTripTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val dark = AppThemeStore.getInstance(context).mode.isDark(isSystemInDarkTheme())
    SideEffect {
        IOS.updateTheme(dark)
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        typography = iosTypography(),
        colorScheme =
            colors.copy(
                primary = IOS.accent,
                onPrimary = Color.White,
                background = IOS.systemBackground,
                onBackground = IOS.label,
                surface = IOS.systemBackground,
                onSurface = IOS.label,
                error = IOS.systemRed,
            ),
        content = content,
    )
}
