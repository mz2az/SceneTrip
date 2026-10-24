package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.NaverMap
import kotlinx.coroutines.delay

/** 레이더 파문의 모양. iOS `RouteTab/RadarPulse.swift` 와 같은 값이다. */
object RadarPulseShape {
    /** 파문 한 겹이 퍼지는 데 걸리는 시간(ms). */
    const val PERIOD_MS = 2200L

    /** 몇 겹이 동시에 퍼지는가. 하나면 사이가 비어 심장 박동으로 안 읽힌다. */
    const val WAVES = 3

    /** 가장 크게 퍼졌을 때의 지름(dp). */
    const val SPREAD_DP = 88f

    /** 작고 진하게 시작한다 — 0 에서 시작하면 첫 프레임이 점 하나라 「퍼진다」로 안 보인다. */
    const val START_SCALE = 0.18f

    /** 겹 하나의 진행(0..1). 겹마다 시작을 어긋나게 해 끊이지 않고 이어지게 한다. */
    fun progress(
        elapsedMs: Long,
        wave: Int,
    ): Float {
        val shifted = elapsedMs - PERIOD_MS / WAVES * wave
        return Math.floorMod(shifted, PERIOD_MS).toFloat() / PERIOD_MS
    }

    /** 진행에 따른 크기 배율. */
    fun scale(progress: Float): Float = START_SCALE + (1f - START_SCALE) * progress

    /** 진행에 따른 불투명도 — 0.9 에서 0 으로, 끝에서 빨리 옅어져야 「사라졌다」로 보인다(ease-out). */
    fun opacity(progress: Float): Float {
        val eased = 1f - (1f - progress) * (1f - progress)
        return 0.9f * (1f - eased)
    }
}

/**
 * 내 위치를 알리는 **레이더 파문** (iOS `RadarPulse`, 검색 탭 「내 위치」).
 *
 * SDK 의 파란 점만으로는 지도에 흩뿌려진 촬영지 핀 사이에 묻힌다. 움직이는 것은 눈이 먼저 잡는다.
 * 지도 마커는 그림 한 장이라 스스로 움직이지 못해, iOS 와 같이 지도 **위에** 얹고 지도가 움직이는
 * 동안 화면 좌표를 계속 다시 잡는다. 손짓은 가로막지 않는다(Canvas 는 입력을 받지 않는다).
 *
 * 자리는 SDK 의 위치 오버레이가 쥔 좌표를 읽는다 — 「내 위치」 가 오버레이를 켜기 전에는 아무것도 그리지 않는다.
 */
@Composable
fun MyLocationPulse(
    map: NaverMap?,
    // 코스 지도(MZ2AZ-478)는 위치 추적 모드 없이 오버레이 좌표만 직접 찍는다 — 그때는 `false` 로 추적 모드를 따지지 않는다.
    // 검색 탭은 기본값(`true`)이라 동작이 그대로다.
    requireTracking: Boolean = true,
    // 코스 지도가 「내 위치」 토글을 끄면 파문도 접는다.
    visible: Boolean = true,
) {
    if (map == null) return
    var spot by remember(map) { mutableStateOf<Offset?>(null) }
    var elapsedMs by remember(map) { mutableLongStateOf(0L) }
    LaunchedEffect(map, requireTracking, visible) {
        while (true) {
            val overlay = map.locationOverlay
            val position = overlay.position
            val tracking = !requireTracking || map.locationTrackingMode != LocationTrackingMode.None
            if (visible && tracking && overlay.isVisible && position.isValid) {
                val point = map.projection.toScreenLocation(position)
                spot = Offset(point.x, point.y)
                withFrameNanos { elapsedMs = it / 1_000_000L }
            } else {
                spot = null
                delay(250)
            }
        }
    }
    val center = spot ?: return
    val accent = IOS.accent
    Canvas(Modifier.fillMaxSize()) {
        val maxRadius = RadarPulseShape.SPREAD_DP.dp.toPx() / 2
        for (wave in 0 until RadarPulseShape.WAVES) {
            val progress = RadarPulseShape.progress(elapsedMs, wave)
            drawCircle(
                color = accent.copy(alpha = 0.22f * RadarPulseShape.opacity(progress)),
                radius = maxRadius * RadarPulseShape.scale(progress),
                center = center,
            )
        }
        // 가운데 점. 파문이 사라져도 **내 자리는 늘 보여야 한다.**
        val dot = 18.dp.toPx() / 2
        val ring = 3.5.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.18f), radius = dot + ring / 2 + 1.5.dp.toPx(), center = center)
        drawCircle(accent, radius = dot, center = center)
        drawCircle(Color.White, radius = dot, center = center, style = Stroke(width = ring))
    }
}
