package com.mz2az.scenetrip.routetab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.CollapseArrowsIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheetMaterial
import com.mz2az.scenetrip.ui.SendArrowCircleIcon
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 화면 오른쪽 아래에 늘 떠 있는 해태 "내가 도와줄게!" — 가이드 챗봇의 입구.
 * iOS `RouteGuideFloatingChip`/`RouteGuideChipBody`와 같은 입구. 꾹 눌러 옮긴 위치를 기억한다.
 */
@Composable
fun RouteGuideFloatingChip(
    hidden: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hidden) return
    // **길게 눌러 끌어 옮긴다**(iOS `RouteGuideFloatingChip.moveGesture`) — 옮긴 자리는 기억한다
    // (iOS `@AppStorage("scenetrip.guideChip.dx/dy")` 와 같은 열쇠). 오른쪽 아래가 원점이라 늘 0 이하다.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("scenetrip", android.content.Context.MODE_PRIVATE) }
    var dx by remember { mutableStateOf(prefs.getFloat("scenetrip.guideChip.dx", 0f)) }
    var dy by remember { mutableStateOf(prefs.getFloat("scenetrip.guideChip.dy", 0f)) }
    var lifted by remember { mutableStateOf(false) }
    val screen = LocalConfiguration.current
    val density = LocalDensity.current
    val minX = with(density) { -(screen.screenWidthDp.dp - 12.dp - 180.dp).toPx() }
    val minY = with(density) { -(screen.screenHeightDp.dp - 76.dp - 60.dp).toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .scale(if (lifted) 1.08f else 1f)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { lifted = true },
                        onDragEnd = {
                            lifted = false
                            prefs
                                .edit()
                                .putFloat("scenetrip.guideChip.dx", dx)
                                .putFloat("scenetrip.guideChip.dy", dy)
                                .apply()
                        },
                        onDragCancel = { lifted = false },
                    ) { change, amount ->
                        change.consume()
                        dx = (dx + amount.x).coerceIn(minX, 0f)
                        dy = (dy + amount.y).coerceIn(minY, 0f)
                    }
                }.clickable(onClick = onTap),
    ) {
        RouteGuideChipBody(bubble = true)
    }
}

/** 떠 있는 입구와 안내 배너가 같은 얼굴을 쓰며 배너에는 말풍선을 붙이지 않는다. */
@Composable
fun RouteGuideChipBody(bubble: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bubble) {
            Text(
                tr("내가 도와줄게!"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.pinDeep,
                // iOS `RouteGuideChipBody`: 흰 캡슐 + pinLight 60% 1pt 테두리 + 옅은 그림자(검정 12%, r3, y1).
                modifier =
                    Modifier
                        .shadow(
                            3.dp,
                            RoundedCornerShape(50),
                            ambientColor = Color.Black.copy(alpha = 0.12f),
                            spotColor = Color.Black.copy(alpha = 0.12f),
                        ).clip(RoundedCornerShape(50))
                        .background(IOS.systemBackground)
                        .border(1.dp, IOS.pinLight.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        // 흰 원 + 핀 그러데이션 2pt 테두리 + 그림자(검정 20%, r4, y2), 안쪽 여백 5.
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                    .clip(CircleShape)
                    .background(IOS.systemBackground)
                    .border(2.dp, Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)), CircleShape)
                    .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.haetae_face),
                contentDescription = tr("여행 가이드"),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 여행 가이드 대화창 — 오른쪽에서 미끄러져 나오는 고정 폭 창. iOS
 * `RouteGuideSheet`/`guidePanel`을 옮긴 것이다. **지도가 계속 보이게** 화면을 다
 * 덮지 않는다.
 */
@Composable
fun RouteGuidePanel(
    isOpen: Boolean,
    session: RouteGuideSession,
    here: Pair<Double, Double>?,
    onAdd: (GuidePlace) -> Unit,
    isAdded: (GuidePlace) -> Boolean,
    onClose: () -> Unit,
    onRemove: (GuidePlace) -> Unit = {},
) {
    // iOS `guidePanel`: 화면 오른쪽 아래에 **떠 있는 316×470 카드**(모서리 20, 그림자, 재질 바탕, 오른쪽 6 ·
    // 아래 8). 뒤를 어둡게 덮지 않아 지도가 계속 보이고, 닫으면 오른쪽 아래 동그라미 쪽으로 오므라든다.
    // 앞서 Android 는 오른쪽에서 나오는 전체 높이 서랍에 딤까지 깔아 모양이 달랐다(2026-09-29 대조).
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = scaleIn(initialScale = 0.05f, transformOrigin = TransformOrigin(1f, 1f)) + fadeIn(),
        exit = scaleOut(targetScale = 0.05f, transformOrigin = TransformOrigin(1f, 1f)) + fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomEnd) {
            Column(
                modifier =
                    Modifier
                        .padding(end = 6.dp, bottom = 8.dp)
                        .size(width = 316.dp, height = 470.dp)
                        // iOS 검정 22%·r14·y6. Android 그림자 색의 알파는 플랫폼 그림자 농도(스팟 약 19%)에
                        // **곱해져** 22% 를 주면 거의 안 보였다(2차 대조) — 최대(검정 그대로)로 둔다.
                        .shadow(
                            16.dp,
                            RoundedCornerShape(20.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black,
                        ).clip(RoundedCornerShape(20.dp))
                        .background(IOSSheetMaterial)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(
                        tr("여행 가이드"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IOS.label,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 창 줄이기(안으로 모이는 화살) — 대화가 사라지는 게 아니라 동그라미로 접힌다.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.align(Alignment.CenterEnd).size(32.dp).clickable(onClick = onClose),
                    ) {
                        CollapseArrowsIcon(IOS.secondaryLabel, Modifier.size(15.dp))
                    }
                }
                if (here == null) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            tr("현재 위치를 알 수 없습니다"),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = IOS.label,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            tr("위치 권한을 켜면 주변 장소를 찾아 드립니다"),
                            fontSize = 15.sp,
                            color = IOS.secondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    GuideConversation(
                        session = session,
                        here = here,
                        onAdd = onAdd,
                        onRemove = onRemove,
                        isAdded = isAdded,
                        modifier = Modifier.weight(1f),
                    )
                    GuideComposer(session = session, here = here)
                }
            }
        }
    }
}

