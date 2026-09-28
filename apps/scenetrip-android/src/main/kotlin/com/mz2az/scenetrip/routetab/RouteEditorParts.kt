package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.FootprintPoint
import com.mz2az.scenetrip.data.RoutePoiGroup
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.searchtab.FilmIcon
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.searchtab.ScopeIcon
import com.mz2az.scenetrip.ui.ArrowDownIcon
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.FlagIcon
import com.mz2az.scenetrip.ui.GripLinesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetMaterial
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.LocationArrowIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import kotlin.math.roundToInt

/**
 * 코스 편집 화면의 부품들. iOS `RouteTab/RouteEditorParts.swift`를 옮긴 것이다 —
 * 여행 중(길찾기·고정 배지)·챗봇 관련 부분은 뺐다, 그 화면 자체가 아직 없다.
 *
 * 정류지 줄은 iOS `RouteStopRow` 를 그대로 옮긴다(2026-09-29 밤, 화면 대조 #13):
 * - 번호 22(그러데이션) · 이름 15 semibold · 부가 줄 11 · 작품 줄 · 머무는 시간 캡슐 · 오른쪽 `line.3.horizontal` 손잡이.
 * - 아랫줄(왼쪽 34 들여) — 30 높이 캡슐들: 안내 중(`location.fill`, 보라 채움) 또는 길찾기(`location`, 옅은 보라),
 *   출발/도착 고정, 그리고 `arrow.down` 다음 곳까지 거리.
 * - **빼기는 밀어서**(왼쪽으로 밀면 빨간 삭제, 끝까지 밀면 빠진다 — iOS `.onDelete`), **순서는 손잡이를 끌어서**
 *   (iOS `.onMove`). 앞서 Android 는 ▲▼ 글자와 빨간 「빼기」 글자가 늘 보였다.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RouteStopRow(
    number: Int,
    stop: RouteStop,
    nextKilometers: Double?,
    isFocused: Boolean,
    pinLabel: String?,
    isPinned: Boolean,
    onFocus: () -> Unit,
    onStay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onTogglePin: () -> Unit,
    isTarget: Boolean = false,
    onNavigate: (() -> Unit)? = null,
    // 이 장소가 나온 작품. iOS `RouteStopRow.works` — 계약의 `CourseItem`엔 작품이
    // 없어(`placeId`·`name`·`address`만 온다) 바깥(`RouteStore.places`)에서
    // 되짚어 넣어 준다.
    works: String = "",
) {
    var rowHeight by remember { mutableStateOf(0) }
    var dragY by remember { mutableStateOf(0f) }
    // 순서가 바뀌면 콜백이 새로 온다 — 그것을 제스처의 키로 두면 끄는 도중 제스처가 다시 시작되며
    // `onDragEnd` 가 불리지 않아 남은 dragY 로 행이 떠 있었다(15차: 37dp 겹침). 키는 고정하고 최신 콜백만 읽는다.
    val moveUp by rememberUpdatedState(onMoveUp)
    val moveDown by rememberUpdatedState(onMoveDown)
    val swipe =
        androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) onRemove()
                false
            },
        )
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier =
            Modifier
                .zIndex(if (dragY != 0f) 1f else 0f)
                .offset {
                    androidx.compose.ui.unit
                        .IntOffset(0, dragY.roundToInt())
                }.onSizeChanged { rowHeight = it.height },
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize().background(IOS.systemRed).padding(horizontal = 20.dp),
            ) {
                Text("삭제", fontSize = 17.sp, color = Color.White)
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (isFocused) IOS.accent.copy(alpha = 0.07f).compositeOver(IOS.systemBackground) else IOS.systemBackground)
                    // iOS `List` 행 여백(약 11) + `.padding(.vertical, 4)` — 8 이면 행이 15 낮았다.
                    .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().alpha(if (stop.visited) 0.45f else 1f).clickable(onClick = onFocus),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(IOS.pinLight, IOS.pinDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$number", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stop.place.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 1)
                        if (stop.isPinned) {
                            Badge(text = "내가 찍은 곳", background = IOS.systemGray5, foreground = IOS.secondaryLabel)
                        }
                        if (stop.placeMissing) {
                            Badge(text = "저장 안 됨", background = IOS.systemOrange.copy(alpha = 0.15f), foreground = IOS.systemOrange)
                        }
                    }
                    stop.arriveMinute?.let {
                        Text("${RouteGuidePlan.clock(it)} 도착 예정", fontSize = 11.sp, color = IOS.secondaryLabel)
                    }
                    val subtitle = listOfNotNull(stop.place.type, stop.place.address).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1)
                    }
                    // **어느 작품에 나온 곳인가.** 이 앱에 오는 이유가 그것이라 유형·주소보다 중요한 줄이다.
                    if (works.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilmIcon(tint = IOS.pinDeep, modifier = Modifier.size(9.dp))
                            Text(works, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = IOS.pinDeep, maxLines = 1)
                        }
                    }
                }
                Text(
                    stop.stayLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = IOS.label,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IOS.accent.copy(alpha = 0.12f))
                            .clickable(onClick = onStay)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                )
                // 손잡이 — 끄는 만큼 행이 따라오고, 반 줄을 넘으면 이웃과 자리를 바꾼다.
                GripLinesIcon(
                    IOS.tertiaryLabel,
                    Modifier
                        .size(12.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { dragY = 0f },
                                onDragCancel = { dragY = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragY += amount
                                val half = rowHeight / 2f
                                val down = moveDown
                                val up = moveUp
                                if (dragY > half && down != null) {
                                    down()
                                    dragY -= rowHeight
                                } else if (dragY < -half && up != null) {
                                    up()
                                    dragY += rowHeight
                                }
                            }
                        },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 34.dp, top = 8.dp),
            ) {
                if (isTarget) {
                    StopCapsule(background = IOS.pinDeep) {
                        LocationArrowIcon(Color.White, filled = true, modifier = Modifier.size(11.dp))
                        Text("안내 중", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                } else if (onNavigate != null) {
                    // 여행 중 이 곳으로 길찾기 — 별도 창이 아니라 이 화면의 지도에 경로가 그려진다.
                    StopCapsule(background = IOS.pinDeep.copy(alpha = 0.12f), onClick = onNavigate) {
                        LocationArrowIcon(IOS.pinDeep, filled = false, modifier = Modifier.size(11.dp))
                        Text(
                            if (stop.visited) "다시 길찾기" else "길찾기",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = IOS.pinDeep,
                            maxLines = 1,
                        )
                    }
                }
                if (pinLabel != null) {
                    val pinTint = if (isPinned) Color.White else IOS.secondaryLabel
                    StopCapsule(background = if (isPinned) IOS.accent else IOS.systemGray6, onClick = onTogglePin) {
                        FlagIcon(tint = pinTint, filled = isPinned, modifier = Modifier.size(10.dp))
                        Text("$pinLabel 고정", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = pinTint, maxLines = 1)
                    }
                }
                nextKilometers?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArrowDownIcon(IOS.tertiaryLabel, Modifier.size(14.dp))
                        Text(RouteFormat.kilometers(it), fontSize = 12.sp, color = IOS.tertiaryLabel, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 행 아랫줄의 30 높이 캡슐. */
