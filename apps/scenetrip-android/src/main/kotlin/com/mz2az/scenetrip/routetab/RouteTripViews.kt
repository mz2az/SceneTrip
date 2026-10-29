package com.mz2az.scenetrip.routetab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.LimitLedger
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.data.usageNow
import com.mz2az.scenetrip.ui.CheckCircleIcon
import com.mz2az.scenetrip.ui.ForwardAppIcon
import com.mz2az.scenetrip.ui.HourglassIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.LocationArrowIcon
import com.mz2az.scenetrip.ui.LocationSlashIcon
import com.mz2az.scenetrip.ui.SealCheckIcon
import com.mz2az.scenetrip.ui.SpeechBubbleIcon
import com.mz2az.scenetrip.ui.StairsIcon
import com.mz2az.scenetrip.ui.WalkIcon
import kotlinx.coroutines.delay
import com.mz2az.scenetrip.ui.BusIcon as TripBusIcon
import com.mz2az.scenetrip.ui.SubwayIcon as TripSubwayIcon
import com.mz2az.scenetrip.ui.TransitIcon as TripTransitIcon

// 편집 화면의 **여행 안내** 화면 조각 — 안내 띠, 도착 알림 카드, 도착 스탬프, 한도 안내, 아래 단추 줄
// (MZ2AZ-478 · MZ2AZ-482). iOS `RouteEditorTrip.swift` · `PawStamp.swift` · `RouteEditorTripLimit.swift` 를 옮겼다.
// 상태는 [TripSession] 이 든다.

/**
 * 발바닥 — 큰 패드 하나 + 발가락 셋. iOS `PawShape` 의 20×20 기준 좌표를 그대로 옮겼다.
 * 「해태가 밟고 간 자리」라는 뜻으로 도착 스탬프와 방문한 정지점 핀에 쓴다.
 */
fun DrawScope.drawPaw(
    brush: Brush? = null,
    color: Color = Color.White,
) {
    val sx = size.width / 20f
    val sy = size.height / 20f

    fun oval(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val topLeft = Offset(left * sx, top * sy)
        val ovalSize = Size(width * sx, height * sy)
        if (brush != null) drawOval(brush, topLeft, ovalSize) else drawOval(color, topLeft, ovalSize)
    }
    oval(5.2f, 10.2f, 9.6f, 7.6f)
    oval(3.4f, 5.4f, 4.4f, 4.6f)
    oval(7.9f, 3.2f, 4.4f, 4.6f)
    oval(12.4f, 5.4f, 4.4f, 4.6f)
}

/** 핀과 스탬프의 하늘→보라 그러데이션(왼쪽 위 → 오른쪽 아래). */
val TripPawBrush: Brush get() = Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))

/**
 * 도착 스탬프 연출 — 크게 나타나 **쾅** 하고 자리 잡고, 1.6초 뒤 [onDone] (iOS `PawStampOverlay`).
 * 도장을 찍는 손짓을 흉내 낸다: 크고 흐리게 시작해(공중) 빠르게 줄어들며 진해진다(종이에 닿음). 살짝 기울인 채 찍힌다. 햅틱 한 번.
 * 연출이 끝나도 **다음으로 넘어가지 않는다** — 부르는 쪽이 도착 상태 그대로 선다.
 */
