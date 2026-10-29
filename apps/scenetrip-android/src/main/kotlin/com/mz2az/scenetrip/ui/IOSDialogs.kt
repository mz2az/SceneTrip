package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * iOS 26 `.alert` — **유리 카드에 알약 단추.** 폭 319, 모서리 34, 단추는 높이 48 의 진한 회색 알약이고 둘이면 가로로
 * 반씩(사이 8), 하나면 가득, 셋 이상이면 세로로 쌓는다. 카드 안쪽 여백 16(2026-10-29 「바꾼 내용을 버릴까요?」 실측).
 * 취소 성격 단추 글자는 검정, 파괴 성격은 빨강, 그 밖은 강조색이다. 반투명 유리는 Compose 가 뒤 화면을 못 읽어
 * 불투명한 옅은 회색(235)으로 옮긴다. 이전 모양(270 폭, 모서리 14, 구분선 단추)은 iOS 26 이전 것이다.
 *
 * Material `AlertDialog`(왼쪽 정렬·오른쪽 아래 글자 버튼)는 한눈에 다른 앱이라 쓰지 않는다.
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
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = IOS.DIM)).padding(horizontal = 16.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(19.dp),
                modifier =
                    Modifier
                        .widthIn(max = ALERT_WIDTH)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ALERT_CORNER))
                        .background(ALERT_SURFACE)
                        .padding(start = 16.dp, end = 16.dp, top = 21.dp, bottom = 16.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 본문 없이 제목만 있으면 보통 굵기(실측), 본문이 있으면 제목을 굵게 한다.
                    Text(
                        title,
                        style = IOS.body,
                        fontWeight = if (message.isNullOrEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                        color = IOS.label,
                        textAlign = TextAlign.Center,
                    )
                    if (!message.isNullOrEmpty()) {
                        Text(message, style = IOS.footnote, color = IOS.label, textAlign = TextAlign.Center)
                    }
                }
                if (actions.size <= 2) {
                    // iOS 는 취소를 왼쪽에 둔다.
                    val ordered = actions.sortedBy { if (it.role == IOSRole.CANCEL) 0 else 1 }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ordered.forEach { AlertCell(it, Modifier.weight(1f), onDismiss) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { AlertCell(it, Modifier.fillMaxWidth(), onDismiss) }
                    }
                }
            }
        }
    }
}

/** iOS 26 알림 카드 폭 — 402 폭 화면에서 319 로 쟀다. */
private val ALERT_WIDTH = 319.dp
private val ALERT_CORNER = 34.dp

/** 알림 카드 바탕 — 유리를 어두운 지도 위에서 잰 값(236·237·238). */
private val ALERT_SURFACE = Color(0xFFECEDEE)

/** 알림 단추 — 카드보다 진한 회색(207·207·209). */
private val ALERT_BUTTON = Color(0xFFCFCFD1)

