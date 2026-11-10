package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.appearIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 엔터 뒤 — 검색 결과를 지도에 펼친다 (MZ2AZ-547 M7, iOS `MapSearchResultsView`, 웹 `protoPoi.tsx` `ResultsView`).
 *
 * 위에 검색어 알약(누르면 고쳐 쓰기) + ✕, 지도를 옮긴 뒤에만 「이 지역에서 다시 찾기」, 아래 서랍(42% ↔ 78%)에
 * 찾은 가게·장소. 촬영지는 찾지 않는다(2026-11-10 사용자 요청) — 「촬영지 N」 탭·사진 핀·일차 고르기 창이 없다.
 * 줄이나 지도 점을 누르면 그곳으로 가서 정보 카드(「코스에 담기」). 지도 점은 [MapSearchDots] 가 그린다.
 */
@Composable
internal fun MapSearchResultsView(
    model: MapSearchResultsModel,
    viewport: MapViewport?,
    onPick: (PoiSummary) -> Unit,
    onEdit: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = model.state ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val drawer = rememberResultsDrawer(maxHeight.value, state.tall) { tall -> model.update { it.copy(tall = tall) } }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
        ) {
            QueryPill(state, onEdit = { onEdit(state.query) }, onExit = onExit)
            if (state.showsRefresh) MapRefreshPill { model.searchHere(viewport) }
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    // 0.08초 뒤 6dp 내려오며 0.22초에 나타난다(M8, 웹 `.proto-search-results`).
                    .appearIn()
                    .fillMaxWidth()
                    .height(drawer.height.value.dp)
                    .shadow(8.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(IOS.systemGray6),
        ) {
            val handleLabel = if (state.tall) tr("목록 줄이기") else tr("목록 키우기")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .then(drawer.dragHandle)
                        .semantics { contentDescription = handleLabel }
                        .clickable(role = Role.Button) { drawer.toggle() },
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IOS.systemGray3),
                )
            }
            when (val body = state.body) {
                MapSearchResults.Body.Loading -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = IOS.secondaryLabel, modifier = Modifier.size(22.dp))
                    }
                }

                MapSearchResults.Body.Failed -> {
                    Text(
                        tr("찾지 못했어요. 잠시 후 다시 해 주세요"),
                        fontSize = 14.sp,
                        color = IOS.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }

                is MapSearchResults.Body.Empty -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp),
                    ) {
                        Text(MapSearchText.empty(body.empty), fontSize = 14.sp, color = IOS.secondaryLabel)
                        if (body.offerEverywhere) {
                            Text(
                                tr("이 지역 밖에서도 찾기"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IOS.homePurple,
                                modifier =
                                    Modifier
                                        .heightIn(min = 40.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(IOS.systemBackground)
                                        .clickable(role = Role.Button) { model.searchEverywhere() }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                is MapSearchResults.Body.Shops -> {
                    LazyColumn(
                        modifier =
                            Modifier
                                .nestedScroll(drawer.listConnection)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 4.dp, bottom = 20.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(IOS.systemBackground),
                    ) {
                        itemsIndexed(body.shops, key = { _, place -> place.id }) { index, place ->
                            Column {
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = IOS.separator,
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(start = MapShopRowInset),
                                    )
                                }
                                MapShopRow(place, Modifier.clickable { onPick(place) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 결과 서랍의 높이와 끌기 (MZ2AZ-547 §3). 판단은 [MapSearchDrawerRules] 가 하고, 여기는 손가락·애니메이션만 잇는다.
 * 높이는 dp. 손잡이를 끌거나, 목록이 맨 위일 때 아래로 끌면 서랍이 따라오고, 손을 떼면 42%·78% 중 하나로 0.28초에 붙는다.
 */
@Stable
internal class ResultsDrawer(
    private val scope: CoroutineScope,
    private val density: Float,
    initial: Float,
) {
    val height = Animatable(initial)
    var mapHeight = 0f
    var tall = false
    lateinit var onSettle: (Boolean) -> Unit

    // 끄는 동안 손가락이 가리키는 (고무줄 전) 높이.
    private var raw = initial
    private var dragging = false

    // 목록에서 이어진 끌기 — 서랍을 내렸다가 같은 손으로 다시 올리면 목록보다 서랍이 먼저 받는다.
    private var pulledFromList = false

    fun snapTarget() = MapSearchDrawerRules.height(mapHeight, tall)

    fun animateTo(target: Float) {
        scope.launch { height.animateTo(target, tween(MapSearchDrawerRules.SNAP_MILLIS, easing = ProtoMotion.EaseOutSheet)) }
    }

    fun toggle() {
        settleTo(MapSearchDrawerRules.toggled(tall))
    }

    private fun settleTo(next: Boolean) {
        tall = next
        onSettle(next)
        animateTo(snapTarget())
    }

    fun start() {
        dragging = true
        raw = height.value
    }

    /** [deltaDp] — 서랍이 커지는 쪽(위)이 +. */
    fun drag(deltaDp: Float) {
        raw += deltaDp
        val next = MapSearchDrawerRules.dragged(mapHeight, raw)
        scope.launch { height.snapTo(next) }
    }

    /** [velocityDp] — 위가 + 인 dp/초. */
    fun release(velocityDp: Float) {
        dragging = false
        pulledFromList = false
        settleTo(MapSearchDrawerRules.settle(mapHeight, height.value, velocityDp, tall))
    }

    val isDragging get() = dragging

    /** 손잡이에 붙이는 끌기. */
    val dragHandle: Modifier =
        Modifier.draggable(
            orientation = Orientation.Vertical,
            state = DraggableState { deltaPx -> drag(-deltaPx / density) },
            onDragStarted = { start() },
            onDragStopped = { velocityPx -> release(-velocityPx / density) },
        )

    /** 목록이 맨 위인데 아래로 끌면 남은 몫으로 서랍을 내린다. */
    val listConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 내렸던 서랍을 같은 손으로 다시 올리는 중 — 쉬는 높이에 닿을 때까지 서랍이 먼저 받는다.
                if (!pulledFromList || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                val room = snapTarget() - raw
                if (room <= 0f) return Offset.Zero
                val upDp = minOf(-available.y / density, room)
                drag(upDp)
                return Offset(0f, -upDp * density)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (!pulledFromList) {
                    pulledFromList = true
                    start()
                }
                drag(-available.y / density)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulledFromList) return Velocity.Zero
                release(-available.y / density)
                return available
            }
        }
}

@Composable
private fun rememberResultsDrawer(
    mapHeight: Float,
    tall: Boolean,
    onSettle: (Boolean) -> Unit,
): ResultsDrawer {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val drawer = remember { ResultsDrawer(scope, density, MapSearchDrawerRules.height(mapHeight, tall)) }
    drawer.onSettle = onSettle
    drawer.mapHeight = mapHeight
    // 바깥(모델)에서 높이가 바뀌거나 지도 크기가 바뀌면 같은 0.28초로 맞춘다. 끄는 중에는 손가락이 이긴다.
    LaunchedEffect(tall, mapHeight) {
        drawer.tall = tall
        if (!drawer.isDragging) drawer.animateTo(drawer.snapTarget())
    }
    return drawer
}

/** 검색어 알약 — 누르면 고쳐 쓰기, ✕ 는 끝내기. */
@Composable
private fun QueryPill(
    state: MapSearchResults,
    onEdit: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .shadow(10.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(IOS.systemBackground)
                .padding(start = 14.dp, end = 6.dp),
    ) {
        MagnifierIcon(IOS.secondaryLabel, Modifier.size(15.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onEdit),
        ) {
            Text(
                "“${state.query}”",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            state.count?.let { Text(tr("%d곳").format(it), fontSize = 14.sp, color = IOS.secondaryLabel) }
        }
        val exitLabel = tr("검색 끝내기")
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(40.dp)
                    .semantics { contentDescription = exitLabel }
                    .clickable(role = Role.Button, onClick = onExit),
        ) {
            Icon(Icons.Filled.Clear, contentDescription = null, tint = IOS.tertiaryLabel, modifier = Modifier.size(20.dp))
        }
    }
}