@Composable
fun PawStampOverlay(
    title: String,
    subtitle: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landed = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // 스프링(response .32, damping .55). 글자·핀은 0~1 로 자리 잡고 크기만 튄다.
        landed.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(Unit) {
        delay(1_600)
        onDone()
    }
    val progress = landed.value
    val appear = progress.coerceIn(0f, 1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .semantics(mergeDescendants = true) { contentDescription = "$title $subtitle" },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.scale(2.2f - 1.2f * progress).alpha(appear),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(148.dp)
                            .shadow(
                                16.dp,
                                CircleShape,
                                ambientColor = IOS.pinDeep.copy(alpha = 0.35f),
                                spotColor = IOS.pinDeep.copy(alpha = 0.35f),
                            ).clip(CircleShape)
                            .background(Color.White),
                )
                Canvas(Modifier.size(96.dp).rotate(-12f)) { drawPaw(brush = TripPawBrush) }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .alpha(appear)
                        .shadow(
                            6.dp,
                            RoundedCornerShape(50),
                            ambientColor = Color.Black.copy(alpha = 0.12f),
                            spotColor = Color.Black.copy(alpha = 0.12f),
                        ).clip(RoundedCornerShape(50))
                        .background(IOS.systemBackground)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = IOS.label, textAlign = TextAlign.Center)
                Text(subtitle, fontSize = 14.sp, color = IOS.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 시트 맨 위 안내 띠 — **가는 중에만.** 어디로 가는 중인지, 얼마나 걸리는지, 어떻게 가는지. iOS `tripBanner`. */
@Composable
fun TripGuidingBanner(
    trip: TripSession,
    targetNumber: Int?,
    onGuide: (() -> Unit)?,
    onEnd: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = trip.target ?: return
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(IOS.pinLight.copy(alpha = 0.10f))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        TripHeadline(target, targetNumber, arrived = false, onGuide = onGuide, onEnd = onEnd)
        TripGuidingDetail(trip, target, onRetry)
    }
}

/**
 * 지도 맨 위의 **도착 알림 카드** — 도착한 뒤 「다음은 사람이 고른다」를 말한다. 시트 안에 두면 성지 목록이 좁아져 지도 위에 띄운다
 * (iOS `arrivalNotice`). 카드 높이는 [onHeight] 로 알려, 지도 오른쪽 위 단추가 카드 밑으로 내려가게 한다.
 */
@Composable
fun TripArrivalCard(
    trip: TripSession,
    targetNumber: Int?,
    next: Pair<Int, RouteStop>?,
    visitStatus: VisitStatus?,
    onRetryVisit: () -> Unit,
    onGuide: (() -> Unit)?,
    onEnd: () -> Unit,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = trip.target ?: return
    val density = LocalDensity.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp)
                .onSizeChanged { onHeight(with(density) { it.height.toDp() } + 10.dp) }
                .shadow(
                    8.dp,
                    RoundedCornerShape(14.dp),
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    spotColor = Color.Black.copy(alpha = 0.14f),
                ).clip(RoundedCornerShape(14.dp))
                .background(IOS.systemBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        TripHeadline(target, targetNumber, arrived = true, onGuide = onGuide, onEnd = onEnd)
        if (visitStatus == VisitStatus.PENDING || visitStatus == VisitStatus.FAILED) {
            VisitSaveNotice(visitStatus, onRetryVisit)
        } else {
            // **다음으로 넘기지 않는다.** 둘러볼 시간은 사람의 것이다.
            Text(
                next?.let { tr("둘러본 뒤 아래 「다음 · %d번으로」를 누르세요").format(it.first) } ?: tr("오늘 일정을 모두 돌았어요"),
                style = IOS.caption,
                color = IOS.secondaryLabel,
            )
        }
    }
}

/** 방문 기록을 저장하는 중 / 못 했다 (iOS `visitSaveNotice`). 못 했으면 44dp 높이의 「다시 저장」. */
@Composable
fun VisitSaveNotice(
    status: VisitStatus?,
    onRetry: () -> Unit,
) {
    if (status == VisitStatus.PENDING) {
        Text(tr("방문 기록을 저장하는 중이에요"), style = IOS.caption, color = IOS.secondaryLabel)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("방문 기록을 저장하지 못했어요"), style = IOS.caption, color = IOS.secondaryLabel)
            Text(
                tr("다시 저장"),
                style = IOS.caption.copy(fontWeight = FontWeight.SemiBold),
                color = IOS.accent,
                modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onRetry),
            )
        }
    }
}

@Composable
private fun TripHeadline(
    target: RouteStop,
    number: Int?,
    arrived: Boolean,
    onGuide: (() -> Unit)?,
    onEnd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arrived) {
            SealCheckIcon(
                IOS.pinDeep,
                Modifier.size(13.dp),
            )
        } else {
            LocationArrowIcon(IOS.pinDeep, filled = true, modifier = Modifier.size(13.dp))
        }
        Text(
            tripTitle(target.label().title, number, arrived),
            style = IOS.subheadline.copy(fontWeight = FontWeight.Bold),
            color = IOS.label,
            modifier = Modifier.weight(1f),
        )
        if (onGuide != null) Box(Modifier.clickable(onClick = onGuide)) { RouteGuideChipBody(bubble = false) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(28.dp).clickable(onClick = onEnd),
        ) {
            Icon(Icons.Filled.Close, contentDescription = tr("안내 끝"), tint = IOS.secondaryLabel, modifier = Modifier.size(13.dp))
        }
    }
}