@Composable
private fun AlertCell(
    action: IOSAction,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(ALERT_BUTTON)
                .clickable {
                    onDismiss()
                    action.onClick()
                },
    ) {
        Text(
            action.label,
            style = IOS.body,
            color =
                when (action.role) {
                    IOSRole.DESTRUCTIVE -> IOS.systemRed
                    IOSRole.CANCEL -> IOS.label
                    IOSRole.DEFAULT -> IOS.accent
                },
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
    // true 면 꼬리 없이 **화면 한가운데**에 뜬다 — 단추가 아니라 화면 전체에 붙인 `.confirmationDialog`(코스 목록 삭제)의 자리다
    // (iOS 실측: 카드 가운데가 화면 높이의 51.5%, 2026-10-30 재감사 R10).
    centered: Boolean = false,
) {
    val density = LocalDensity.current
    // 그림자가 팝업 창 경계에서 네모로 잘리지 않게 카드 둘레에 여백을 두고, 그만큼 자리를 되돌린다.
    val shadowRoom = 24.dp
    val roomPx = with(density) { shadowRoom.roundToPx() }
    val gapPx = with(density) { (anchorX + 2.dp).roundToPx() }
    // 아래·위 어느 쪽에 뜨는가 — iOS 는 자리가 더 넉넉한 쪽에 띄운다(위쪽 줄은 아래로, 아래쪽 줄은 위로. 2026-10-29 감사 #17).
    // 꼬리 방향이 이 값을 따라 바뀐다. 자리는 팝업이 놓일 때 알 수 있어 그때 적는다.
    var above by remember { mutableStateOf(false) }
    val provider =
        remember(gapPx, roomPx, below, centered) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    return if (centered) {
                        IntOffset(maxX / 2, (windowSize.height * 0.515f - popupContentSize.height / 2f).toInt().coerceIn(0, maxY))
                    } else if (below) {
                        val flip = anchorBounds.top > windowSize.height - anchorBounds.bottom
                        if (above != flip) above = flip
                        val y = if (flip) anchorBounds.top - popupContentSize.height + roomPx else anchorBounds.bottom - roomPx
                        IntOffset((anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX), y.coerceIn(0, maxY))
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
        if (centered) {
            Box(modifier = Modifier.padding(shadowRoom)) { PopoverCard(title, message, actions, onDismiss) }
        } else if (below) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(shadowRoom)) {
                if (!above) {
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
                }
                PopoverCard(title, message, actions, onDismiss)
                if (above) {
                    Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
                        val tail =
                            Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width / 2, size.height)
                                lineTo(size.width, 0f)
                                close()
                            }
                        drawPath(tail, POPOVER_SURFACE)
                    }
                }
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
        // 글과 단추 사이 — 글 가운데에서 단추 윗변까지 iOS 29, 실측(2026-10-29 감사).
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
        // 본문이 없으면 제목은 가운데·보통 굵기(언어 고르기 「언어」), 본문이 있으면 왼쪽·굵게(코스 지우기)다(2026-10-29 감사 실측).
        val centered = message.isNullOrEmpty()
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier.fillMaxWidth().padding(start = if (centered) 0.dp else 14.dp, end = if (centered) 0.dp else 9.dp),
        ) {
            // 제목은 17 검정, 본문은 15 회색(2026-10-29 감사 실측). 본문이 있으면 둘 다 왼쪽 정렬이다 — 「」 괄호는 Noto 가
            // 글자 칸 한쪽에 붙여 그려 제목 머리가 본문보다 들어가 보이는데 글꼴 차이라 맞출 수 없다.
            // 본문이 있는 제목은 iOS 글자 폭이 17sp Noto 보다 11% 좁다(112-sbs 실측 220px/245px) — 본문(15sp)이 같은 글꼴 차 3.5% 만
            // 넓은 것과 견주면 15.3sp 가 맞다(실기 158.6dp → 151.7dp, iOS 146.7pt × 1.035). 본문 없는 가운데 제목(「언어」)은 그대로 17sp.
            // 「 는 Noto 가 글자 칸 오른쪽에 그려 머리가 본문보다 7dp 들어가 보인다 — iOS 는 본문과 같은 선(165 대 167)이라 그만큼 당긴다.
            Text(
                title,
                fontSize = if (centered) 17.sp else 15.3.sp,
                fontWeight = if (centered) FontWeight.Normal else FontWeight.SemiBold,
                color = IOS.label,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = if (!centered && title.startsWith("「")) Modifier.offset(x = (-7).dp) else Modifier,
            )
            if (!message.isNullOrEmpty()) Text(message, fontSize = 15.sp, color = IOS.secondaryLabel, textAlign = TextAlign.Start)
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
                    // iOS 는 일반 단추 글자를 검정으로 그린다(언어 고르기 실측) — 파괴 단추만 빨강.
                    color = if (action.role == IOSRole.DESTRUCTIVE) IOS.systemRed else IOS.label,
                )
            }
        }
    }
}

/** 팝오버 카드 — iOS 26 유리 재질을 흰 목록 위에서 잰 값(245). 순백이면 흰 목록 위에서 카드가 안 보였다. */
private val POPOVER_SURFACE = Color(0xFFF5F5F6)

/** 팝오버 단추 — 카드(245)보다 확실히 진한 회색(216, 실측). */
private val POPOVER_BUTTON = Color(0xFFD8D8DA)
