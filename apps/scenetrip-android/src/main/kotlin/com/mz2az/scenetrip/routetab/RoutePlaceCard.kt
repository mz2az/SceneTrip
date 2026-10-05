package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 핀을 눌렀을 때 뜨는 정보 카드의 자료. iOS `RouteGuide.Card`.
 *
 * ## 우리 자료만 보여 준다 (2026-10-05, MZ2AZ-354)
 *
 * 사진·영업시간·리뷰 수·별점은 서버가 네이버 지도의 비공식 주소를 불러 가져온 것이었다
 * (ADR 0011 — 데모 한정). 밖에 내보내는 빌드에는 쓸 수 없어 걷어냈다. 더 보려면
 * 「네이버 지도에서 보기」가 **그쪽 화면으로 넘긴다** — 옮겨 담지 않는다.
 */
data class RoutePlaceCardData(
    val category: String?,
    val address: String?,
    val phone: String?,
    /** 촬영지의 우리 사진. 편의시설은 사진이 없다. */
    val images: List<String>,
    /** 「네이버 지도에서 보기」가 갈 곳. */
    val naverUrl: String?,
)

/**
 * **출처가 상세 API 를 정한다**(계약 `GuidePlaceSource`) — 편의시설은 `GET /pois/{id}`,
 * 촬영지는 `GET /places/{id}`. 둘 다 **우리 자료**다.
 *
 * 상세를 못 받아도(`null`) 카드는 선다 — 목록이 이미 준 이름·분류·주소로. 전화만 빠진다.
 * **편의시설은 상세 실패로도 카드를 못 띄우는 일이 없다** — 그 경우에만 `null`을 돌려주는
 * 것은 촬영지(`place`) 쪽뿐이다.
 */
suspend fun fetchPlaceCard(place: GuidePlace): RoutePlaceCardData? =
    withContext(Dispatchers.IO) {
        when (place.source) {
            GuidePlaceSource.place -> {
                val detail = runCatching { PlacesApi(API_BASE).getPlace(placeId = place.id) }.getOrNull() ?: return@withContext null
                RoutePlaceCardData(
                    category = detail.type ?: place.category,
                    address = detail.address ?: place.address,
                    phone = null,
                    images = detail.imageUrls?.map { it.toString() } ?: listOfNotNull(detail.imageUrl?.toString()),
                    // 촬영지는 전과 같다 — 우리가 가진 링크가 있을 때만.
                    naverUrl = detail.naverPlaceUrl?.toString(),
                )
            }

            GuidePlaceSource.poi -> {
                val detail = runCatching { PoisApi(API_BASE).getPoi(poiId = place.id) }.getOrNull()
                RoutePlaceCardData(
                    category = detail?.category ?: place.category,
                    address = detail?.address ?: place.address,
                    phone = detail?.tel,
                    images = emptyList(),
                    naverUrl = NaverMapLink.search(detail?.name ?: place.name, near = detail?.city),
                )
            }
        }
    }

/**
 * 핀을 눌렀을 때 뜨는 정보 카드 — iOS `RoutePlaceCard`. 분류·주소·전화를 보여 주고,
 * 더 보려면 네이버 지도로 넘긴다.
 *
 * "여기로 길찾기"(iOS `onReroute`)는 없다 — 그건 여행 중 화면의 것인데, 이 카드는
 * 편집기(계획 화면)에서만 뜬다.
 */
@Composable
fun RoutePlaceCard(
    place: GuidePlace,
    added: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var card by remember(place.id) { mutableStateOf<RoutePlaceCardData?>(null) }
    var loading by remember(place.id) { mutableStateOf(true) }
    var missing by remember(place.id) { mutableStateOf(false) }

    LaunchedEffect(place.id) {
        loading = true
        missing = false
        val result = fetchPlaceCard(place)
        card = result
        missing = result == null
        loading = false
    }

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(listOf(IOS.pinLight.copy(alpha = 0.16f), IOS.pinDeep.copy(alpha = 0.07f))),
                ).background(IOS.systemBackground)
                .border(1.dp, IOS.pinLight.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        PlaceCardHeader(
            name = place.name,
            distanceMeters = place.distanceMeters,
            naverUrl = card?.naverUrl,
            onClose = onClose,
        )

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("불러오는 중입니다", fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        } else if (missing) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("정보를 불러오지 못했습니다", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                place.category?.let {
                    Text(it, fontSize = 11.sp, color = IOS.tertiaryLabel)
                }
            }
        } else {
            card?.let { PlaceCardFound(it, added, onAdd, onRemove) }
        }
    }
}

/**
 * 이름 · 「네이버 지도에서 보기」 · 닫기.
 *
 * **한 줄에 다 들어가면 한 줄, 아니면 링크가 이름 아래로 내려간다** — iOS `RoutePlaceCard.header`
 * (`ViewThatFits`)의 그대로다. 가이드 시트 안에서 펼친 카드는 좁다 — 링크를 줄이면
 * 「View on…」으로 잘리고, 링크 폭을 지키면 이름이 두 글자씩 세 줄로 쪼개졌다(iOS 2026-10-05 실기).
 * Compose 에는 `ViewThatFits` 가 없어 `SubcomposeLayout` 으로 두 후보를 재 보고 고른다.
 */