@Composable
private fun StopCapsule(
    background: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(background)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 9.dp),
    ) { content() }
}

@Composable
private fun Badge(
    text: String,
    background: Color,
    foreground: Color,
) {
    Text(
        text,
        fontSize = 9.sp,
        color = foreground,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 「내 위치」 토글. 검색 탭의 현위치 버튼(`ScopeIcon`)과 같은 과녁 십자 모양을 쓰되,
 * 여기서는 **토글**이다 — iOS `RouteEditorControls.locateButton`. 켜면 지도가
 * 촬영지와 내 자리가 같이 보이는 크기로 맞는다; 켜짐은 배경색으로만 구별한다
 * (모양을 바꾸면 "위치 기능이 꺼졌다"로 오해할 수 있다).
 */
@Composable
fun RouteLocateButton(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f))
                .clip(CircleShape)
                .background(if (on) IOS.accent else IOS.systemBackground)
                .border(1.dp, if (on) Color.Transparent else IOS.systemGray4, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ScopeIcon(tint = if (on) Color.White else IOS.accent, modifier = Modifier.size(20.dp))
    }
}

/** 서버 갈래(`PoiCategoryGroup`)를 지도 색(`RoutePoiTone`)이 아는 갈래로. */
fun PoiCategoryGroup.toRoutePoiGroup(): RoutePoiGroup =
    when (this) {
        PoiCategoryGroup.food -> RoutePoiGroup.FOOD
        PoiCategoryGroup.stay -> RoutePoiGroup.STAY
        PoiCategoryGroup.sight -> RoutePoiGroup.SIGHT
        PoiCategoryGroup.transit -> RoutePoiGroup.TRANSIT
    }

