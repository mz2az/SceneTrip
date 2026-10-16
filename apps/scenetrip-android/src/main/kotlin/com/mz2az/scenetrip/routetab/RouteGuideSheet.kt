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
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.AIConsentStore
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.data.requestLocation
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.ui.AIConsentSheetHost
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.CollapseArrowsIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheetMaterial
import com.mz2az.scenetrip.ui.LocationConsentSheetHost
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
    bubble: Boolean = true,
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
        RouteGuideChipBody(bubble = bubble && screen.fontScale < 1.3f)
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
    /** 「주변」의 기준 좌표 — 기기 위치 또는 지도 중심([RouteGuideAnchor]). 없으면 질문은 미리 적어 둘 수만 있다. */
    here: Pair<Double, Double>?,
    /** [here] 가 기기 위치인지 사용자가 고른 지도 중심인지(MZ2AZ-472). 기기 위치일 때만 질문 전에 위치 동의를 묻는다. */
    source: CoordinateSource = CoordinateSource.DEVICE,
    onAdd: (GuidePlace) -> Unit,
    isAdded: (GuidePlace) -> Boolean,
    onClose: () -> Unit,
    onRemove: (GuidePlace) -> Unit = {},
    anchorDescription: String = tr("지도에 표시된 내 위치 주변을 찾아 드립니다."),
    anchorActionTitle: String = "",
    onChangeAnchor: (() -> Unit)? = null,
    cartPlaces: List<String> = emptyList(),
    onOpenCart: (() -> Unit)? = null,
    onOpenNearby: (() -> Unit)? = null,
    onOpenPlanner: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // AI 기능 동의 (MZ2AZ-468) — 첫 질문·다시 시도 전에 안내 시트. 패널이 접혀도 시트는 남도록 패널 밖에 둔다.
    val consent = remember { AIConsentGate(AIConsentStore.getInstance(context)) }
    AIConsentSheetHost(consent)
    // 위치기반서비스 동의 (MZ2AZ-472) — 질문이 기기 좌표를 싣는 순간(첫 질문·다시 시도·예시 질문) AI 동의 다음에 한 번 더 묻는다.
    // 기준 좌표가 지도 중심(사용자가 고른 영역)이면 AI 동의만 필요하다. 취소하면 입력한 질문을 그대로 두고 아무것도 보내지 않는다.
    val locationConsent = remember { AIConsentGate(LocationConsentStore.getInstance(context)) }
    LocationConsentSheetHost(locationConsent)
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
                // iOS 처럼 위치가 없어도 대화창은 열린다 — 기기 위치를 못 받아도 지도 중심이 있으면 되고, 둘 다
                // 없으면 질문을 미리 적어 둘 수만 있다(보내기·추천 질문은 잠긴다).
                // 입력 중인 글은 동의 안내를 취소해도 남는다(iOS `draft`). 추천 질문도 같은 길로 보낸다.
                var draft by remember { mutableStateOf("") }
                val askScope = rememberCoroutineScope()
                val send = { text: String ->
                    val point = here
                    if (point != null) {
                        consent.request {
                            if (!com.mz2az.scenetrip.auth.AuthStore.signedIn) {
                                com.mz2az.scenetrip.auth.AuthStore
                                    .promptSignIn()
                            } else {
                                // 기준 좌표가 기기 위치일 때만 위치 동의를 묻는다. 지도 중심(사용자가 고른 영역)은 AI 동의만 필요하다.
                                locationConsent.requestLocation(source) {
                                    draft = ""
                                    askScope.launch { session.ask(text, point.first, point.second, source) }
                                }
                            }
                        }
                    }
                }
                GuideConversation(
                    session = session,
                    here = here,
                    start =
                        GuideStartActions(
                            anchorActionTitle = anchorActionTitle,
                            onChangeAnchor = onChangeAnchor,
                            cartPlaces = cartPlaces,
                            onOpenCart = onOpenCart,
                            onOpenNearby = onOpenNearby,
                            onOpenPlanner = onOpenPlanner,
                        ),
                    onAdd = onAdd,
                    onRemove = onRemove,
                    isAdded = isAdded,
                    consent = consent,
                    locationConsent = locationConsent,
                    onExample = {
                        if (!session.asking && !session.blocked) {
                            draft = it
                            send(it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                GuideComposer(
                    session = session,
                    here = here,
                    anchorDescription = anchorDescription,
                    draft = draft,
                    onDraft = { draft = it },
                    submit = send,
                )
            }
        }
    }
}