/** 가는 중의 자세한 줄 — iOS `tripDetail` 순서 그대로: 받은 경로 → 한도 → 실패(재시도) → 구하는 중 → 자리를 못 찾음. */
@Composable
private fun TripGuidingDetail(
    trip: TripSession,
    target: RouteStop,
    onRetry: () -> Unit,
) {
    val leg = trip.leg
    val failure = trip.failure
    when {
        leg != null -> {
            TripNavigationSummary(duration = RouteFormat.minutes(leg.totalMinutes), summary = leg.summaryLine())
            // 안내 언어가 앱 언어와 다르면 그대로 보여 주고 언어만 표시한다(MZ2AZ-305). 번역은 별도.
            leg.languageNote()?.let { note ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpeechBubbleIcon(IOS.secondaryLabel, Modifier.size(11.dp))
                    Text(note, style = IOS.caption2, color = IOS.secondaryLabel)
                }
            }
            // 구간 — 도보·대중교통 조각을 한 줄로. 밀면 다 보인다.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                leg.chips().forEach { LegChip(it) }
            }
            Text(trip.dwellHint(), style = IOS.caption2, color = IOS.tertiaryLabel)
        }

        failure?.isLimit == true -> {
            // 길찾기 한도 — 실패 한 줄이 아니라 지도 앱으로 넘기는 안내다(MZ2AZ-366 D).
            TripLimitNotice(target, onRetry)
        }

        failure != null -> {
            // 계약 응답별 문구(`RouteNavFailure`). 다시 불러 달라질 수 있는 것에만 단추.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = IOS.secondaryLabel, modifier = Modifier.size(11.dp))
                Text(failure.message, style = IOS.caption, color = IOS.secondaryLabel, modifier = Modifier.weight(1f, fill = false))
                if (failure.canRetry) MiniBorderedButton(tr("다시 시도"), onRetry)
            }
        }

        trip.asking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                Text(tr("길을 찾는 중입니다"), style = IOS.caption, color = IOS.secondaryLabel)
            }
        }

        trip.here == null -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LocationSlashIcon(IOS.secondaryLabel, Modifier.size(12.dp))
                Text(tr("현재 위치를 찾는 중입니다"), style = IOS.caption, color = IOS.secondaryLabel)
            }
        }
    }
}

