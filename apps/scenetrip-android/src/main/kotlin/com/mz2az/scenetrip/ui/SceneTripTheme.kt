package com.mz2az.scenetrip.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 앱은 라이트로 고정이다 (MZ2AZ-419 — iOS `.preferredColorScheme(.light)`). 시스템이 다크여도,
 * 예전에 다크를 골라 둔 기기여도 밝은 화면을 그린다. 상태바·내비게이션 바 글자도 밝은 바탕용(어두운 글자)이다.
 */
@Composable
fun SceneTripTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        typography = iosTypography(),
        colorScheme =
            lightColorScheme().copy(
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