/** 가이드 시작 화면([RouteGuideStart])이 부르는 동작 모음. */
private class GuideStartActions(
    val anchorActionTitle: String,
    val onChangeAnchor: (() -> Unit)?,
    val cartPlaces: List<String>,
    val onOpenCart: (() -> Unit)?,
    val onOpenNearby: (() -> Unit)?,
    val onOpenPlanner: (() -> Unit)?,
)

@Composable
private fun GuideConversation(
    session: RouteGuideSession,
    here: Pair<Double, Double>?,
    start: GuideStartActions,
    onAdd: (GuidePlace) -> Unit,
    onRemove: (GuidePlace) -> Unit,
    isAdded: (GuidePlace) -> Boolean,
    consent: AIConsentGate,
    locationConsent: AIConsentGate,
    onExample: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    // 시작 화면 항목이 맨 위에 하나 더 있으면(iOS: 대화 중에도 장바구니·주변 입구를 유지한다) 마지막 말 다음 항목의 번호가 하나 밀린다.
    val hasStart = session.isEmpty || start.onOpenCart != null || start.onOpenNearby != null
    LaunchedEffect(session.turns.size) {
        if (session.turns.isNotEmpty()) listState.animateScrollToItem(session.turns.size + if (hasStart) 1 else 0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // iOS: 대화가 비었거나 장바구니·주변 입구가 있으면 시작 화면을 늘 맨 위에 둔다.
        // 추천 질문은 대화가 비어 있을 때만 보인다.
        if (session.isEmpty || start.onOpenCart != null || start.onOpenNearby != null) {
            item {
                RouteGuideStart(
                    hasLocation = here != null,
                    anchorActionTitle = start.anchorActionTitle,
                    onChangeAnchor = start.onChangeAnchor,
                    cartPlaces = start.cartPlaces,
                    onOpenCart = start.onOpenCart,
                    onOpenNearby = start.onOpenNearby,
                    onOpenPlanner = start.onOpenPlanner,
                    showQuestions = session.isEmpty,
                    canAsk = here != null && !session.blocked && !session.asking,
                    onAsk = onExample,
                )
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
                        modifier =
                            Modifier
                                .clickable {
                                    consent.request {
                                        locationConsent.requestLocation(session.pendingSource) { scope.launch { session.retry() } }
                                    }
                                }.padding(vertical = 8.dp),
                    )
                }
            }
        }
        item {
            com.mz2az.scenetrip.data
                .GuideUsageNotice(onOpenPlanner = start.onOpenPlanner)
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
    here: Pair<Double, Double>?,
    anchorDescription: String,
    draft: String,
    onDraft: (String) -> Unit,
    submit: (String) -> Unit,
) {
    val now =
        com.mz2az.scenetrip.data
            .usageNow()
    val locked =
        com.mz2az.scenetrip.data.LimitLedger.guideBlock
            ?.blocked(now) == true
    // 위치를 아직 모르면 글은 칠 수 있지만 보내지는 못한다(iOS `canSend`).
    val canSend = here != null && draft.isNotBlank() && draft.trim().length <= 4000 && !session.asking && !locked

    fun send() {
        if (!canSend) return
        submit(draft)
    }
    // 입력줄 바탕 — iOS `.thinMaterial` 은 지도 빛이 비쳐 푸르스름하다(실측 약 224,238,246).
    // iOS `composer` 는 입력줄과 AI 답변 안내(MZ2AZ-439)를 한 재질 안에 쌓는다.
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFE0EEF6))) {
        // iOS `composer`: 기준이 무엇인지(기기 위치·지도 중심)를 입력줄 위에 한 줄로 알린다.
        Text(
            if (here == null) tr("지도의 위치를 확인하고 있어요. 질문을 미리 적어 두세요.") else anchorDescription,
            fontSize = 11.sp,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                    onValueChange = { if (it.length <= 4000) onDraft(it) },
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
        GuideAiNoticeLine()
    }
}
