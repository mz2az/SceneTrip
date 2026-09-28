package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** iOS `Button(role:)`의 역할 — 취소는 굵게, 파괴는 빨갛게. */
enum class IOSRole { DEFAULT, CANCEL, DESTRUCTIVE }

data class IOSAction(
    val label: String,
    val role: IOSRole = IOSRole.DEFAULT,
    val onClick: () -> Unit,
)

/**
 * iOS `.alert` — 270pt 폭, 가운데 정렬 제목·본문, 버튼은 둘이면 가로로 반씩, 셋 이상이면 세로.
 * 모양은 `searchtab/Locate.kt` 의 위치 경고창에서 실측해 둔 값을 공용으로 뺀 것이다. Material
 * `AlertDialog`(왼쪽 정렬·오른쪽 아래 글자 버튼)는 한눈에 다른 앱이라 쓰지 않는다
 * (2026-09-28 화면 대조 #10).
 */
@Composable
fun IOSAlert(
    title: String,
    message: String?,
    actions: List<IOSAction>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // 창이 상태바 높이만큼 밀려 내려가고 상태바가 안 어두워졌다 — 창을 화면 전체로 편다.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = IOS.DIM)),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(IOS.alertWidth)
                        .clip(RoundedCornerShape(IOS.alertCorner))
                        .background(IOS.popupSurface),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    Text(title, style = IOS.headline, color = IOS.label, textAlign = TextAlign.Center)
                    if (!message.isNullOrEmpty()) {
                        Text(message, style = IOS.footnote, color = IOS.label, textAlign = TextAlign.Center)
                    }
                }
                Box(Modifier.fillMaxWidth().height(IOS.hairline).background(IOS.separator))
                if (actions.size == 2) {
                    // iOS 는 취소를 왼쪽에 둔다.
                    val ordered = actions.sortedBy { if (it.role == IOSRole.CANCEL) 0 else 1 }
                    Row(Modifier.height(IOS.alertButton)) {
                        AlertCell(ordered[0], Modifier.weight(1f), onDismiss)
                        Box(Modifier.width(IOS.hairline).fillMaxHeight().background(IOS.separator))
                        AlertCell(ordered[1], Modifier.weight(1f), onDismiss)
                    }
                } else {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(IOS.hairline).background(IOS.separator))
                        AlertCell(action, Modifier.fillMaxWidth().height(IOS.alertButton), onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCell(
    action: IOSAction,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier.fillMaxHeight().clickable {
                onDismiss()
                action.onClick()
            },
    ) {
        Text(
            action.label,
            style = IOS.body.copy(fontWeight = if (action.role == IOSRole.CANCEL) FontWeight.SemiBold else FontWeight.Normal),
            color = if (action.role == IOSRole.DESTRUCTIVE) IOS.systemRed else IOS.accent,
        )
    }
}

/**
 * iOS 26 `.confirmationDialog` — **누른 단추 옆에 붙는 팝오버.** 옅은 회색 유리 카드(폭 약 240, 모서리
 * 32)에 검은 17 제목(여러 줄, 왼쪽 정렬)과 높이 48 의 진한 회색 캡슐 동작 단추(실측, 2026-09-28). **취소 단추도 어둡게 덮는 막도 없다** —
 * 바깥을 누르면 닫힌다. 아래에서 올라오는 시트는 iOS 16 이전 모양이라 쓰지 않는다(2026-09-28
 * iOS 26 실기 확인).
 *
 * 이 컴포저블을 **단추(또는 그 줄)의 자리 안에** 둔다 — 그 자리를 기준으로 뜬다. [anchorX] 는 그
 * 자리 왼쪽에서 단추 글자 끝까지의 거리다(카드가 그 오른쪽에 붙고 꼬리가 글자를 가리킨다).
 */
@Composable
fun IOSConfirmPopover(
    title: String,
    actions: List<IOSAction>,
    anchorX: Dp,
    onDismiss: () -> Unit,
    message: String? = null,
    // true 면 자리 **아래 가운데**에 뜨고 꼬리가 위를 가리킨다(목록 줄처럼 옆에 자리가 없을 때).
    below: Boolean = false,
) {
    val density = LocalDensity.current
    // 그림자가 팝업 창 경계에서 네모로 잘리지 않게 카드 둘레에 여백을 두고, 그만큼 자리를 되돌린다.
    val shadowRoom = 24.dp
    val roomPx = with(density) { shadowRoom.roundToPx() }
    val gapPx = with(density) { (anchorX + 2.dp).roundToPx() }
    val provider =
        remember(gapPx, roomPx, below) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    return if (below) {
                        IntOffset(
                            (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX),
                            (anchorBounds.bottom - roomPx).coerceIn(0, maxY),
                        )
                    } else {
                        IntOffset(
                            (anchorBounds.left + gapPx - roomPx).coerceAtMost(maxX),
                            (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, maxY),
                        )
                    }
                }
            }
        }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        if (below) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(shadowRoom)) {
                Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
                    val tail =
                        Path().apply {
                            moveTo(0f, size.height)
                            lineTo(size.width / 2, 0f)
                            lineTo(size.width, size.height)
                            close()
                        }
                    drawPath(tail, POPOVER_SURFACE)
                }
                PopoverCard(title, message, actions, onDismiss)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(shadowRoom)) {
                // 꼬리 — 단추 글자를 가리키는 작은 세모.
                Canvas(Modifier.size(width = 8.dp, height = 18.dp)) {
                    val tail =
                        Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(0f, size.height / 2)
                            lineTo(size.width, size.height)
                            close()
                        }
                    drawPath(tail, POPOVER_SURFACE)
                }
                PopoverCard(title, message, actions, onDismiss)
            }
        }
    }
}

@Composable
private fun PopoverCard(
    title: String,
    message: String?,
    actions: List<IOSAction>,
    onDismiss: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .width(240.dp)
                .shadow(
                    20.dp,
                    RoundedCornerShape(32.dp),
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.22f),
                ).clip(RoundedCornerShape(32.dp))
                .background(POPOVER_SURFACE)
                // 단추는 카드 안쪽 16/16 을 쓰고 **제목만** 30 들여쓴다(iOS 실측: 단추 폭 208).
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 14.dp, end = 9.dp)) {
            Text(title, fontSize = 17.sp, color = IOS.label)
            if (!message.isNullOrEmpty()) Text(message, fontSize = 13.sp, color = IOS.secondaryLabel)
        }
        actions.forEach { action ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(POPOVER_BUTTON)
                        .clickable {
                            onDismiss()
                            action.onClick()
                        },
            ) {
                Text(
                    action.label,
                    fontSize = 17.sp,
                    color = if (action.role == IOSRole.DESTRUCTIVE) IOS.systemRed else IOS.accent,
                )
            }
        }
    }
}

/** 팝오버 카드 — iOS 26 유리 재질을 흰 목록 위에서 잰 값(245). 순백이면 흰 목록 위에서 카드가 안 보였다. */
private val POPOVER_SURFACE = Color(0xFFF5F5F6)

/** 팝오버 단추 — 카드(245)보다 확실히 진한 회색(216, 실측). */
private val POPOVER_BUTTON = Color(0xFFD8D8DA)
