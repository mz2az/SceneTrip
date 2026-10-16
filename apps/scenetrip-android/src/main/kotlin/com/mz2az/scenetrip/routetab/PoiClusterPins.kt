package com.mz2az.scenetrip.routetab

import android.graphics.PointF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.searchtab.regionSource
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker

/** 확대하면 묶음이 풀리고, 같은 좌표나 최대 확대에서는 구성원 목록을 연다. */
@Composable
fun PoiClusterPins(
    map: NaverMap?,
    places: List<GuidePlace>,
    zoom: Double,
    pickedId: Long?,
    onTap: (GuidePlace) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    val select by rememberUpdatedState(onTap)
    val groups = remember(places, zoom, pickedId) { PoiClusters.make(places, zoom, pickedId) }
    var opened by remember { mutableStateOf<PoiClusters.Cluster?>(null) }
    DisposableEffect(map, groups, pickedId, AppLanguage.current) {
        val images = PoiPinImage(context)
        val markers =
            groups.map { cluster ->
                clusterMarker(cluster, map, images, pickedId, select) {
                    if (cluster.opensList(map.cameraPosition.zoom, map.maxZoom)) {
                        opened = cluster
                    } else {
                        // 눌러 들어간 범위는 사용자가 고른 영역이다 — 내 위치에서 왔다는 표시를 풀어 위치 동의를 묻지 않게 한다(MZ2AZ-472).
                        map.regionSource.source = CoordinateSource.USER_SELECTED
                        map.moveCamera(
                            CameraUpdate
                                .scrollAndZoomTo(
                                    LatLng(cluster.latitude, cluster.longitude),
                                    (map.cameraPosition.zoom + 2).coerceAtMost(map.maxZoom),
                                ).animate(CameraAnimation.Easing),
                        )
                    }
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
    opened?.let { cluster ->
        PoiClusterList(cluster.places, onClose = { opened = null }, onPick = {
            opened = null
            select(it)
        })
    }
}

private fun clusterMarker(
    cluster: PoiClusters.Cluster,
    map: NaverMap,
    images: PoiPinImage,
    pickedId: Long?,
    onPick: (GuidePlace) -> Unit,
    onOpen: () -> Unit,
): Marker =
    Marker(LatLng(cluster.latitude, cluster.longitude)).apply {
        if (cluster.places.size == 1) {
            val place = cluster.places.single()
            val picked = place.id == pickedId
            icon = if (picked) images.mascot(picked = true) else images.dot(place)
            anchor = PointF(0.5f, if (picked) 1f else 0.5f)
            captionText = place.label().title
            captionMinZoom = if (picked) 0.0 else 18.0
            isHideCollidedCaptions = !picked
            zIndex = if (picked) 30 else 3
            setOnClickListener {
                onPick(place)
                true
            }
        } else {
            icon = images.cluster(cluster.places.size)
            anchor = PointF(0.5f, 0.5f)
            zIndex = 4
            captionText = tr("편의시설 %d곳").format(cluster.places.size)
            captionMinZoom = 17.0
            setOnClickListener {
                onOpen()
                true
            }
        }
        this.map = map
    }

@Composable
private fun PoiClusterList(
    places: List<GuidePlace>,
    onClose: () -> Unit,
    onPick: (GuidePlace) -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose) {
        Column {
            IOSSheetToolbar(title = tr("주변 편의시설"), trailing = tr("닫기"), onTrailing = onClose)
            LazyColumn(contentPadding = sheetListBottom()) {
                item {
                    Text(
                        tr("현재 지도에서 받은 결과 중 %d곳").format(places.size),
                        color = IOS.secondaryLabel,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(places, key = { it.id }) { place ->
                    PoiClusterRow(place, onPick)
                    IOSListDivider(start = 16.dp)
                }
                item {
                    Text(
                        tr("지역 전체 개수가 아니에요. 장소를 골라 정보를 확인하세요."),
                        color = IOS.secondaryLabel,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PoiClusterRow(
    place: GuidePlace,
    onPick: (GuidePlace) -> Unit,
) {
    val label = place.label()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("poi-cluster-place-${place.id}")
                .clickable { onPick(place) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PoiGlyphIcon(place.categoryGroup, place.category)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            label.reading?.let { Text(it, fontSize = 15.sp, color = IOS.secondaryLabel) }
            label.category?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel) }
            label.address?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel) }
        }
    }
}
