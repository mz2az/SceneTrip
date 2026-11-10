package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon

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
        val drawer by animateDpAsState(maxHeight * state.drawerShare.toFloat(), label = "drawer")
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
                    .fillMaxWidth()
                    .height(drawer)
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
                        .semantics { contentDescription = handleLabel }
                        .clickable(role = Role.Button) { model.update { it.toggleDrawer() } },
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
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 4.dp, bottom = 20.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(IOS.systemBackground),
                    ) {
                        itemsIndexed(body.shops, key = { _, place -> place.id }) { index, place ->
                            Column {
                                if (index >
                                    0
                                ) {
                                    HorizontalDivider(
                                        color = IOS.separator,
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(start = 66.dp),
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