/** iOS `TripNavigationSummary` — 시간(굵게 20)과 요약. 좁거나 큰 글자면 요약이 다음 줄로 내려간다(MZ2AZ-117). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TripNavigationSummary(
    duration: String,
    summary: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(duration, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        Text(summary, style = IOS.caption, color = IOS.secondaryLabel)
    }
}

/** iOS `.bordered` + `.controlSize(.mini)` 단추 — 회색 채움·accent 글자·캡슐. 누를 자리는 44dp 로 넓힌다. */
@Composable
fun MiniBorderedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.heightIn(min = 44.dp).clickable(onClick = onClick)) {
        Text(
            label,
            style = IOS.caption.copy(fontWeight = FontWeight.SemiBold),
            color = IOS.accent,
            modifier = Modifier.clip(CircleShape).background(IOS.tertiaryFill).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 구간 칩 하나 — 도보는 회색, 지하철은 파랑, 버스·그 밖의 탈것은 초록(iOS와 같은 세 갈래). */
@Composable
fun LegChip(chip: RouteLegChip) {
    val background =
        when (chip.mode) {
            RouteLegMode.WALK -> IOS.systemGray5
            RouteLegMode.SUBWAY -> IOS.accent.copy(alpha = 0.16f)
            else -> IOS.systemGreen.copy(alpha = 0.18f)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val icon = Modifier.size(11.dp)
        when (chip.mode) {
            RouteLegMode.WALK -> WalkIcon(IOS.label, icon)
            RouteLegMode.BUS -> TripBusIcon(IOS.label, icon)
            RouteLegMode.SUBWAY -> TripSubwayIcon(IOS.label, icon)
            RouteLegMode.TRANSIT -> TripTransitIcon(IOS.label, icon)
        }
        Text(chip.text, fontSize = 11.sp, color = IOS.label, maxLines = 1)
        if (chip.hasStairs) StairsIcon(IOS.systemRed, Modifier.size(10.dp))
    }
}

/**
 * 안내 띠의 **길찾기 한도 안내 — 지도 앱으로 넘기기** (MZ2AZ-366 D, iOS `tripLimitNotice`). 앱 안 길찾기가 한도(429
 * `NAVIGATION_LIMIT_REACHED`)에 걸려도 여행은 멈추지 않는다 — 안내는 켜진 채로(목적지·직선 계획선·머무름 도착 판정·「여기 도착함」
 * 그대로) 그 구간만 카카오맵·네이버 지도가 대신 안내한다. 상태는 응답 헤더로 [LimitLedger] 가 든다.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TripLimitNotice(
    target: RouteStop,
    onRetry: () -> Unit,
) {
    val now = usageNow()
    val block = LimitLedger.navigationBlock
    if (block == null || block.lifted(now)) {
        // 풀렸다(또는 계정이 바뀌어 잊었다). **저절로 다시 부르지 않는다** — 유료 호출은 사람이 누른다.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckCircleIcon(IOS.secondaryLabel, Modifier.size(12.dp))
            Text(
                tr("이제 앱 안에서 다시 길을 찾을 수 있어요"),
                style = IOS.caption,
                color = IOS.secondaryLabel,
                modifier = Modifier.weight(1f, fill = false),
            )
            MiniBorderedButton(tr("다시 시도"), onRetry)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HourglassIcon(IOS.systemOrange, Modifier.size(12.dp))
            Text(
                NavLimit.message(NavLimit.window(block.retryAfter)),
                style = IOS.caption,
                color = IOS.systemOrange,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 풀리는 때를 모르면 잠그지 않는다 — 바로 다시 물어볼 수 있다. 서버가 다시 정한다.
            if (block.until == null) MiniBorderedButton(tr("다시 시도"), onRetry)
        }
        HandoffButtons(target)
        Text(tr("도착하면 돌아와서 「여기 도착함」 을 눌러 주세요"), style = IOS.caption2, color = IOS.secondaryLabel)
    }
}

/**
 * 지도 앱으로 넘기는 단추 — 카카오맵은 언제나(앱이 없으면 웹), 네이버 지도는 앱이 깔려 있을 때만. 좁으면(영어·큰 글자) 두 줄로 내려간다.
 * **안내는 끄지 않는다** — 돌아오면 그 자리에서 이어진다. 외부 지도 앱이 출발지를 얻는다: 기기 좌표를 주소에 싣지 않는다(MZ2AZ-472).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun HandoffButtons(target: RouteStop) {
    val context = LocalContext.current
    val end = ExternalDirections.Spot(target.label().title, target.place.latitude, target.place.longitude)

    fun intent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    fun available(url: String?) = url != null && intent(url).resolveActivity(context.packageManager) != null

    fun open(
        url: String?,
        fallback: String? = null,
    ) {
        if (url == null) return
        try {
            context.startActivity(intent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // 앱 스킴이 끝내 안 열리면(방금 지웠다 등) 웹으로 물러선다.
            fallback?.let { runCatching { context.startActivity(intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
    val kakaoApp = ExternalDirections.kakaoApp(null, end)
    val kakaoWeb = ExternalDirections.kakaoWeb(null, end)
    val naver = ExternalDirections.naver(null, end)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val kakao = if (available(kakaoApp)) kakaoApp else kakaoWeb
        if (kakao != null) {
            HandoffButton(tr("카카오맵에서 길찾기"), opensApp = kakao == kakaoApp) { open(kakao, kakaoWeb) }
        }
        if (available(naver)) HandoffButton(tr("네이버 지도에서 길찾기"), opensApp = true) { open(naver) }
    }
}

@Composable
private fun HandoffButton(
    label: String,
    opensApp: Boolean,
    onClick: () -> Unit,
) {
    val hint = if (opensApp) tr("지도 앱이 열려요") else tr("브라우저에서 지도가 열려요")
    // 보이는 알약은 낮아도 **누를 자리는 44dp** 다(걸으면서 누르는 단추다).
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .semantics { contentDescription = "$label, $hint" }
                .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .clip(
                        RoundedCornerShape(16.dp),
                    ).background(IOS.accent.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            ForwardAppIcon(IOS.accent, Modifier.size(13.dp))
            Text(label, style = IOS.caption.copy(fontWeight = FontWeight.SemiBold), color = IOS.accent)
        }
    }
}

/**
 * 아래 단추 줄의 배치 — 접근성 큰 글자에서는 동작마다 화면 폭을 쓰도록 세로로 쌓는다 (MZ2AZ-388, iOS `controlLayout`).
 * [content] 는 주된 동작에 붙일 `grow` 수식어를 받는다 — 가로에서는 남는 폭을 채우고, 세로에서는 줄 전체 폭이다.
 */
@Composable
private fun ControlLayout(
    stacked: Boolean,
    content: @Composable (grow: Modifier) -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { content(Modifier.fillMaxWidth()) }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) { content(Modifier.weight(1f)) }
    }
}

