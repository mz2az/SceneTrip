package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.IOS

/**
 * 드릴다운 2단 — 작품 상세.
 *
 * iOS `SearchTab/ContentDetailView.swift` 를 옮긴 것이다. 목록에서 넘어온
 * `ContentSummary` 로 먼저 그리고, 상세(`GET /contents/{id}`)가 오면 채운다.
 * 상세에만 있는 것이 줄거리·출연진이라 그 줄은 도착 후에 뜬다.
 *
 * **별칭은 빼 둔다.** 한 줄에 담기지 않아 늘 뒤가 잘렸고, 잘린 별칭은 아무 값도
 * 하지 않는다 — 사용자가 그 이름으로 찾아 들어온 뒤라 이미 아는 정보다.
 */
@Composable
fun ContentDetailView(
    summary: ContentSummary,
    places: List<PlaceSummary>,
    loading: Boolean,
    saved: (Long) -> Boolean,
    detailOf: suspend (Long) -> ContentDetail?,
    onBack: () -> Unit,
    onSelectPlace: (PlaceSummary) -> Unit,
    onSave: (PlaceSummary) -> Unit,
) {
    var detail by remember(summary.id) { mutableStateOf<ContentDetail?>(null) }
    LaunchedEffect(summary.id) { detail = detailOf(summary.id) }

    // "출연: 이름, 이름, …" — 넷까지만. iOS 와 같은 문구·같은 개수다.
    val castLine =
        detail?.cast.orEmpty().map { it.name }.let {
            if (it.isEmpty()) "" else "출연: " + it.take(4).joinToString(", ")
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailHeader(title = summary.title, onBack = onBack)
        LazyColumn {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 12.dp),
                ) {
                    RemoteImage(
                        url = summary.posterUrl?.toString(),
                        modifier =
                            Modifier
                                .size(width = 92.dp, height = 124.dp)
                                .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(summary.meta, style = IOS.subheadline, color = IOS.secondaryLabel)
                        if (castLine.isNotEmpty()) {
                            Text(castLine, style = IOS.caption, color = IOS.secondaryLabel)
                        }
                    }
                }
                detail?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = IOS.gutter),
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 14.dp),
                ) {
                    Text("촬영지", style = IOS.headline, color = IOS.label)
                    Text("${summary.placeCount}곳", style = IOS.caption, color = IOS.secondaryLabel)
                }
            }

            if (loading || places.isEmpty()) {
                item {
                    Text(
                        text = if (loading) "" else "촬영지를 불러오지 못했습니다",
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    )
                }
            } else {
                // 지도 핀과 같은 배열이므로 행 번호가 곧 핀 번호다.
                itemsIndexed(places) { index, place ->
                    PlaceRow(
                        place = place,
                        number = index + 1,
                        saved = saved(place.id),
                        onTap = { onSelectPlace(place) },
                        onAdd = { onSave(place) },
                    )
                    Box(
                        Modifier
                            .padding(start = IOS.gutter)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(IOS.separator),
                    )
                }
            }
        }
    }
}

/**
 * 드릴다운 3단 — 촬영지 상세.
 *
 * iOS `SearchTab/PlaceDetailView.swift` 를 옮긴 것이다. 상세에만 있는 것이
 * **작품별 장면**(`scenes`)이라 그 절은 도착 후에 뜬다.
 *
 * **담기 버튼은 두 개가 아니다.** 장바구니에 담기는 단위는 장소이므로 이 화면의
 * 저장 버튼과 목록 행의 `+` 가 같은 것을 부른다.
 */
@Composable
fun PlaceDetailView(
    summary: PlaceSummary,
    saved: Boolean,
    detailOf: suspend (Long) -> PlaceDetail?,
    onBack: () -> Unit,
    onToggleSave: () -> Unit,
) {
    var detail by remember(summary.id) { mutableStateOf<PlaceDetail?>(null) }
    val context = LocalContext.current
    LaunchedEffect(summary.id) { detail = detailOf(summary.id) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailHeader(title = summary.name, subtitle = summary.address.orEmpty(), onBack = onBack)
        LazyColumn {
            item {
                RemoteImage(
                    url = (detail?.imageUrl ?: summary.imageUrl)?.toString(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(horizontal = IOS.gutter)
                            .clip(RoundedCornerShape(10.dp)),
                )
                summary.type?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        modifier =
                            Modifier
                                .padding(horizontal = IOS.gutter, vertical = 12.dp)
                                .clip(CircleShape)
                                .background(IOS.systemGray6)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // 담긴 상태에서 다시 누르면 뺀다 — 목록 행과 같은 규칙이다.
                //
                // 두 버튼이 한 줄이다. 담기는 계약이 적어 둔 세 경로 중 하나이고,
                // 네이버 지도는 외부 앱/브라우저로 넘긴다 — 길찾기와 영업정보는
                // 우리가 만들 것이 아니다.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(IOS.accent)
                                .clickable(onClick = onToggleSave)
                                .padding(vertical = 12.dp),
                    ) {
                        Text(
                            text = if (saved) "담김 · 누르면 빼기" else "장바구니에 담기",
                            style = IOS.subheadlineSemibold,
                            color = IOS.systemBackground,
                        )
                    }
                    val naver = detail?.naverPlaceUrl?.toString()
                    if (naver != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(IOS.systemGray6)
                                    .clickable { openUrl(context, naver) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "네이버 지도",
                                style = IOS.subheadlineSemibold,
                                color = IOS.accent,
                            )
                        }
                    }
                }
            }

            val scenes = detail?.scenes.orEmpty()
            if (scenes.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 16.dp),
                    ) {
                        Text("이 장소의 장면", style = IOS.headline, color = IOS.label)
                        Text(
                            text = "${scenes.size}개 작품이 이곳에서 촬영",
                            style = IOS.caption,
                            color = IOS.secondaryLabel,
                        )
                    }
                }
                itemsIndexed(scenes) { _, scene ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = IOS.gutter, vertical = 10.dp),
                    ) {
                        // **장면 스틸을 쓴다.** 포스터로 대신하면 한 작품의 장면이
                        // 여럿일 때 카드가 전부 같은 그림이 된다 — "이 장면이 찍힌
                        // 곳" 을 보여 주는 것이 이 앱의 핵심이다.
                        RemoteImage(
                            url = (scene.sceneImageUrl ?: scene.posterUrl)?.toString(),
                            modifier =
                                Modifier
                                    .size(width = 120.dp, height = 70.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                        )
                        Column {
                            Text(
                                text = scene.contentTitle,
                                style = IOS.caption2,
                                color = IOS.accent,
                            )
                            Text(
                                text = scene.sceneDescription ?: "장면 설명이 아직 없습니다",
                                style = IOS.subheadline,
                                color =
                                    if (scene.sceneDescription == null) {
                                        IOS.secondaryLabel
                                    } else {
                                        IOS.label
                                    },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 외부 브라우저·앱으로 넘긴다. 네이버 지도의 길찾기·영업정보는 우리 몫이 아니다. */
private fun openUrl(
    context: android.content.Context,
    url: String,
) {
    runCatching {
        context.startActivity(
            android.content
                .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
