package com.mz2az.scenetrip.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 시스템이 애니메이션을 껐는가(설정의 「애니메이션 제거」 = 애니메이터 배율 0). iOS `accessibilityReduceMotion`에
 * 해당한다. 켜져 있으면 움직임 없이 같은 시간 동안 고정된 표시만 둔다.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
