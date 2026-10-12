package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.SceneData
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.IOS

private const val INITIAL_PLACE_COUNT = 10
private const val CHIP_FILL_PAGES = 10

@Composable
internal fun ListContent(
    data: SceneData,
    nearby: Boolean,
    onSearchViewport: () -> Unit,
    committed: String,
    onClearSearch: () -> Unit,
    tab: ListTab,
    onTabChange: (ListTab) -> Unit,
    chip: String,
    onChipChange: (String) -> Unit,
    isInitial: Boolean,
    places: List<PlaceSummary>,
    contents: List<ContentSummary>,
    saved: (Long) -> Boolean,
    liked: (Long) -> Boolean,
    onToggleLike: (Long) -> Unit,
    onOpenContent: (ContentSummary) -> Unit,
    onSelectPlace: (PlaceSummary) -> Unit,
    onAdd: (PlaceSummary) -> Unit,
) {
    var chipFillRetry by remember { mutableStateOf(0) }
    val wantsMorePlaces = data.placePaging.hasMore && (!isInitial || places.size < INITIAL_PLACE_COUNT)
    LaunchedEffect(chip, data.listSerial, data.phase, chipFillRetry) {
        if (chip == CategoryChip.ALL) return@LaunchedEffect
        var pages = 0
        while (data.placePaging.hasMore && (!isInitial || data.places.count { CategoryChip.of(it.type) == chip } < INITIAL_PLACE_COUNT) &&
            pages < CHIP_FILL_PAGES
        ) {
            if (!data.loadNextPlaces()) break
            pages++
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(data.listSerial, tab) { listState.scrollToItem(0) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // 검색을 하고 들어온 목록이면 나가는 길을 준다 — 상세와 같은 헤더를 같은
        // 자리에 쓴다. 브라우저 뒤로가기처럼 한 번에 한 단계씩만 나온다.
        if (committed.isNotEmpty()) {
            DetailHeader(title = committed, onBack = onClearSearch)
        }

        // 첫 화면의 숫자는 **전체가 아니라 인기순으로 추린 것**이다. 그냥 "장소 10"
        // 이라고만 두면 전국에 10곳뿐인 것으로 읽힌다.
        SegmentedControl(
            options = ListTab.values().toList(),
            selected = tab,
            label = { entry ->
                val count =
                    when {
                        entry == ListTab.WORK -> data.contentPaging.shownTotal(contents.size)
                        isInitial -> places.size
                        chip == CategoryChip.ALL -> data.placePaging.shownTotal(data.places.size)
                        else -> places.size
                    }
                // 「인기 작품 12」·「장소 8」 — 지금 언어로 그린다(iOS `tabLabel`, MZ2AZ-343).
                val format =
                    if (entry == ListTab.WORK) {
                        if (isInitial) tr("인기 작품 %d") else tr("작품 %d")
                    } else {
                        if (isInitial) tr("인기 장소 %d") else tr("장소 %d")
                    }
                format.format(count) +
                    if (entry == ListTab.PLACE && !isInitial && chip != CategoryChip.ALL && data.placePaging.hasMore) "+" else ""
            },
            onSelect = onTabChange,
        )
        Spacer(Modifier.height(8.dp))

        if (tab == ListTab.PLACE) {
            ChipRow(selected = chip, onSelect = onChipChange)
            Spacer(Modifier.height(8.dp))
            SearchPartialNotice(data, isInitial, chip, nearby, onSearchViewport)
        }

        LazyColumn(state = listState) {
            if (tab == ListTab.WORK) {
                itemsIndexed(contents, key = { _, content -> "work:${content.id}" }) { _, content ->
                    WorkRow(
                        content = content,
                        liked = liked(content.id),
                        onTap = { onOpenContent(content) },
                        onToggleLike = { onToggleLike(content.id) },
                    )
                    RowDivider()
                }
                if (data.contentPaging.hasMore || data.state.moreContentsFailed) {
                    item {
                        SearchMoreFooter(
                            data.contentPaging.offset,
                            data.listSerial,
                            data.state.moreContentsFailed,
                            data.state.loadingMoreContents,
                            onMore = data::loadMoreContents,
                        )
                    }
                }
                if (contents.isEmpty() && !data.contentPaging.hasMore) {
                    item {
                        SearchListEmpty("검색된 작품이 없어요", "")
                    }
                }
            } else {
                // 번호는 지도 핀과 같은 배열의 같은 순서다 — "3번 행 = 3번 핀".
                itemsIndexed(places, key = { _, place -> "place:${place.id}" }) { index, place ->
                    PlaceRow(
                        place = place,
                        number = index + 1,
                        saved = saved(place.id),
                        onTap = { onSelectPlace(place) },
                        onAdd = { onAdd(place) },
                    )
                    RowDivider()
                }
                if (wantsMorePlaces) {
                    item {
                        SearchMoreFooter(
                            data.placePaging.offset,
                            data.listSerial,
                            data.state.morePlacesFailed,
                            data.state.loadingMorePlaces,
                            onMore = data::loadMorePlaces,
                            onRetry = { if (chip == CategoryChip.ALL) data.loadMorePlaces() else chipFillRetry++ },
                        )
                    }
                }
                if (places.isEmpty() && !wantsMorePlaces) {
                    item {
                        when {
                            chip != CategoryChip.ALL -> SearchListEmpty("이 분류의 촬영지가 없어요", "다른 분류를 누르거나 「전체」 로 돌아가 보세요")
                            nearby -> SearchListEmpty("이 지도 안에는 촬영지가 없어요", "지도를 옮기거나 축소한 뒤 다시 검색해 보세요")
                            else -> SearchListEmpty("검색된 장소가 없어요", "")
                        }
                    }
                }
            }
        }
    }
}

/** iOS 는 `Divider().padding(.leading, 14)` 다 — 왼쪽이 들여쓰기된 선. */
@Composable
private fun RowDivider() {
    Box(
        modifier =
            Modifier
                .padding(start = IOS.gutter)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(IOS.separator),
    )
}

@Composable
private fun SearchMoreFooter(
    offset: Int,
    serial: Int,
    failed: Boolean,
    loading: Boolean,
    onMore: () -> Unit,
    onRetry: () -> Unit = onMore,
) {
    LaunchedEffect(offset, serial) { if (!failed) onMore() }
    if (failed) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("더 불러오지 못했어요"), style = IOS.footnote, color = IOS.secondaryLabel)
            Text(tr("다시 시도"), color = IOS.accent, modifier = Modifier.clickable(enabled = !loading, onClick = onRetry).padding(12.dp))
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = IOS.accent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SearchListEmpty(
    title: String,
    detail: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr(title), style = IOS.footnote, color = IOS.secondaryLabel, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) Text(tr(detail), style = IOS.caption, color = IOS.tertiaryLabel, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchPartialNotice(
    data: SceneData,
    initial: Boolean,
    chip: String,
    nearby: Boolean,
    onSearchViewport: () -> Unit,
) {
    val loaded = data.places.size
    val total = data.placePaging.shownTotal(loaded)
    if (initial || loaded >= total || data.phase != SceneData.Phase.LOADED) return
    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (chip != CategoryChip.ALL) {
            Text(
                tr("이 분류를 %2\$d곳 중 %1\$d곳에서 찾았어요").format(loaded, total),
                style = IOS.caption,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
            )
            Text(
                tr(if (data.state.morePlacesFailed) "나머지를 확인하지 못했어요" else "나머지를 확인하는 중이에요"),
                style = IOS.caption2,
                color = IOS.secondaryLabel,
            )
        } else {
            Text(
                tr("%2\$d곳 중 %1\$d곳을 보고 있어요").format(loaded, total),
                style = IOS.caption,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
            )
            Text(
                tr(if (nearby) "지도를 확대하면 더 보여요" else "목록을 내리면 이어서 보여요"),
                style = IOS.caption2,
                color = IOS.secondaryLabel,
            )
            if (nearby) {
                Text(
                    tr("이 화면 다시 검색"),
                    style = IOS.caption2,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.accent,
                    modifier = Modifier.clickable(onClick = onSearchViewport).padding(vertical = 8.dp),
                )
            }
        }
    }
}
