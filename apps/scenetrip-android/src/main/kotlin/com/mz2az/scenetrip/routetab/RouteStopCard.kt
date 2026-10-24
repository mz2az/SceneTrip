package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.analytics.UsageEvents
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.UnavailableItem
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.RatingLine
import com.mz2az.scenetrip.reviews.ReviewSubject
import com.mz2az.scenetrip.reviews.ReviewsSheet
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.NorthArrowIcon
import com.mz2az.scenetrip.ui.UnavailableItemNotice

/**
 * 코스의 번호 핀을 눌렀을 때 뜨는 카드 — **줄의 갈래가 고른다** (MZ2AZ-380, MZ2AZ-478 E8, iOS `RouteCourseStopCard`).
 *
 * 편의시설 줄은 편의시설 카드([RoutePlaceCard] — 분류·주소·전화·네이버 버튼·리뷰, `GET /pois/{poiId}`)이고, 촬영지와 개인 핀은 성지
 * 카드([RouteStopCard])다. 편의시설에는 장면 설명이 없고 성지 카드에는 가게의 전화·네이버 링크가 없다 — 한 카드로 둘을 다 그리지 않는다.
 * 이미 코스에 있는 곳이라 「경로에 추가」·「경로에 있음 · 누르면 빼기」 단추는 없다. 여행 중이면 「여기로 길찾기」가 붙는다.
 */
@Composable
fun RouteCourseStopCard(
    stop: RouteStop,
    onReroute: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val poi = if (stop.poiId != null && stop.canNavigate) stop.cardPlace() else null
    if (poi != null) {
        RoutePlaceCard(
            place = poi,
            added = true,
            onAdd = null,
            onRemove = null,
            onReroute = onReroute,
            onClose = onClose,
            modifier = modifier,
            unavailable = stop.unavailable,
        )
    } else {
        RouteStopCard(stop = stop, onReroute = onReroute, onClose = onClose, modifier = modifier)
    }
}

/**
 * 길찾기 지도에서 **성지(코스 번호 핀)를 눌렀을 때** 뜨는 카드 (iOS `RouteStopCard`). 편의시설 카드와 하는 일이 다르다 — 저쪽은 가게의
 * 분류·주소·전화를 보이고 네이버 지도로 넘기고, 이쪽은 **성지의 이야기**(작품별 장면 설명, 작품검색 탭과 같은 상세 API)를 보여 준다.
 * 장바구니 담기는 없다 — 이미 코스에 있는 곳이다. 대신 「여기로 길찾기」로 목적지를 이곳으로 바꾼다.
 */
@Composable
fun RouteStopCard(
    stop: RouteStop,
    onReroute: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember(stop.id) { mutableStateOf<PlaceDetail?>(null) }
    var loading by remember(stop.id) { mutableStateOf(true) }
    var reviewing by remember(stop.id) { mutableStateOf(false) }
    var revision by remember(stop.id) { mutableStateOf(0) }
    val placeId = stop.savablePlaceId
    val notice = stop.unavailable ?: if (detail?.delisted == true) UnavailableItem.PLACE else null

    LaunchedEffect(stop.id, revision) {
        if (revision == 0) UsageEvents.opened(placeId?.let { ReviewSubject(it, stop.place.name) })
        loading = revision == 0
        // 직접 찍은 핀은 우리 표에 없다 — 물어볼 곳이 없다.
        if (placeId != null) {
            apiResult { PlacesApi(API_BASE).getPlace(placeId = placeId) }.getOrNull()?.let { detail = it }
        }
        loading = false
    }

    Column(
        modifier =
            modifier
                // 코스 핀·경로선과 같은 보라 계열 — 「우리 것」의 색이다. 편의시설 카드의 연보라와 같은 결이라 흰 시트 위에서도 구별된다.
                .shadow(
                    12.dp,
                    RoundedCornerShape(16.dp),
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.22f),
                ).clip(RoundedCornerShape(16.dp))
                .background(IOS.systemBackground)
                .background(Brush.linearGradient(listOf(IOS.pinLight.copy(alpha = 0.16f), IOS.pinDeep.copy(alpha = 0.07f))))
                .border(1.dp, IOS.pinLight.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        StopCardHeader(stop, onClose)
        if (notice != null) UnavailableItemNotice(notice, Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp))

        // 별점 한 줄 — 직접 찍은 핀에는 리뷰가 없다.
        if (placeId != null) {
            RatingLine(detail?.rating, { reviewing = true }, Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp))
        }

        val scenes = detail?.scenes.orEmpty()
        when {
            stop.unavailable == UnavailableItem.CLOSED_POI -> {
                Text(
                    listOfNotNull(stop.label().category, stop.label().address).joinToString(" · "),
                    style = IOS.caption,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }

            loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(tr("장면 설명을 찾는 중입니다"), style = IOS.caption, color = IOS.secondaryLabel)
                }
            }

            scenes.isNotEmpty() -> {
                // 작품검색 탭이 보여 주는 그 장면 설명이다. 둘까지만 — 카드는 요약이고 전문은 작품검색 탭이 맡는다.
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                ) {
                    scenes.take(2).forEach { scene ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(scene.contentTitle, style = IOS.caption.copy(fontWeight = FontWeight.Bold), color = IOS.accent)
                            Text(
                                scene.sceneDescription ?: tr("장면 설명이 아직 없습니다"),
                                style = IOS.caption,
                                color = if (scene.sceneDescription == null) IOS.secondaryLabel else IOS.label,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            else -> {
                Text(
                    if (placeId != null) tr("장면 정보가 아직 없습니다") else tr("직접 찍은 곳입니다"),
                    style = IOS.caption,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
        }

        if (onReroute != null && notice == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IOS.accent)
                        .clickable(onClick = onReroute),
            ) {
                NorthArrowIcon(Color.White, Modifier.size(12.dp))
                Text(tr("여기로 길찾기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
    if (reviewing && placeId != null) {
        ReviewsSheet(ReviewSubject(placeId, stop.place.name), onClose = { reviewing = false }, onChanged = { revision += 1 })
    }
}

@Composable
private fun StopCardHeader(
    stop: RouteStop,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(stop.label().title, style = IOS.headline, color = IOS.label)
            Text(
                listOfNotNull(stop.label().category, stop.label().address).joinToString(" · "),
                style = IOS.caption,
                color = IOS.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp).clickable(onClick = onClose)) {
            Icon(Icons.Filled.Close, contentDescription = tr("닫기"), tint = IOS.secondaryLabel, modifier = Modifier.size(16.dp))
        }
    }
}
