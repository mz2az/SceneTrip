package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.ErrorView
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSEmptyState
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetMaterial
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom

/**
 * 모델 없이 지도 중심(또는 내 위치) 주변 촬영지를 보고 현재 일차에 직접 담는다 (MZ2AZ-444). iOS
 * `RouteNearbySheet`를 옮긴 것이다. AI 요청·오류와 상태를 나누므로 가이드가 실패해도 이 목록은 쓸 수 있다.
 *
 * [mapAnchor]는 누른 시점의 지도 중심으로 고정된 기준이다. [deviceLocation]이 없으면 「내 위치로」는 눌리지 않고
 * 지도 중심으로 찾는다.
 *
 * TODO(MZ2AZ-472): 「내 위치로」는 지금 편집 화면이 이미 읽어 둔 기기 위치를 그대로 쓴다. 위치기반서비스
 * 동의가 Android 에 들어오면 iOS 처럼 이 버튼 앞에서 동의를 확인하고, 거절·철회 시 지도 기준만 남긴다.
 */
@Composable
fun RouteNearbySheet(
    mapAnchor: RouteGuideAnchor,
    deviceLocation: Pair<Double, Double>?,
    taken: Set<Long>,
    onAdd: (PlaceSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf(RouteNearbyState()) }
    val placesApi = remember { PlacesApi(API_BASE) }
    val search =
        remember {
            RouteNearbySearch(
                fetch = { query ->
                    apiResult { placesApi.listPlaces(bbox = query.bbox, limit = RouteNearbySearch.LIMIT) }
                        .getOrThrow()
                        .items
                        .orEmpty()
                },
                changed = { state = it },
            )
        }
    var anchor by remember { mutableStateOf<RouteGuideAnchor?>(null) }
    var requestNumber by remember { mutableStateOf(0) }

    fun request(wanted: RouteGuideAnchor) {
        anchor = wanted
        requestNumber += 1
    }
    // 열리자마자 지도 중심으로 한 번 찾는다. 요청 번호가 바뀌면 이전 조회는 취소되고 새로 시작한다.
    LaunchedEffect(requestNumber) {
        if (requestNumber == 0) request(mapAnchor) else anchor?.let { search.load(it) }
    }

    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            Box(modifier = Modifier.background(IOSSheetMaterial)) {
                IOSSheetToolbar(title = tr("주변 촬영지"), leading = tr("닫기"), onLeading = onDismiss)
            }
            Controls(
                usingMapCenter = anchor?.isMapCenter != false,
                hasDeviceLocation = deviceLocation != null,
                onMapCenter = { request(mapAnchor) },
                onDeviceLocation = { deviceLocation?.let { request(RouteGuideAnchor(it.first, it.second, isMapCenter = false)) } },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Results(
                    state = state,
                    taken = taken,
                    onAdd = onAdd,
                    onRetry = { request(anchor ?: mapAnchor) },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    usingMapCenter: Boolean,
    hasDeviceLocation: Boolean,
    onMapCenter: () -> Unit,
    onDeviceLocation: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            if (usingMapCenter) {
                tr("지도 중심 주변 촬영지입니다. AI를 사용하지 않아요.")
            } else {
                tr("내 위치 주변 촬영지입니다. AI를 사용하지 않아요.")
            },
            fontSize = 12.sp,
            color = IOS.secondaryLabel,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("지도 중심 주변"), enabled = true, onClick = onMapCenter)
            Pill(tr("내 위치로"), enabled = hasDeviceLocation, onClick = onDeviceLocation)
        }
        if (!hasDeviceLocation) {
            Text(tr("내 위치를 사용할 수 없어 지도 중심으로 찾아요."), fontSize = 11.sp, color = IOS.secondaryLabel)
        }
    }
}

@Composable
private fun Pill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(CircleShape)
                .background(IOS.tertiaryFill)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, color = IOS.accent)
    }
}

@Composable
private fun Results(
    state: RouteNearbyState,
    taken: Set<Long>,
    onAdd: (PlaceSummary) -> Unit,
    onRetry: () -> Unit,
) {
    when (state.phase) {
        RouteNearbySearch.Phase.IDLE -> {
            IOSEmptyState(title = tr("검색 기준을 선택해 주세요"), description = "") { MapPinEllipseIcon(IOS.tertiaryLabel, it) }
        }

        RouteNearbySearch.Phase.LOADING -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        RouteNearbySearch.Phase.FAILED -> {
            ErrorView(failure = state.failure, onRetry = onRetry)
        }

        RouteNearbySearch.Phase.LOADED -> {
            if (state.places.isEmpty()) {
                IOSEmptyState(
                    title = tr("주변에 촬영지가 없어요"),
                    description = tr("지도를 다른 곳으로 옮겨 다시 찾아보세요."),
                ) { MapPinEllipseIcon(IOS.tertiaryLabel, it) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
                    items(state.places, key = { it.id }) { place ->
                        NearbyRow(place = place, isTaken = taken.contains(place.id), onAdd = { onAdd(place) })
                        IOSListDivider(start = 16.dp)
                    }
                }
            }
        }
    }
}

/** iOS `List` 행: 이름 · 작품(강조색) · 주소, 오른쪽에 「담기」/「담김」. 담긴 곳은 눌리지 않는다. */
@Composable
private fun NearbyRow(
    place: PlaceSummary,
    isTaken: Boolean,
    onAdd: () -> Unit,
) {
    val label = if (isTaken) tr("%s · 코스에 담김").format(place.name) else tr("%s 코스에 담기").format(place.name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
            place.contents
                ?.firstOrNull()
                ?.title
                ?.let { Text(it, fontSize = 12.sp, color = IOS.accent) }
            place.address?.let { Text(it, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .alpha(if (isTaken) 0.4f else 1f)
                    .clip(CircleShape)
                    .background(IOS.tertiaryFill)
                    .semantics { contentDescription = label }
                    .clickable(enabled = !isTaken, onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (isTaken) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(15.dp))
            } else {
                CircleSignIcon(plus = true, tint = IOS.accent, modifier = Modifier.size(15.dp))
            }
            Text(if (isTaken) tr("담김") else tr("담기"), fontSize = 12.sp, color = IOS.accent)
        }
    }
}