/** iOS `examples` — 되는 질문 하나만 둔다(안 되는 예시는 첫인상에서 신뢰를 깎는다). */
private val GUIDE_EXAMPLES get() = listOf(tr("주변 음식점 알려줘"))

@Composable
private fun GuideConversation(
    session: RouteGuideSession,
    here: Pair<Double, Double>,
    onAdd: (GuidePlace) -> Unit,
    onRemove: (GuidePlace) -> Unit,
    isAdded: (GuidePlace) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    LaunchedEffect(session.turns.size) {
        if (session.turns.isNotEmpty()) listState.animateScrollToItem(session.turns.size)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session.isEmpty) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("무엇을 도와드릴까요?"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                    Text(tr("지금 있는 자리를 기준으로 주변을 찾아 드립니다."), fontSize = 12.sp, color = IOS.secondaryLabel)
                    GUIDE_EXAMPLES.forEach { example ->
                        Text(
                            example,
                            fontSize = 12.sp,
                            color = IOS.label,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .background(IOS.systemGray6)
                                    .clickable { scope.launch { session.ask(example, here.first, here.second) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        items(session.turns, key = { it.id }) { turn ->
            val isUser = turn.role == GuideTurn.Role.USER
            val isLastAssistant = !isUser && turn.id == session.turns.lastOrNull()?.id
            Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                Text(
                    turn.text,
                    fontSize = 15.sp,
                    color = IOS.label,
                    modifier =
                        Modifier
                            .padding(start = if (isUser) 40.dp else 0.dp, end = if (isUser) 0.dp else 40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isUser) IOS.accent.copy(alpha = 0.14f) else IOS.pinLight.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                )
                // 마지막 답에만 — 무엇을 근거로 말했는지(도구)와 찾은 곳들.
                if (isLastAssistant) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp, top = 6.dp)) {
                        if (turn.tools.isNotEmpty()) {
                            Text(turn.tools.joinToString(" · "), fontSize = 11.sp, color = IOS.tertiaryLabel)
                        }
                        session.places.forEach { place ->
                            val isPicked = place.id == session.picked?.id
                            GuidePlaceRow(
                                place = place,
                                isPicked = isPicked,
                                added = isAdded(place),
                                onToggle = { if (isPicked) session.dismiss() else session.pick(place) },
                                onAdd = { onAdd(place) },
                                onRemove = { onRemove(place) },
                            )
                            if (isPicked) {
                                RoutePlaceCard(
                                    place = place,
                                    added = isAdded(place),
                                    onAdd = { onAdd(place) },
                                    onRemove = { onRemove(place) },
                                    onClose = { session.dismiss() },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (session.asking) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    // 몇 초 걸리는지 미리 말한다. 안 그러면 멈춘 줄 안다(실측 9~57초).
                    Text(tr("찾는 중입니다… 10초쯤 걸립니다"), fontSize = 12.sp, color = IOS.secondaryLabel)
                }
            }
        }
        session.failure?.let { message ->
            item {
                Text(message, fontSize = 13.sp, color = IOS.systemOrange)
                val now =
                    com.mz2az.scenetrip.data
                        .usageNow()
                if (session.canRetry &&
                    com.mz2az.scenetrip.data.LimitLedger.guideBlock
                        ?.blocked(now) != true
                ) {
                    Text(
                        tr("다시 시도"),
                        color = IOS.accent,
                        modifier = Modifier.clickable { scope.launch { session.retry() } }.padding(vertical = 8.dp),
                    )
                }
            }
        }
        item {
            com.mz2az.scenetrip.data
                .GuideUsageNotice()
        }
    }
}

/** iOS `placeRow`: 갈래 색 점(16) · 이름(12) · 갈래·주소(11) · 거리 · 펼침 꺾쇠 · 담기/담김. */
@Composable
private fun GuidePlaceRow(
    place: GuidePlace,
    isPicked: Boolean,
    added: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).clickable(onClick = onToggle),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isPicked) IOS.systemRed else RoutePoiTone.of(place.categoryGroup.toRoutePoiGroup())),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    place.name,
                    fontSize = 12.sp,
                    fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Medium,
                    color = IOS.label,
                    maxLines = 1,
                )
                val subtitle = listOfNotNull(place.category, place.address).joinToString(" · ")
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, color = IOS.tertiaryLabel, maxLines = 1)
            }
            place.distanceMeters?.let { Text("$it m", fontSize = 11.sp, color = IOS.tertiaryLabel) }
        }
        ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(10.dp).rotate(if (isPicked) -90f else 90f))
        if (added) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = tr("담김 · 누르면 빼기"),
                tint = IOS.secondaryLabel,
                modifier = Modifier.size(17.dp).clickable(onClick = onRemove),
            )
        } else {
            CircleSignIcon(plus = true, tint = IOS.accent, modifier = Modifier.size(17.dp).clickable(onClick = onAdd))
        }
    }
}