/** 큰 글자인가 — iOS `dynamicTypeSize.isAccessibilitySize`(접근성 크기). 기기 글자 배율이 이 이상이면 쌓는다. */
@Composable
fun isAccessibilityFontScale(): Boolean = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE

/** 접근성 큰 글자로 보는 글자 배율. 제어 센터의 「큰 글씨」 1.3 은 넘고 표준 확대 1.15 는 못 미친다. */
const val ACCESSIBILITY_FONT_SCALE = 1.5f

/** 「오늘 일정을 모두 돌았어요」 / 남은 곳이 숨김·폐업뿐이면 그 말 (iOS `dayFinishedNotice`). */
@Composable
fun DayFinishedNotice(
    onlyUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    if (onlyUnavailable) {
        Text(
            tr("갈 수 있는 곳이 없어요. 목록에서 빠지거나 폐업한 곳만 남았어요."),
            style = IOS.subheadline,
            color = IOS.secondaryLabel,
            modifier = modifier,
        )
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            SealCheckIcon(IOS.pinDeep, Modifier.size(15.dp))
            Text(tr("오늘 일정을 모두 돌았어요"), style = IOS.subheadlineSemibold, color = IOS.pinDeep)
        }
    }
}

/**
 * 계획 중의 아래 줄 — **주된 동작이 줄을 채우고, 보조(「여행 종료」)는 늘 왼쪽에 작게**(안내 중의 [TripControls] 와 같은 자리). 새 코스면
 * 「코스 만들기」, 저장된 코스면 시작 전엔 「코스 시작」, 여행 중엔 「N번으로 길찾기」(+「여행 종료」)다 (iOS `planControls`).
 *
 * **저장된 코스에는 저장 단추가 없다**(2026-10-08 사용자 결정, MZ2AZ-368). 앞서 여기 있던 「저장하고 닫기」는 머리줄 「저장」과 같은
 * 일을 했는데 이름이 달라 다른 기능처럼 읽혔다. 저장은 머리줄 하나다. 새 코스의 「코스 만들기」는 남긴다 — 그때는 이 줄에 다른 단추가
 * 없고, 처음 만드는 사람이 머리줄의 작은 「만들기」를 놓치기 쉽다.
 */
