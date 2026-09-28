package com.mz2az.scenetrip.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** iOS `.presentationDetents` — 반쯤(`.medium`), 크게(`.large`). [IOSSheet] 의 `fixedHeight` 는 `.height(n)`. */
enum class SheetDetent { MEDIUM, LARGE }

/**
 * iOS 26 `.sheet` — 아래에서 올라오는 카드.
 *
 * - **반쯤 높이에서는 떠 있다**: 양옆·아래 8 떨어지고 네 모서리가 약 38 로 둥글다. 크게 끌어
 *   올리면 화면에 붙고 위 모서리만 둥글다. 그 사이는 끄는 만큼 부드럽게 바뀐다(2026-09-28 iOS 실측).
 * - 뒤는 20% 어둡게, 높이가 둘이면 손잡이(36×5). 손잡이·머리줄을 끌거나 **목록을 끌어도**
 *   (목록이 맨 위일 때) 시트가 커지고 작아지며, 끝까지 내리거나 바깥을 누르면 닫힌다.
 * - 카드는 **보이는 높이만큼만** 잡는다 — 큰 카드를 아래로 밀어 두면 가운데 놓인 빈 상태가
 *   반쯤 높이에서 화면 밖으로 나갔다.
 *
 * [content] 는 카드를 채운다. 자체 머리줄(제목·닫기)은 content 가 그린다.
 */