/** iOS `composer`: 회색 캡슐 입력창(「주변에 무엇을 찾으세요?」) + `arrow.up.circle.fill` 30, 얇은 재질 바탕. */
@Composable
private fun GuideComposer(
    session: RouteGuideSession,
    here: Pair<Double, Double>,
) {
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val now =
        com.mz2az.scenetrip.data
            .usageNow()
    val locked =
        com.mz2az.scenetrip.data.LimitLedger.guideBlock
            ?.blocked(now) == true
    val canSend = draft.isNotBlank() && draft.trim().length <= 4000 && !session.asking && !locked

    fun send() {
        if (!canSend) return
        if (!com.mz2az.scenetrip.auth.AuthStore.signedIn) {
            com.mz2az.scenetrip.auth.AuthStore
                .promptSignIn()
            return
        }
        val text = draft
        draft = ""
        scope.launch { session.ask(text, here.first, here.second) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                // 입력줄 바탕 — iOS `.thinMaterial` 은 지도 빛이 비쳐 푸르스름하다(실측 약 224,238,246).
                .background(Color(0xFFE0EEF6))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(IOS.systemGray6)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (draft.isEmpty()) Text(tr("주변에 무엇을 찾으세요?"), fontSize = 17.sp, color = IOS.tertiaryLabel, maxLines = 1)
            BasicTextField(
                value = draft,
                onValueChange = { if (it.length <= 4000) draft = it },
                enabled = !session.asking && !locked,
                maxLines = 3,
                textStyle = IOS.body.copy(color = IOS.label),
                cursorBrush = SolidColor(IOS.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SendArrowCircleIcon(
            if (canSend) IOS.accent else Color(0xFFD2DAE0), // 비었을 때 iOS 는 푸른 기 도는 옅은 회색(실측)
            Modifier.size(30.dp).clickable(enabled = canSend) { send() },
            arrow = Color(0xFFE0EEF6),
        )
    }
}
