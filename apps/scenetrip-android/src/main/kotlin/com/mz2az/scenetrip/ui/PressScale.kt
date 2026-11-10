package com.mz2az.scenetrip.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 시안 단추의 누름 느낌 — 누르는 동안 0.97배로 0.12초에 작아졌다 돌아온다
 * (MZ2AZ-547 M1, 웹 `proto.css` `.proto-press`). Android 기본 눌림 잔상(회색 사각형·물결)은 쓰지 않는다.
 * 시스템 「애니메이션 제거」 이면 애니메이터 배율 0 이라 Compose 가 바로 끝 값으로 간다.
 */
object PressScale {
    const val SCALE = 0.97f
    const val MILLIS = 120
}

/** [source] 가 눌린 동안 줄인다. 이미 `clickable`·`selectable` 에 자기 interactionSource 를 주는 곳에 붙인다. */
fun Modifier.pressScale(source: InteractionSource): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) PressScale.SCALE else 1f,
            tween(PressScale.MILLIS, easing = FastOutSlowInEasing),
            label = "pressScale",
        )
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

/** `clickable` + [pressScale] — 기본 눌림 잔상 없이 누르는 동안 0.97배. */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier =
    composed {
        val source = remember { MutableInteractionSource() }
        Modifier
            .pressScale(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