@Composable
fun IOSSheet(
    detents: List<SheetDetent>,
    onDismiss: () -> Unit,
    fixedHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        val density = LocalDensity.current
        val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screen = maxHeight
            val largeH = screen - statusTop - 10.dp
            val restH =
                when {
                    fixedHeight != null -> fixedHeight
                    detents.first() == SheetDetent.MEDIUM -> screen * 0.5f
                    else -> largeH
                }
            val maxH = if (SheetDetent.LARGE in detents) largeH else restH
            val maxPx = with(density) { maxH.toPx() }
            val restPx = with(density) { restH.toPx() }
            val floats = restH < largeH
            // 보이는 카드 높이(px). 0 이면 화면 밖(닫힘).
            val shown = remember { Animatable(0f) }
            var opened by remember { mutableStateOf(false) }
            var dismissing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            // 인셋(상태바 높이)이 첫 프레임 뒤에 들어와 restPx 가 바뀐다 — 그때마다 **다시 그 높이로 간다**.
            // 앞서 「처음 한 번만 올린다」로 두었더니 올라오는 도중 인셋이 들어와 애니메이션이 취소되고,
            // 다시 올리지 않아 크게 시트가 화면 밖에 멈췄다(보이지 않는 막이 터치를 먹었다, 2026-09-28).
            // 닫히는 중이거나, 반쯤 시트를 사람이 크게로 끌어 올려 둔 경우만 건드리지 않는다.
            LaunchedEffect(restPx) {
                val raisedByUser = opened && restPx < maxPx && shown.value > restPx + 1f
                if (!dismissing && !raisedByUser) shown.animateTo(restPx, tween(280))
                opened = true
            }

            fun settle(velocity: Float = 0f) {
                val stops =
                    buildList {
                        if (SheetDetent.LARGE in detents) add(maxPx)
                        add(restPx)
                    }
                scope.launch {
                    if (shown.value < restPx * 0.72f || (velocity > 2500f && shown.value <= restPx)) {
                        dismissing = true
                        shown.animateTo(0f, tween(200))
                        onDismiss()
                    } else {
                        val target =
                            if (abs(velocity) >
                                1200f
                            ) {
                                (if (velocity < 0) stops.max() else stops.min())
                            } else {
                                stops.minBy { abs(it - shown.value) }
                            }
                        shown.animateTo(target, tween(220))
                    }
                }
            }
            val nested =
                remember(maxPx) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            // 위로 끌면 목록보다 먼저 시트가 커진다.
                            if (available.y < 0 && shown.value < maxPx) {
                                val take = (-available.y).coerceAtMost(maxPx - shown.value)
                                scope.launch { shown.snapTo(shown.value + take) }
                                return Offset(0f, -take)
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            // 목록이 맨 위라 남은 아래 끌기는 시트가 받는다.
                            if (available.y > 0) {
                                scope.launch { shown.snapTo((shown.value - available.y).coerceAtLeast(0f)) }
                                return Offset(0f, available.y)
                            }
                            return Offset.Zero
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            val settled = shown.value == maxPx || shown.value == restPx
                            if (settled) return Velocity.Zero
                            settle(available.y) // 위로 튕기면 음수 — settle 도 음수를 「위로」로 읽는다
                            return available
                        }
                    }
                }
            val shownDp = with(density) { shown.value.toDp() }
            // 0 = 반쯤(떠 있음), 1 = 크게(붙음).
            val grow = if (maxPx > restPx) ((shown.value - restPx) / (maxPx - restPx)).coerceIn(0f, 1f) else 0f
            val lift = if (floats) lerp(8.dp, 0.dp, grow) else 0.dp
            val bottomCorner = if (floats) lerp(38.dp, 0.dp, grow) else 0.dp
            val topCorner = if (floats) 38.dp else IOS.sheetCorner
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = IOS.DIM * (shown.value / restPx).coerceIn(0f, 1f)))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch {
                                dismissing = true
                                shown.animateTo(0f, tween(200))
                                onDismiss()
                            }
                        },
            ) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            // 반쯤보다 아래(닫히는 중)는 높이를 줄이지 않고 통째로 내린다.
                            .offset { IntOffset(0, (restPx - shown.value).coerceAtLeast(0f).roundToInt()) }
                            .padding(horizontal = lift)
                            .padding(bottom = lift)
                            .fillMaxWidth()
                            .height(if (shownDp < restH) restH else shownDp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = topCorner,
                                    topEnd = topCorner,
                                    bottomStart = bottomCorner,
                                    bottomEnd = bottomCorner,
                                ),
                            ).background(IOS.systemBackground)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .nestedScroll(nested)
                            // 끌기는 **카드 윗부분(손잡이·머리줄 44)에서 시작한 것만** 받는다. 카드 전체에 걸었더니
                            // 시트 안 편집기의 지도를 위아래로 움직이는 손가락까지 시트가 가져갔다(10차 검증).
                            // 목록은 위 nestedScroll 로 시트와 이어진다.
                            .pointerInput(detents) {
                                val headerPx = 44.dp.toPx()
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.position.y > headerPx) return@awaitEachGesture
                                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                    if (slop != null) {
                                        verticalDrag(slop.id) { change ->
                                            val drag = change.positionChange().y
                                            change.consume()
                                            scope.launch { shown.snapTo((shown.value - drag).coerceIn(0f, maxPx)) }
                                        }
                                        settle()
                                    }
                                }
                            }
                            // 떠 있는(반쯤) 카드만 제스처 막대만큼 비운다. 크게 붙은 카드는 내용이 화면 끝까지
                            // 닿는다 — 비워 두었더니 시트 안 편집기 아래에 24 띠가 드러났다(10차 검증).
                            .padding(bottom = if (floats) lerp(navBottom, 0.dp, grow) else 0.dp),
                ) {
                    if (detents.size > 1) {
                        Box(Modifier.fillMaxWidth().padding(top = 5.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(IOS.systemGray3))
                        }
                    }
                    content()
                }
            }
        }
    }
}

/**
 * 시트 안 목록의 끝 여백 — 크게 붙은 시트는 내용이 화면 끝까지 닿으므로 **목록 끝에만** 제스처 막대만큼
 * 비운다. 시트에서 비우면 편집기 아래에 띠가 드러났고, 아무 데도 안 비우면 마지막 줄이 막대와 겹쳤다.
 */
@Composable
fun sheetListBottom(): androidx.compose.foundation.layout.PaddingValues =
    androidx.compose.foundation.layout.PaddingValues(
        bottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() },
    )
