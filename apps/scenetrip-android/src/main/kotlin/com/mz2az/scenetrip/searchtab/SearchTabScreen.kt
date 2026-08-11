package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naver.maps.map.NaverMap

/** 목록의 두 탭. iOS `SearchTabView.Tab` 과 같다. */
enum class ListTab(
    val label: String,
) {
    WORK("작품"),
    PLACE("장소"),
}

/**
 * 작품검색 탭 (MZ2AZ-194).
 *
 * iOS `SearchTabView.swift` 를 옮긴 것이다. **동작 규칙을 새로 정하지 않는다** —
 * 확정 동작 문서(볼트 `(3주차)경로탭 개발/01_검색 탭 확정 동작 (버그정리 후).md`)가
 * 이미 답을 갖고 있고, 두 앱이 어긋나면 ADR 0002 가 네이티브 두 벌을 택하며 스스로
 * 건 검증 항목에 정면으로 걸린다.
 *
 * ## SwiftUI 와 얼마나 같은가
 *
 * 상태 선언이 `@State private var committed = ""` → `var committed by remember {...}`,
 * 세로 쌓기가 `VStack` → `Column`, 목록이 `ForEach` → `LazyColumn` 이다. 구조가
 * 그대로 남는 것이 Compose 를 고른 이유다.
 *
 * ## 아직 없는 것
 *
 * 서버를 부르지 않는다 — 코틀린 API 클라이언트가 아직 없어서다(Model.kt 의 설명).
 * 자동완성 · 드릴다운 · 반경 검색 · 현위치도 아직이다. 지금 있는 것은 **검색어로
 * 거르기 · 두 탭 · 카테고리 칩 · 장바구니 담기 · 목록과 핀의 번호 일치**다.
 */
@Composable
fun SearchTabScreen() {
    var draft by remember { mutableStateOf("") }

    // **확정된 검색어.** `draft` 는 타이핑하는 동안에도 바뀌므로 "지금 이 목록이
    // 무엇을 검색한 결과인가" 를 그것으로 판단하면 헤더가 글자마다 깜빡인다.
    var committed by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ListTab.WORK) }
    var chip by remember { mutableStateOf(CategoryChip.ALL) }
    val cart = remember { mutableStateOf(setOf<Long>()) }
    val liked = remember { mutableStateOf(setOf<Long>()) }
    var map by remember { mutableStateOf<NaverMap?>(null) }

    // 아무것도 좁히지 않은 첫 화면인가.
    val isInitial = committed.isEmpty()

    // 화면에 실제로 쓰는 촬영지 — 검색어와 칩까지 적용한 것.
    //
    // **목록과 지도가 이것을 같이 쓴다.** 프로토타입은 칩이 목록만 좁히고 지도 핀은
    // 검색 결과 전체를 유지했으나 뒤집었다 — 목록에 없는 핀이 남으면 그 핀을 눌렀을
    // 때 목록에 없는 장소가 열린다.
    val visiblePlaces =
        Fixtures.places
            .filter { committed.isEmpty() || it.name.contains(committed) }
            .filter { chip == CategoryChip.ALL || CategoryChip.of(it.type) == chip }
            // 첫 화면은 **인기 상위 10곳만** 본다. 전부 보여 주면 목록도 지도도 읽을 수
            // 없다 — 무엇부터 봐야 할지가 사라진다.
            .let { if (isInitial) it.take(10) else it }

    val visibleContents =
        Fixtures.contents
            .filter { committed.isEmpty() || it.title.contains(committed) }

    // 핀에 번호를 찍을 것인가. 번호는 "목록의 N 번 = 지도의 N 번" 을 잇는 장치인데,
    // 첫 화면의 작품 탭에서는 목록이 작품이고 핀은 장소라 이어 볼 짝이 없다.
    val numbersOnPins = !(isInitial && tab == ListTab.WORK)

    Box(modifier = Modifier.fillMaxSize()) {
        NaverMapCanvas(
            modifier = Modifier.fillMaxSize(),
            onMapReady = {
                map = it
                it.showWholeKorea()
            },
        )
        MapPins(map = map, places = visiblePlaces, numbered = numbersOnPins)

        SearchBar(
            draft = draft,
            onDraftChange = { draft = it },
            onSubmit = { committed = draft },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
        )

        ResultSheet(
            tab = tab,
            onTabChange = { tab = it },
            chip = chip,
            onChipChange = { chip = it },
            isInitial = isInitial,
            places = visiblePlaces,
            contents = visibleContents,
            cart = cart.value,
            liked = liked.value,
            onToggleCart = { id ->
                cart.value = if (id in cart.value) cart.value - id else cart.value + id
            },
            onToggleLike = { id ->
                liked.value = if (id in liked.value) liked.value - id else liked.value + id
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SearchBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                placeholder = { Text("작품 · 배우 · 장소를 검색") },
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "검색",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clickable(onClick = onSubmit)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * 하단 시트.
 *
 * iOS 는 높이를 세 단으로 끌어 조절한다(`BottomSheet.swift`). 여기서는 **고정
 * 높이**로 두었다 — 끌기와 지도 여백(contentInset)의 연동은 iOS 에서 가장 손이 많이
 * 간 부분이라, 목록·칩·번호가 맞는지부터 눈으로 보고 나서 옮기는 편이 낫다.
 */
@Composable
private fun ResultSheet(
    tab: ListTab,
    onTabChange: (ListTab) -> Unit,
    chip: CategoryChip,
    onChipChange: (CategoryChip) -> Unit,
    isInitial: Boolean,
    places: List<PlaceSummary>,
    contents: List<ContentSummary>,
    cart: Set<Long>,
    liked: Set<Long>,
    onToggleCart: (Long) -> Unit,
    onToggleLike: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxSize(0.52f),
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                            .fillMaxWidth(0.12f)
                            .padding(vertical = 2.dp),
                ) { Text(" ", style = MaterialTheme.typography.labelSmall) }
            }

            TabRow(selectedTabIndex = tab.ordinal) {
                ListTab.values().forEach { entry ->
                    val count = if (entry == ListTab.WORK) contents.size else places.size
                    Tab(
                        selected = tab == entry,
                        onClick = { onTabChange(entry) },
                        // 첫 화면에서만 **「인기」** 를 붙인다. 검색 결과일 때
                        // 붙이면 인기순이 아닌데 인기라고 말하는 셈이 된다.
                        text = {
                            Text(if (isInitial) "인기 ${entry.label} $count" else "${entry.label} $count")
                        },
                    )
                }
            }

            if (tab == ListTab.PLACE) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    CategoryChip.values().forEach { entry ->
                        FilterChip(
                            selected = chip == entry,
                            onClick = { onChipChange(entry) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }

            LazyColumn {
                if (tab == ListTab.WORK) {
                    items(contents, key = { it.id }) { content ->
                        ContentRow(
                            content = content,
                            liked = content.id in liked,
                            onTap = {},
                            onToggleLike = { onToggleLike(content.id) },
                        )
                    }
                } else {
                    itemsIndexed(places) { index, place ->
                        PlaceRow(
                            place = place,
                            number = index + 1,
                            inCart = place.id in cart,
                            onTap = {},
                            onToggleCart = { onToggleCart(place.id) },
                        )
                    }
                }
            }
        }
    }
}
