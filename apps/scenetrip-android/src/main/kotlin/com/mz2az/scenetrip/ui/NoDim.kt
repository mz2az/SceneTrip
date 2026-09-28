package com.mz2az.scenetrip.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * `Dialog` 가 스스로 까는 어두운 막을 끈다.
 *
 * **`DialogProperties` 에는 이것을 끄는 값이 없다.** `decorFitsSystemWindows` 는
 * 인셋 처리이지 딤과 무관하다 — 그걸 끄고 다 됐다고 믿었다가 딤이 20% 가 아니라
 * 68% 로 나왔다(5 차 검사: 1 − 0.8 × 0.4 = 0.68, 실측 배율 0.3202 와 정확히 일치).
 *
 * 우리가 딤을 직접 칠하는 이유는 iOS 의 값(20%)을 그대로 쓰기 위해서다. 안드로이드
 * 기본은 60% 라 훨씬 어둡다.
 *
 * 창을 직접 만지는 것은 마지막 수단이지만, Compose 가 다른 길을 주지 않는다.
 */
@Composable
fun DisableDialogDim() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}

/**
 * 다이얼로그 창을 **상태바까지 화면 전체로** 편다. `decorFitsSystemWindows = false` 만으로는 창이
 * 상태바 높이만큼 내려간 채 전체 높이로 잡혀, 가운데 카드가 약 52dp 아래로 치우치고 상태바가 안
 * 덮였다(2026-09-28 실측). 창 크기·배치를 직접 준다.
 */
@Composable
fun FullScreenDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes =
            window.attributes.apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
                // API 30+ 는 창이 인셋만큼 스스로 비켜 선다 — 이것을 꺼야 상태바 아래에서 시작하지
                // 않는다(dumpsys: frame=[0,136]…, `FIT_INSETS_CONTROLLED` 없음).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }
}