@Composable
private fun PlaceCardHeader(
    name: String,
    distanceMeters: Int?,
    naverUrl: String?,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val vGapPx = with(density) { 6.dp.roundToPx() }

    SubcomposeLayout(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) { constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)

        val titleOneLine =
            subcompose("titleOneLine") { PlaceCardTitle(name, distanceMeters, wraps = false) }
                .map { it.measure(loose) }
        val titleWidth = titleOneLine.maxOf { it.width }
        val titleHeight = titleOneLine.maxOf { it.height }

        val trailing =
            subcompose("trailing") { PlaceCardTrailing(naverUrl, onClose) }
                .map { it.measure(loose) }
        val trailingWidth = trailing.maxOf { it.width }
        val trailingHeight = trailing.maxOf { it.height }

        if (titleWidth + gapPx + trailingWidth <= constraints.maxWidth) {
            val height = maxOf(titleHeight, trailingHeight)
            layout(constraints.maxWidth, height) {
                titleOneLine.forEach { it.placeRelative(0, 0) }
                trailing.forEach { it.placeRelative(constraints.maxWidth - trailingWidth, 0) }
            }
        } else {
            val close = subcompose("close") { PlaceCardCloseButton(onClose) }.map { it.measure(loose) }
            val closeWidth = close.maxOf { it.width }
            val closeHeight = close.maxOf { it.height }

            val titleMaxWidth = (constraints.maxWidth - gapPx - closeWidth).coerceAtLeast(0)
            val titleWrapped =
                subcompose("titleWrapped") { PlaceCardTitle(name, distanceMeters, wraps = true) }
                    .map { it.measure(Constraints(maxWidth = titleMaxWidth, maxHeight = constraints.maxHeight)) }
            val titleWrappedHeight = titleWrapped.maxOf { it.height }
            val topRowHeight = maxOf(titleWrappedHeight, closeHeight)

            val link =
                if (naverUrl != null) {
                    subcompose("link") { PlaceCardNaverLink(naverUrl) }.map { it.measure(loose) }
                } else {
                    emptyList()
                }
            val linkHeight = link.maxOfOrNull { it.height } ?: 0
            val totalHeight = topRowHeight + if (link.isNotEmpty()) vGapPx + linkHeight else 0

            layout(constraints.maxWidth, totalHeight) {
                titleWrapped.forEach { it.placeRelative(0, 0) }
                close.forEach { it.placeRelative(constraints.maxWidth - closeWidth, 0) }
                if (link.isNotEmpty()) {
                    link.forEach { it.placeRelative(0, topRowHeight + vGapPx) }
                }
            }
        }
    }
}

/** `wraps`가 아니면 한 줄 폭을 그대로 요구한다 — 헤더가 그것으로 들어가는지 잰다. */
@Composable
private fun PlaceCardTitle(
    name: String,
    distanceMeters: Int?,
    wraps: Boolean,
) {
    Column {
        Text(
            name,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.label,
            maxLines = if (wraps) Int.MAX_VALUE else 1,
        )
        distanceMeters?.let {
            Text("${it}m", fontSize = 11.sp, color = IOS.secondaryLabel)
        }
    }
}

@Composable
private fun PlaceCardTrailing(
    naverUrl: String?,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        naverUrl?.let { PlaceCardNaverLink(it) }
        PlaceCardCloseButton(onClose)
    }
}

/** **더 보려면 네이버로** — 사진·메뉴·예약·리뷰는 그쪽에 있다. 이름으로 검색한 화면을 연다. */
@Composable
private fun PlaceCardNaverLink(url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF05C759).copy(alpha = 0.12f))
                .clickable { runCatching { uriHandler.openUri(url) } }
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text("네이버 지도에서 보기", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF049A47))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = Color(0xFF049A47),
            modifier = Modifier.size(9.dp).rotate(-45f),
        )
    }
}

@Composable
private fun PlaceCardCloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.secondaryLabel, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PlaceCardFound(
    card: RoutePlaceCardData,
    added: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    if (card.images.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        ) {
            card.images.take(5).forEach { url ->
                RemoteImage(url = url, modifier = Modifier.size(width = 92.dp, height = 70.dp).clip(RoundedCornerShape(8.dp)))
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Column {
        cardRow("분류", card.category)
        cardRow("주소", card.address)
        cardRow("전화", card.phone)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (added) IOS.systemGray5 else IOS.accent)
                .clickable(onClick = if (added) onRemove else onAdd),
    ) {
        Icon(
            if (added) Icons.Filled.CheckCircle else Icons.Filled.Add,
            contentDescription = null,
            tint = if (added) IOS.secondaryLabel else Color.White,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (added) "경로에 있음 · 누르면 빼기" else "경로에 추가",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (added) IOS.secondaryLabel else Color.White,
        )
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun cardRow(
    label: String,
    value: String?,
) {
    if (value.isNullOrEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, fontSize = 11.sp, color = IOS.secondaryLabel, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 11.sp, color = IOS.label)
    }
    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(start = 14.dp), color = IOS.systemGray5)
}
