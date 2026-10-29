package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 밀어서 지우는 줄 — iOS 26 `.swipeActions` / `.onDelete` 의 모양.
 *
 * 줄을 왼쪽으로 밀면 줄이 **둥근 알약으로 줄어들며** 오른쪽에 **빨간 휴지통 단추와 그 아래 「삭제」** 가 드러난다.
 * 단추를 눌러야 [onDelete] 가 불린다. 끝까지(줄 폭의 절반 넘게) 밀면 단추를 안 눌러도 바로 [onDelete] 를 부르고 줄은
 * 제자리로 돌아온다(iOS `allowsFullSwipe`). 판정은 [CourseSwipe.settle] 이다.
 *
 * 코스 목록 줄과 코스 편집기의 장소 줄이 함께 쓴다. 열린 줄이 하나뿐이도록 [isOpen] 은 바깥이 든다 —
 * 다른 줄이 열려 [isOpen] 이 꺼지면 이 줄은 제자리로 돌아오고, 밀기를 시작하면 [onClaim], 닫히면 [onRelease] 를 부른다.
 *
 * @param revealedColor 단추가 드러난 동안의 줄 바탕. null 이면 [containerColor] 그대로다
 *   (iOS 편집기 줄은 밀리면 연한 회색으로 눌린 색이 된다).
 * @param allowsFullSwipe false 면 끝까지 밀어도 단추만 드러난 채 멈춘다(iOS 편집기 `.onDelete` 줄 — 실기 확인).
 * @param onTap 닫힌 줄을 눌렀을 때. 열린 줄을 누르면 늘 줄만 닫는다(iOS). null 이면 닫힌 줄의 탭은 안쪽에 맡긴다.
 */
@Composable
fun SwipeDeleteRow(
    onDelete: () -> Unit,
    isOpen: Boolean,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    reveal: Dp = 80.dp,
    buttonSize: DpSize = DpSize(60.dp, 41.dp),
    containerColor: Color = IOS.systemBackground,
    revealedColor: Color? = null,
    onTap: (() -> Unit)? = null,
    allowsFullSwipe: Boolean = true,
    content: @Composable (revealed: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { reveal.toPx() }
    // 드러난 폭(px). 0 = 닫힘.
    val revealed = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val deleteLabel = tr("삭제")
    var fullWidthPx by remember { mutableStateOf(0f) }
    // 다른 줄이 열리면(`isOpen` 이 꺼지면) 이 줄은 제자리로 돌아온다.
    LaunchedEffect(isOpen) {
        if (!isOpen && revealed.value > 0f) revealed.animateTo(0f)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = (reveal - buttonSize.width) / 2),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .background(IOS.systemRed)
                        .semantics { contentDescription = deleteLabel }
                        .clickable {
                            scope.launch { revealed.animateTo(0f) }
                            onRelease()
                            onDelete()
                        },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(deleteLabel, fontSize = 12.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            modifier =
                Modifier
                    .clip(if (revealed.value > 0f) RoundedCornerShape(corner) else RoundedCornerShape(0.dp))
                    // 줄은 제 폭을 지킨 채 왼쪽으로 밀리고, 보이는 폭만 줄어든다(iOS 도 글자가 왼쪽에서 잘린다).
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        fullWidthPx = placeable.width.toFloat()
                        val shown = revealed.value.toInt().coerceIn(0, placeable.width)
                        layout(placeable.width - shown, placeable.height) { placeable.place(-shown, 0) }
                    }.fillMaxWidth()
                    .background(if (revealed.value > 0f && revealedColor != null) revealedColor else containerColor)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            // 밀기 시작 — 앞서 열려 있던 줄은 이때 닫힌다.
                            onDragStart = { onClaim() },
                            onDragEnd = {
                                val settle = CourseSwipe.settle(revealed.value, revealPx, fullWidthPx, allowsFullSwipe)
                                // 끝까지 밀었다 — iOS 는 첫 동작을 바로 부른다.
                                if (settle != SwipeSettle.OPEN) onRelease()
                                if (settle == SwipeSettle.FULL) onDelete()
                                scope.launch { revealed.animateTo(if (settle == SwipeSettle.OPEN) revealPx else 0f) }
                            },
                            onDragCancel = {
                                onRelease()
                                scope.launch { revealed.animateTo(0f) }
                            },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                scope.launch { revealed.snapTo((revealed.value - dx).coerceIn(0f, fullWidthPx)) }
                            },
                        )
                    }.clickable(enabled = onTap != null || revealed.value > 0f) {
                        // 열려 있으면 탭은 줄을 닫을 뿐이다(iOS).
                        if (revealed.value > 0f) {
                            onRelease()
                            scope.launch { revealed.animateTo(0f) }
                        } else {
                            onTap?.invoke()
                        }
                    },
        ) {
            content(revealed.value > 0f)
        }
    }
}
