package com.mz2az.scenetrip.routetab

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XMarkIcon
import com.mz2az.scenetrip.ui.rememberReduceMotion

/** 첫 안내 기록을 SharedPreferences 에 둔다. 열쇠는 iOS UserDefaults 와 같다. */
class PreferencesCartHintStorage(
    context: Context,
) : CartHintStorage {
    private val prefs = context.applicationContext.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    override fun seen(): Boolean = prefs.getInt(KEY, 0) != 0

    override fun markSeen() {
        prefs.edit().putInt(KEY, 1).apply()
    }

    private companion object {
        const val KEY = "scenetrip.manualCourseCartHintSeen"
    }
}

/**
 * 한 번 커지는 테두리 — iOS `RouteCartHintRing`. 2.5초 동안 1.0→1.12 배, 투명도 1→0.3 으로 번진다.
 * 동작 줄이기에서는 같은 시간 동안 고정 테두리만 보인다. 터치를 가로채지 않고 화면 낭독기에는 보이지 않는다.
 */
@Composable
fun RouteCartHintRing(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            progress.animateTo(
                1f,
                tween(RouteCartHint.DURATION_MILLIS.toInt(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)),
            )
        }
    }
    Box(
        modifier =
            modifier
                .clearAndSetSemantics {}
                .graphicsLayer {
                    val scale = 1f + 0.12f * progress.value
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.7f * progress.value
                }.border(3.dp, IOS.accent, RoundedCornerShape(10.dp)),
    )
}

/**
 * 링 아래 안내 문구 — iOS `cartHintNotice`: 12pt 문구 + 44dp 닫기 버튼, 옅은 강조색 바탕, 가로 16 · 아래 8.
 * 낭독은 [RouteCartHint.present]의 `announce`가 한 번만 한다.
 */
@Composable
fun RouteCartHintNotice(
    text: String,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(IOS.systemBackground)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.accent.copy(alpha = 0.1f))
                .padding(start = 12.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = IOS.label,
            modifier = Modifier.weight(1f),
        )
        val closeLabel = tr("닫기")
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(44.dp)
                    .semantics { contentDescription = closeLabel }
                    .clickable(onClick = onDismiss),
        ) {
            XMarkIcon(IOS.label, Modifier.size(12.dp))
        }
    }
}
