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
