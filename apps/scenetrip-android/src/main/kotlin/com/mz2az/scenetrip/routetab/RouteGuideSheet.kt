package com.mz2az.scenetrip.routetab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 화면 오른쪽 아래에 늘 떠 있는 해태 "내가 도와줄게!" — 가이드 챗봇의 입구.
 * iOS `RouteGuideFloatingChip`/`RouteGuideChipBody`를 옮긴 것이다 — **꾹 눌러 옮기는
 * 것은 없다**, 자리는 늘 오른쪽 아래로 고정이다.
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
        Text(
            "내가 도와줄게!",
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
                contentDescription = "여행 가이드",
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
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.15f))
                        .clickable(onClick = onClose),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(300.dp)
                        .background(IOS.systemBackground)
                        .statusBarsPadding(),
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                    Text(
                        "여행 가이드",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IOS.label,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "접기",
                        tint = IOS.secondaryLabel,
                        modifier = Modifier.align(Alignment.CenterEnd).size(20.dp).clickable(onClick = onClose),
                    )
                }
                if (here == null) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("현재 위치를 알 수 없습니다", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        Text(
                            "위치 권한을 켜면 주변 장소를 찾아 드립니다",
                            fontSize = 12.sp,
                            color = IOS.secondaryLabel,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    GuideConversation(session = session, onAdd = onAdd, isAdded = isAdded, modifier = Modifier.weight(1f))
                    GuideComposer(session = session, here = here)
                }
            }
        }
    }
}

@Composable
private fun GuideConversation(
    session: RouteGuideSession,
    onAdd: (GuidePlace) -> Unit,
    isAdded: (GuidePlace) -> Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(16.dp),
    ) {
        if (session.isEmpty) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("무엇을 도와드릴까요?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                    Text("지금 있는 자리를 기준으로 주변을 찾아 드립니다.", fontSize = 12.sp, color = IOS.secondaryLabel)
                }
            }
        }
        items(session.turns, key = { it.id }) { turn ->
            val isLastAssistant = turn.role == GuideTurn.Role.ASSISTANT && turn.id == session.turns.lastOrNull()?.id
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (turn.role == GuideTurn.Role.USER) Arrangement.End else Arrangement.Start,
                ) {
                    Text(
                        turn.text,
                        fontSize = 13.sp,
                        color = IOS.label,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (turn.role ==
                                        GuideTurn.Role.USER
                                    ) {
                                        IOS.accent.copy(alpha = 0.14f)
                                    } else {
                                        IOS.pinLight.copy(alpha = 0.16f)
                                    },
                                ).padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
                if (isLastAssistant) {
                    if (turn.tools.isNotEmpty()) {
                        Text(
                            "🔧 " + turn.tools.joinToString(" · "),
                            fontSize = 10.sp,
                            color = IOS.tertiaryLabel,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    session.places.forEach { place ->
                        GuidePlaceRow(place = place, added = isAdded(place), onAdd = { onAdd(place) })
                    }
                }
            }
        }
        if (session.asking) {
            item {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
        }
        session.failure?.let { message ->
            item { Text("⚠️ $message", fontSize = 11.sp, color = IOS.systemOrange, modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}

@Composable
private fun GuidePlaceRow(
    place: GuidePlace,
    added: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(IOS.pinDeep))
        Column(modifier = Modifier.weight(1f)) {
            Text(place.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = IOS.label, maxLines = 1)
            val subtitle = listOfNotNull(place.category, place.address).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 10.sp, color = IOS.secondaryLabel, maxLines = 1)
            }
        }
        Text(
            if (added) "담김" else "담기",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (added) IOS.tertiaryLabel else IOS.accent,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background((if (added) IOS.tertiaryLabel else IOS.accent).copy(alpha = 0.12f))
                    .clickable(enabled = !added, onClick = onAdd)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GuideComposer(
    session: RouteGuideSession,
    here: Pair<Double, Double>,
) {
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("무엇이든 물어보세요", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = !session.asking,
        )
        Text(
            "전송",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (draft.isBlank() || session.asking) IOS.tertiaryLabel else IOS.accent,
            modifier =
                Modifier.clickable(enabled = draft.isNotBlank() && !session.asking) {
                    val text = draft
                    draft = ""
                    scope.launch { session.ask(text, here.first, here.second) }
                },
        )
    }
}