/** iOS `RoutePoiGroup.label`. */
val PoiCategoryGroup.label: String
    get() =
        when (this) {
            PoiCategoryGroup.food -> "음식점"
            PoiCategoryGroup.stay -> "숙소"
            PoiCategoryGroup.sight -> "명소"
            PoiCategoryGroup.transit -> "교통"
        }

fun PoiSummary.asPlaceSummary(): PlaceSummary =
    PlaceSummary(id = 0, name = name, latitude = latitude, longitude = longitude, type = category, address = address)

/** 주변 편의시설 점도 챗봇 결과와 같은 카드로 연다 — iOS `RouteMapAmbient`의 점도
 * `RouteGuide.Place`인 것과 같은 뜻이다. */
fun PoiSummary.asGuidePlace(): com.mz2az.scenetrip.sceneapi.client.model.GuidePlace =
    com.mz2az.scenetrip.sceneapi.client.model.GuidePlace(
        id = id,
        name = name,
        category = category,
        categoryGroup = categoryGroup,
        latitude = latitude,
        longitude = longitude,
        source = com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource.poi,
        address = address,
        distanceMeters = distanceMeters,
    )

/**
 * 주변 편의시설 **갈래별 켜고 끄기 칩** — iOS `RoutePoiChips`. 「전체」가 마스터
 * 스위치다: 다 켜져 있으면 다 끄고, 하나라도 꺼져 있으면 다 켠다. 「AI 장소」칩은
 * 챗봇이 찾아 준 곳이 있을 때만 맨 앞에 나온다 — iOS `RoutePoiChips.Extra`.
 */
@Composable
fun RoutePoiChips(
    counts: Map<PoiCategoryGroup, Int>,
    groupsOn: Set<PoiCategoryGroup>,
    onToggleGroup: (PoiCategoryGroup) -> Unit,
    onToggleAll: () -> Unit,
    aiCount: Int = 0,
    aiOn: Boolean = false,
    onToggleAi: () -> Unit = {},
) {
    val allOn = groupsOn.size == PoiCategoryGroup.entries.size
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(IOS.systemBackground)
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        if (aiCount > 0) {
            PoiChip(label = "AI 장소 $aiCount", tone = IOS.accent, isOn = aiOn, onClick = onToggleAi)
        }
        PoiChip(label = "전체", tone = null, isOn = allOn, onClick = onToggleAll)
        PoiCategoryGroup.entries.forEach { group ->
            val count = counts[group] ?: 0
            if (count > 0) {
                PoiChip(
                    label = "${group.label} $count",
                    tone = RoutePoiTone.of(group.toRoutePoiGroup()),
                    isOn = groupsOn.contains(group),
                    onClick = { onToggleGroup(group) },
                )
            }
        }
    }
}

@Composable
private fun PoiChip(
    label: String,
    tone: Color?,
    isOn: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (isOn) IOS.accent.copy(alpha = 0.14f) else IOS.systemGray6)
                .border(1.dp, if (isOn) IOS.accent.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        tone?.let { Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(it)) }
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isOn) IOS.label else IOS.secondaryLabel,
        )
    }
}

/**
 * 주변 편의시설 점 — 갈래 색 원. iOS `PinoPin.guideDot`처럼 업종 글리프까지는
 * 넣지 않았다(SF Symbol 을 Material 아이콘으로 낱낱이 맞추는 일은 다음 단계) —
 * 색만으로 네 갈래를 가른다, iOS 도 처음엔 이 모양이었다(`RoutePoiTone.swift`).
 */
