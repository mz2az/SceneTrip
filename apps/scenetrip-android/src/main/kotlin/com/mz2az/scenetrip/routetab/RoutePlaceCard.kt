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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 핀을 눌렀을 때 뜨는 정보 카드의 자료. iOS `RouteGuide.Card`. 출처(`GuidePlace.source`)가
 * 상세 API를 정한다 — 편의시설은 `GET /pois/{id}/card`(네이버, ADR 0011), 촬영지는
 * `GET /places/{id}`. 둘은 다른 표라 숫자 id만으로는 못 가른다.
 */
data class RoutePlaceCardData(
    val found: Boolean,
    val name: String,
    val category: String?,
    val address: String?,
    val hours: String?,
    val phone: String?,
    val reviewCount: Int?,
    val blogReviews: Int?,
    val score: Double?,
    val images: List<String>,
    val naverUrl: String?,
    val why: String?,
)

suspend fun fetchPlaceCard(place: GuidePlace): RoutePlaceCardData? =
    withContext(Dispatchers.IO) {
        runCatching {
            when (place.source) {
                GuidePlaceSource.place -> {
                    val detail = PlacesApi(API_BASE).getPlace(placeId = place.id)
                    RoutePlaceCardData(
                        found = true,
                        name = detail.name,
                        category = detail.type ?: place.category,
                        address = detail.address ?: place.address,
                        hours = null,
                        phone = null,
                        reviewCount = null,
                        blogReviews = null,
                        score = null,
                        images = detail.imageUrls?.map { it.toString() } ?: listOfNotNull(detail.imageUrl?.toString()),
                        naverUrl = detail.naverPlaceUrl?.toString(),
                        why = null,
                    )
                }

                GuidePlaceSource.poi -> {
                    val card = PoisApi(API_BASE).getPoiCard(poiId = place.id)
                    RoutePlaceCardData(
                        found = card.found ?: false,
                        name = card.name ?: place.name,
                        category = card.category ?: place.category,
                        address = card.address ?: place.address,
                        hours = card.hours,
                        phone = card.phone,
                        reviewCount = card.reviewCount,
                        blogReviews = card.blogReviews,
                        score = card.score,
                        images = card.images.orEmpty().map { it.toString() },
                        naverUrl = card.naverUrl?.toString(),
                        why = if (card.pending == true) tr("아직 채우는 중이에요 — 잠시 뒤 다시 열어 주세요") else card.why,
                    )
                }
            }
        }.getOrNull()
    }

/**
 * 핀을 눌렀을 때 뜨는 정보 카드 — iOS `RoutePlaceCard`. 사진·영업시간·리뷰·별점을
 * 보여 주고, 더 보려면 네이버 앱(브라우저)으로 넘긴다. 우리 자료에 있어도 네이버에
 * 없는 가게면 "왜 없는지"를 적는다 — 빈 카드를 띄우지 않는다.
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
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(place.id) {
        loading = true
        card = fetchPlaceCard(place)
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
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(place.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                place.distanceMeters?.let {
                    Text("${it}m", fontSize = 11.sp, color = IOS.secondaryLabel)
                }
            }
            card?.naverUrl?.let { url ->
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
                    Text(tr("네이버 더보기"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF049A47))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF049A47),
                        modifier = Modifier.size(9.dp).rotate(-45f),
                    )
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = tr("닫기"),
                tint = IOS.secondaryLabel,
                modifier = Modifier.padding(start = 8.dp).size(16.dp).clickable(onClick = onClose),
            )
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("네이버에서 찾는 중입니다"), fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        } else {
            val found = card
            if (found != null && found.found) {
                PlaceCardFound(found, added, onAdd, onRemove)
            } else {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(tr("네이버에서 찾지 못했습니다"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                    Text(
                        found?.why ?: tr("우리 자료(TMAP)에는 있지만 네이버에 없는 가게일 수 있습니다."),
                        fontSize = 11.sp,
                        color = IOS.secondaryLabel,
                    )
                }
            }
        }
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
        cardRow(tr("분류"), card.category)
        cardRow(tr("영업"), card.hours)
        cardRow(tr("주소"), card.address)
        cardRow(tr("전화"), card.phone)
        cardRow(tr("방문자 리뷰"), card.reviewCount?.let { tr("%d건").format(it) })
        cardRow(tr("블로그 리뷰"), card.blogReviews?.let { tr("%d건").format(it) })
        cardRow(tr("별점"), card.score?.let { "%.2f".format(it) })
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
            if (added) tr("경로에 있음 · 누르면 빼기") else tr("경로에 추가"),
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
