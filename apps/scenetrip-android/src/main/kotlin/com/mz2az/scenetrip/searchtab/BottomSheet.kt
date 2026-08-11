package com.mz2az.scenetrip.searchtab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS
import kotlin.math.abs

/** 시트가 멈추는 세 자리. */
enum class Detent(
    val ratio: Float,
) {
    COLLAPSED(IOS.DETENT_COLLAPSED),
    MEDIUM(IOS.DETENT_MEDIUM),
    EXPANDED(1f),
}

/**
 * 지도 위에 얹히는 3단 스냅 시트.
 *
 * iOS `SearchTab/BottomSheet.swift` 를 그대로 옮겼다. 그 파일이 이렇게 못 박아 뒀다:
 *
 * > 스냅 비율 14% / 48% / 최대는 두 앱이 **같은 숫자**를 써야 한다 — 플랫폼 기본값
 * > (iOS `UISheetPresentationController`, Android `BottomSheetBehavior`)에 맡기면
 * > 미묘하게 갈린다.
 *
 * 그래서 `BottomSheetScaffold` 를 쓰지 않는다. 그것은 자기 비율과 자기 애니메이션을
 * 갖고 오며, 그 순간 두 앱의 시트가 다른 높이에서 멈춘다.
 *
 * 중간 단이 42% 가 아니라 48% 인 것도 iOS 쪽 실측 결과다 — 42% 에서는 작품 상세의
 * 설명까지만 보이고 촬영지 행이 한 줄도 안 보였다.
 *
 * @param topInset 검색바가 차지하는 높이. 최대 단계는 이 아래까지만 올라온다.
 * @param onHeightChange 지금 덮고 있는 실제 높이. 지도가 로고·축척을 이 위에 올린다.
 */
@Composable
fun BottomSheet(
    detent: Detent,
    onDetentChange: (Detent) -> Unit,
    topInset: Dp,
    onHeightChange: (Dp) -> Unit = {},
    content: @Composable () -> Unit,
) {
    // **`fillMaxSize` 여야 한다.** 너비만 채우면 `maxHeight` 가 내용 높이로 잡혀
    // 스냅 비율이 화면이 아니라 시트 자신을 기준으로 계산되고, 정렬도 위로 붙는다
    // (실측 — 시트가 화면 위쪽에 뜨고 지도가 아래로 밀렸다).
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val total = maxHeight
        val maxSheet = total - topInset
        val density = LocalDensity.current

        fun heightFor(target: Detent): Dp = if (target == Detent.EXPANDED) maxSheet else total * target.ratio

        // 끄는 동안의 손가락 이동량(px). 손을 떼면 0 으로 돌아가고 detent 가 바뀐다.
        var drag by remember { mutableFloatStateOf(0f) }

        val settled = heightFor(detent)
        val animated by animateFloatAsState(
            targetValue = with(density) { settled.toPx() },
            label = "sheetHeight",
        )
        val heightPx =
            (animated - drag)
                .coerceIn(with(density) { 80.dp.toPx() }, with(density) { maxSheet.toPx() })
        val height = with(density) { heightPx.toDp() }

        // 합성 중에 콜백을 부르면 안 된다 — 부수 효과다. 높이가 바뀐 뒤에 알린다.
        LaunchedEffect(height) { onHeightChange(height) }

        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            ambientColor = IOS.label,
                            spotColor = IOS.label,
                        ).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(IOS.systemBackground)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { drag += it },
                            onDragStopped = { velocity ->
                                // iOS 는 predictedEndTranslation 으로 던진 방향을 본다.
                                // 여기서는 속도를 같은 뜻으로 쓴다.
                                val endPx = heightPx - velocity * 0.1f
                                val endDp = with(density) { endPx.toDp() }
                                onDetentChange(
                                    Detent.entries.minByOrNull {
                                        abs((heightFor(it) - endDp).value)
                                    } ?: Detent.MEDIUM,
                                )
                                drag = 0f
                            },
                        ),
            ) {
                // 손잡이 — iOS 는 40×5 의 systemGray3 캡슐이다.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 40.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(IOS.systemGray3),
                    )
                }
                content()
            }
        }
    }
}