@Composable
fun AmbientPoiPins(
    map: NaverMap?,
    pois: List<PoiSummary>,
    onTap: (PoiSummary) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    DisposableEffect(map, pois) {
        val metrics = context.resources.displayMetrics
        val markers =
            pois.map { poi ->
                Marker().apply {
                    position =
                        com.naver.maps.geometry
                            .LatLng(poi.latitude, poi.longitude)
                    icon = AmbientDotImage.of(poi.categoryGroup, metrics)
                    captionText = poi.name
                    captionMinZoom = 15.0
                    setOnClickListener {
                        onTap(poi)
                        true
                    }
                    this.map = map
                    // 코스 번호 핀(기본 zIndex 0)보다 아래에 깐다 — 배경은 배경답게
                    // 조용해야 한다(iOS 주석과 같은 뜻, `RouteMapAmbient.swift`).
                    zIndex = -1
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

/**
 * 챗봇이 찾아 준 「AI 장소」 점 — iOS `PinoPin.marker(.ai)`(작은 해태). 여기서는
 * 해태 얼굴 대신 강조색 점만 쓴다(글리프는 UI 폴리싱 단계) — 주변 편의시설 점보다
 * 커서(24dp) 눈에 먼저 띈다. 고른 곳은 [RoutePlaceCard]가 따로 보여 주므로 여기선
 * 고른 표시를 하지 않는다.
 */
@Composable
fun AiPlacePins(
    map: NaverMap?,
    places: List<GuidePlace>,
    onTap: (GuidePlace) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    DisposableEffect(map, places) {
        val metrics = context.resources.displayMetrics
        val markers =
            places.map { place ->
                Marker().apply {
                    position =
                        com.naver.maps.geometry
                            .LatLng(place.latitude, place.longitude)
                    icon = AiPlaceDotImage.of(metrics)
                    captionText = place.name
                    captionMinZoom = 14.0
                    setOnClickListener {
                        onTap(place)
                        true
                    }
                    this.map = map
                    zIndex = 1 // 편의시설 점 위, 코스 번호 핀 아래.
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

private object AiPlaceDotImage {
    private var cached: com.naver.maps.map.overlay.OverlayImage? = null

    fun of(metrics: android.util.DisplayMetrics): com.naver.maps.map.overlay.OverlayImage {
        cached?.let { return it }
        val size = 24f
        val s = metrics.density
        val bitmap =
            android.graphics.Bitmap.createBitmap((size * s).toInt(), (size * s).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(s, s)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = IOS.accent.toArgb()
                setShadowLayer(2f, 0f, 0f, android.graphics.Color.argb(90, 0, 0, 0))
            }
        val center = size / 2
        canvas.drawCircle(center, center, center - 2, paint)
        val border =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
            }
        canvas.drawCircle(center, center, center - 2, border)
        val overlay =
            com.naver.maps.map.overlay.OverlayImage
                .fromBitmap(bitmap)
        cached = overlay
        return overlay
    }
}

/** 갈래 색 점 비트맵. 번호 핀(`PinImage`)과 같은 이유로 캐시한다 — 카메라가 멈출 때마다 새로 구우면 버벅인다. */
private object AmbientDotImage {
    private val cache = mutableMapOf<PoiCategoryGroup, com.naver.maps.map.overlay.OverlayImage>()

    fun of(
        group: PoiCategoryGroup,
        metrics: android.util.DisplayMetrics,
    ): com.naver.maps.map.overlay.OverlayImage {
        cache[group]?.let { return it }
        val size = 20f
        val s = metrics.density
        val bitmap =
            android.graphics.Bitmap.createBitmap((size * s).toInt(), (size * s).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(s, s)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = RoutePoiTone.of(group.toRoutePoiGroup()).toArgb()
                setShadowLayer(1.8f, 0f, 0f, android.graphics.Color.argb(76, 0, 0, 0))
            }
        val center = size / 2
        canvas.drawCircle(center, center, center - 2, paint)
        val border =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
            }
        canvas.drawCircle(center, center, center - 2, border)
        val overlay =
            com.naver.maps.map.overlay.OverlayImage
                .fromBitmap(bitmap)
        cache[group] = overlay
        return overlay
    }
}

/**
 * 발자취 **보기** 토글. iOS `RouteEditorControls.footprintButton` — 여행 중에만 나온다
 * (현위치 버튼 아래). 색은 발자국 아이콘과 같은 황금색(iOS `PinoPin.footprint()`).
 */
@Composable
fun RouteFootprintButton(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gold = Color(0xFFD9A621)
    Box(
        modifier =
            modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f))
                .clip(CircleShape)
                .background(if (on) gold else IOS.systemBackground)
                .border(1.dp, if (on) Color.Transparent else IOS.systemGray4, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("👣", fontSize = 16.sp)
    }
}

/**
 * 발자취 점 — iOS `renderFootprints`(황금 반투명 점, 35m 안은 솎는다). 방향에 따라
 * 회전한 신발 자국 아이콘까지는 넣지 않았다 — 색·간격만 옮겼다, 회전 계산은 다음 단계.
 */
@Composable
fun FootprintTrail(
    map: NaverMap?,
    points: List<FootprintPoint>,
) {
    if (map == null || points.isEmpty()) return
    val context = LocalContext.current
    DisposableEffect(map, points) {
        val minMeters = 35.0
        val thinned = mutableListOf<FootprintPoint>()
        for (point in points) {
            val last = thinned.lastOrNull()
            if (last != null) {
                val meters =
                    com.mz2az.scenetrip.data
                        .haversineKm(last.latitude, last.longitude, point.latitude, point.longitude) * 1000
                if (meters < minMeters) continue
            }
            thinned.add(point)
        }
        val markers =
            thinned.map { point ->
                Marker().apply {
                    position =
                        com.naver.maps.geometry
                            .LatLng(point.latitude, point.longitude)
                    icon = FootprintDotImage.of(context.resources.displayMetrics)
                    zIndex = -2
                    isHideCollidedMarkers = false
                    this.map = map
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

private object FootprintDotImage {
    private var cached: com.naver.maps.map.overlay.OverlayImage? = null

    // PinImage 와 같은 이유로 dp 로 굽는다 — 밀도를 안 곱하면 고밀도 화면에서
    // 점이 몇 픽셀짜리 먼지가 된다(실측).
    fun of(metrics: android.util.DisplayMetrics): com.naver.maps.map.overlay.OverlayImage {
        cached?.let { return it }
        val sizeDp = 10f
        val s = metrics.density
        val size = (sizeDp * s).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(178, 217, 166, 33)
            }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - s, paint)
        val overlay =
            com.naver.maps.map.overlay.OverlayImage
                .fromBitmap(bmp)
        cached = overlay
        return overlay
    }
}

/**
 * 장바구니에서 담기. 검색 탭의 장바구니(같은 기기 id)를 그대로 이어받는다.
 * 비었으면 [store]의 인기 장소(iOS `RouteStore.cartSample` — `places.prefix(6)`)로
 * 대신 채운다 — 빈 화면보다 무엇이든 있는 편이 다음 행동을 부른다는 게 iOS 결정.
 * **목 장소를 쓰면 안 된다** — 실제 서버 장소라야 코스에 담아도 외래키 위반이
 * 안 난다.
 */
@Composable
private fun RouteCartSheetBody(
    cart: CartStore,
    store: RouteStore,
    taken: Set<Long>,
    onPreview: (List<PlaceSummary>) -> Unit,
    onPick: (List<PlaceSummary>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val cartPlaces = cart.items.mapNotNull { it.toPlaceSummary() }
    val isSample = cartPlaces.isEmpty()
    LaunchedEffect(isSample) { if (isSample) store.loadPlaces() }
    val places = if (isSample) store.places.take(6) else cartPlaces

    // iOS `RouteCartSheet`: 툴바(닫기 · 장바구니에서 담기 · 담기 N), 구분선 있는 목록, `.regularMaterial` 바탕.
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        Box(modifier = Modifier.background(IOSSheetMaterial)) {
            IOSSheetToolbar(
                title = "장바구니에서 담기",
                leading = "닫기",
                onLeading = onDismiss,
                trailing = "담기 ${picked.size}",
                trailingEnabled = picked.isNotEmpty(),
                onTrailing = {
                    onPick(places.filter { picked.contains(it.id) })
                    onDismiss()
                },
            )
        }
        if (isSample) {
            Text(
                "장바구니가 비어 인기 장소를 보여 줍니다",
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            IOSListDivider(start = 16.dp)
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = sheetListBottom()) {
            items(places, key = { it.id }) { place ->
                val isTaken = taken.contains(place.id)
                val isPicked = picked.contains(place.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isTaken) {
                                picked = if (isPicked) picked - place.id else picked + place.id
                                onPreview(places.filter { picked.contains(it.id) || it.id == place.id })
                            }.padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        Text(place.address ?: place.type ?: "", fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1)
                    }
                    // iOS: 담긴 곳은 옅은 체크, 고른 곳은 파란 체크, 아니면 회색 `plus.circle`.
                    if (isTaken || isPicked) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = if (isTaken) "담김" else "선택",
                            tint = if (isTaken) IOS.accent.copy(alpha = 0.45f) else IOS.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        CircleSignIcon(plus = true, tint = IOS.secondaryLabel, modifier = Modifier.size(17.dp))
                    }
                }
                IOSListDivider(start = 72.dp)
            }
        }
    }
}

private fun CartItem.toPlaceSummary(): PlaceSummary? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    return PlaceSummary(id = placeId, name = name, type = null, address = address, latitude = lat, longitude = lng, imageUrl = imageUrl)
}

/** 지도를 눌러 찍은 자리에 이름과 갈래만 붙인다. */
@Composable
private fun RoutePinSheetBody(
    pin: RoutePin,
    onDone: (name: String, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PIN_CATEGORIES[0]) }

    // iOS `RoutePinSheet`: `Form` — 회색 바탕 위 묶음 카드(이름 / 갈래 / 좌표), 갈래는 체크 표시로 고른다.
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        IOSSheetToolbar(
            title = "이 자리에 추가",
            leading = "취소",
            onLeading = onDismiss,
            trailing = "추가",
            onTrailing = {
                onDone(name.ifBlank { "이름 없는 장소" }, category)
                onDismiss()
            },
        )
        LazyColumn(contentPadding = sheetListBottom()) {
            item {
                FormSection("이름") {
                    Box(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("예: 오늘 묵을 숙소", fontSize = 17.sp, color = IOS.tertiaryLabel)
                        androidx.compose.foundation.text.BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = IOS.body.copy(color = IOS.label),
                            cursorBrush =
                                androidx.compose.ui.graphics
                                    .SolidColor(IOS.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                FormSection("갈래") {
                    PIN_CATEGORIES.forEachIndexed { index, each ->
                        if (index > 0) IOSListDivider(start = 16.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .clickable { category = each }
                                    .padding(horizontal = 16.dp),
                        ) {
                            Text(each, fontSize = 17.sp, color = IOS.label, modifier = Modifier.weight(1f))
                            if (category == each) CheckmarkIcon(IOS.accent, Modifier.size(15.dp))
                        }
                    }
                }
            }
            item {
                FormSection(null) {
                    Text(
                        "위도 %.5f · 경도 %.5f".format(pin.latitude, pin.longitude),
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** iOS `Form` 의 한 `Section` — 회색 머리글(13) + 모서리 둥근 흰 카드. */
@Composable
private fun FormSection(
    title: String?,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
        if (title != null) {
            // iOS 26 의 `Form` 머리글은 본문과 거의 같은 크기(약 17)의 굵은 회색이다(12차 실측: 13 은 작았다).
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        // 흰(재질) 바탕 위 **회색 카드** — Android 는 회색 바탕 위 흰 카드로 톤이 반대였다.
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(IOS.systemGray6)) {
            content()
        }
    }
}

private val PIN_CATEGORIES = listOf("숙소", "음식점·카페", "명소·자연", "거리·다리", "건물·시설")

/** 체류 시간 고르기 — iOS `RouteStaySheet`: 제목만 있는 목록(취소 단추 없음), 고른 값에 체크. */
@Composable
private fun RouteStaySheetBody(
    stop: RouteStop,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
            Text("얼마나 머무를까요?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        }
        LazyColumn(contentPadding = sheetListBottom()) {
            items(RouteStop.STAY_OPTIONS) { minutes ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(minutes)
                                onDismiss()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(RouteFormat.minutes(minutes), fontSize = 17.sp, color = IOS.label)
                    Spacer(Modifier.weight(1f))
                    if (minutes == stop.stayMinutes) CheckmarkIcon(IOS.accent, Modifier.size(15.dp))
                }
                IOSListDivider(start = 16.dp)
            }
        }
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun RouteCartSheet(
    cart: CartStore,
    store: RouteStore,
    taken: Set<Long>,
    onPreview: (List<PlaceSummary>) -> Unit,
    onPick: (List<PlaceSummary>) -> Unit,
    onDismiss: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        RouteCartSheetBody(cart = cart, store = store, taken = taken, onPreview = onPreview, onPick = onPick, onDismiss = onDismiss)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun RoutePinSheet(
    pin: RoutePin,
    onDone: (name: String, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), onDismiss = onDismiss) {
        RoutePinSheetBody(pin = pin, onDone = onDone, onDismiss = onDismiss)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun RouteStaySheet(
    stop: RouteStop,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), onDismiss = onDismiss) {
        RouteStaySheetBody(stop = stop, onPick = onPick, onDismiss = onDismiss)
    }
}