@Composable
fun TripPlanControls(
    isNew: Boolean,
    running: Boolean,
    saving: Boolean,
    canSave: Boolean,
    stopsEmpty: Boolean,
    onlyUnavailable: Boolean,
    next: Pair<Int, RouteStop>?,
    startBlocked: Boolean,
    onCreate: () -> Unit,
    onToggleRunning: () -> Unit,
    onStartTrip: (RouteStop) -> Unit,
) {
    ControlLayout(isAccessibilityFontScale()) { grow ->
        when {
            isNew -> {
                // 「코스 시작」은 저장된 코스에만 있다 — 아직 만들지도 않은 일정을 여행 중으로 만들 수는 없다.
                EditorCapsuleButton(
                    if (saving) "…" else tr("코스 만들기"),
                    prominent = true,
                    enabled = !saving && canSave,
                    onClick = onCreate,
                    modifier = grow,
                )
            }

            !running && onlyUnavailable -> {
                DayFinishedNotice(onlyUnavailable = true, modifier = grow)
            }

            !running -> {
                // 시작 전에는 이것이 이 줄의 하나뿐인 동작이다 — 누르면 앱이 흐름을 이어받는다.
                EditorCapsuleButton(tr("코스 시작"), prominent = true, enabled = !startBlocked, onClick = onToggleRunning, modifier = grow)
            }

            next != null -> {
                EditorCapsuleButton(tr("여행 종료"), prominent = false, onClick = onToggleRunning)
                // 여행 중이면 **다음 성지로 길찾기가 주된 동작**이다 — 경로는 이 화면의 지도에 그려진다.
                EditorCapsuleButton(
                    tr("%d번으로 길찾기").format(next.first),
                    prominent = true,
                    enabled = !startBlocked,
                    onClick = { onStartTrip(next.second) },
                    modifier = grow,
                )
            }

            else -> {
                // 다 돌았거나 빈 일차를 보고 있으면 갈 곳이 없다. **「여행 종료」는 그래도 작은 보조 단추다** — 누르면 묻지 않고 바로 상태가
                // 바뀌는데, 줄 전체가 그 단추면 잘못 누르기 쉽다(MZ2AZ-368 검증). 남는 자리에는 다 돌았을 때만 그 말을 적는다.
                EditorCapsuleButton(tr("여행 종료"), prominent = false, onClick = onToggleRunning)
                if (stopsEmpty) Box(grow) else DayFinishedNotice(onlyUnavailable = onlyUnavailable, modifier = grow)
            }
        }
    }
}

/**
 * 안내 중의 아래 줄 — 가는 중이면 「여기 도착함」(탈출구), 도착했으면 「다음 · N번으로」 (iOS `tripControls`).
 * 저장 단추는 이 줄에 없다 — 저장은 언제나 머리줄의 「저장」이 한다.
 */
@Composable
fun TripControls(
    arrived: Boolean,
    stamped: Boolean,
    next: Pair<Int, RouteStop>?,
    onlyUnavailable: Boolean,
    nextBlocked: Boolean,
    onEnd: () -> Unit,
    onNext: (RouteStop) -> Unit,
    onArriveNow: () -> Unit,
) {
    ControlLayout(isAccessibilityFontScale()) { grow ->
        EditorCapsuleButton(tr("안내 끝"), prominent = false, onClick = onEnd)
        if (arrived) {
            if (next != null) {
                EditorCapsuleButton(
                    tr("다음 · %d번 %s로 길찾기").format(next.first, next.second.label().title),
                    prominent = true,
                    enabled = !nextBlocked,
                    onClick = { onNext(next.second) },
                    modifier = grow,
                )
            } else {
                DayFinishedNotice(onlyUnavailable = onlyUnavailable, modifier = grow)
            }
        } else {
            // 머무를 시간이 없거나 GPS 가 튈 때의 탈출구. 저절로 찍힐 때와 같은 길이다.
            EditorCapsuleButton(tr("여기 도착함"), prominent = true, enabled = !stamped, onClick = onArriveNow, modifier = grow)
        }
    }
}

/**
 * iOS 의 캡슐 버튼(`.bordered` / `.borderedProminent`, `.controlSize(.regular)`, 글자 15). 큰 글자에서는 줄을 바꿔 다 읽히게 한다 —
 * 한 줄로 자르면 「다음 · 3번 낙산공원로…」 만 남는다.
 */
@Composable
fun EditorCapsuleButton(
    label: String,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 높이는 iOS 의 32 다(`.buttonStyle(.borderedProminent)` 를 감사 스크린샷에서 쟀다 — 이전 글줄 19.5 + 위아래 8 은 37.3 이었다).
    // 글자가 크게 늘어난 접근성 글꼴에서는 32 가 최소이고 더 자란다.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .alpha(if (enabled) 1f else 0.4f)
                .clip(CircleShape)
                .background(if (prominent) IOS.accent else IOS.tertiaryFill)
                .clickable(enabled = enabled, onClick = onClick)
                .heightIn(min = 32.dp)
                .padding(horizontal = 14.dp),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            color = if (prominent) Color.White else IOS.accent,
            textAlign = TextAlign.Center,
        )
    }
}
