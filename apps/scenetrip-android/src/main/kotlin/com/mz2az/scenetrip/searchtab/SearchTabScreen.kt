package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS
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
 * iOS `SearchTabView.swift` 를 옮긴 것이다. **동작 규칙도 겉모습도 새로 정하지
 * 않는다** — iOS 소스가 기준이고, 색·간격·컴포넌트 모양까지 그대로 따른다
 * (ADR 0008 · `ui/IOSTheme.kt`).
 *
 * ## 아직 없는 것
 *
 * 서버를 부르지 않는다 — 코틀린 API 클라이언트는 생성·컴파일까지 됐지만 아직
 * 화면에 잇지 않았다 (Model.kt). 자동완성 · 드릴다운 · 반경 검색 · 현위치도 아직이다.
 */
@Composable
fun SearchTabScreen() {
    var draft by remember { mutableStateOf("") }

    // **확정된** 검색어. `draft` 는 타이핑하는 동안에도 바뀌므로 "지금 이 목록이
    // 무엇을 검색한 결과인가" 를 그것으로 판단하면 헤더가 글자마다 깜빡인다.
    var committed by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ListTab.WORK) }
    var chip by remember { mutableStateOf(CategoryChip.ALL) }
    var detent by remember { mutableStateOf(Detent.MEDIUM) }
    var cart by remember { mutableStateOf(setOf<Long>()) }
    var liked by remember { mutableStateOf(setOf<Long>()) }
    var map by remember { mutableStateOf<NaverMap?>(null) }
    var sheetHeight by remember { mutableStateOf(0.dp) }

    // / 반경 검색이 켜져 있나. 켜져 있으면 버튼이 해제 버튼으로 바뀐다.
    var nearby by remember { mutableStateOf(false) }

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
            // 첫 화면은 **인기 상위 10곳만** 본다.
            .let { if (isInitial) it.take(10) else it }

    val visibleContents =
        Fixtures.contents
            .filter { committed.isEmpty() || it.title.contains(committed) }

    // 번호는 "목록의 N 번 = 지도의 N 번" 을 잇는 장치인데, 첫 화면의 작품 탭에서는
    // 목록이 작품이고 핀은 장소라 이어 볼 짝이 없다.
    val numbersOnPins = !(isInitial && tab == ListTab.WORK)

    Box(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        NaverMapCanvas(
            modifier = Modifier.fillMaxSize(),
            sheetHeight = sheetHeight,
            onMapReady = {
                map = it
                it.showWholeKorea()
            },
        )
        MapPins(map = map, places = visiblePlaces, numbered = numbersOnPins)

        BottomSheet(
            detent = detent,
            onDetentChange = { detent = it },
            topInset = SEARCH_BAR_INSET,
            onHeightChange = { sheetHeight = it },
        ) {
            ListContent(
                tab = tab,
                onTabChange = { tab = it },
                chip = chip,
                onChipChange = { chip = it },
                isInitial = isInitial,
                places = visiblePlaces,
                contents = visibleContents,
                cart = cart,
                liked = liked,
                onToggleCart = { id -> cart = if (id in cart) cart - id else cart + id },
                onToggleLike = { id -> liked = if (id in liked) liked - id else liked + id },
            )
        }

        // 검색바 아래에 조작 줄이 붙는다 — iOS 와 같은 배치다.
        // 「현 지도 내 성지 검색」은 가운데, 조작 버튼은 오른쪽이며 위 검색창의
        // 장바구니와 같은 세로선에 놓아 지도를 덜 가린다.
        Column(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
            SearchBar(
                draft = draft,
                cartCount = cart.size,
                onDraftChange = { draft = it },
                onSubmit = {
                    committed = draft.trim()
                    detent = Detent.MEDIUM
                },
                onClear = {
                    draft = ""
                    committed = ""
                },
                onOpenCart = {},
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                NearbyButton(
                    on = nearby,
                    count = visiblePlaces.size,
                    onToggle = {
                        nearby = !nearby
                        draft = ""
                        committed = ""
                        chip = CategoryChip.ALL
                        if (nearby) {
                            tab = ListTab.PLACE
                            detent = Detent.MEDIUM
                        }
                    },
                )
                MapControl(
                    label = "내 위치",
                    onClick = {},
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                ) { tint, size -> ScopeIcon(tint, Modifier.size(size)) }
            }
        }
    }
}

/**
 * 검색바가 차지하는 높이. 시트의 최대 단계가 이 아래까지만 올라온다.
 *
 * iOS 는 기기별로 계산하지만(`topInset`), 여기서는 검색바 구성이 고정이라 상수로
 * 둔다 — 세로 여백 8 + 11 + 11 + 아이콘 22 + 상태바 여유.
 */
private val SEARCH_BAR_INSET: Dp = 108.dp

@Composable
private fun ListContent(
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
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 첫 화면의 숫자는 **전체가 아니라 인기순으로 추린 것**이다. 그냥 "장소 10"
        // 이라고만 두면 전국에 10곳뿐인 것으로 읽힌다.
        SegmentedControl(
            options = ListTab.values().toList(),
            selected = tab,
            label = { entry ->
                val count = if (entry == ListTab.WORK) contents.size else places.size
                (if (isInitial) "인기 " else "") + "${entry.label} $count"
            },
            onSelect = onTabChange,
        )
        androidx.compose.foundation.layout
            .Spacer(Modifier.height(8.dp))

        if (tab == ListTab.PLACE) {
            ChipRow(selected = chip, onSelect = onChipChange)
            androidx.compose.foundation.layout
                .Spacer(Modifier.height(8.dp))
        }

        LazyColumn {
            if (tab == ListTab.WORK) {
                itemsIndexed(contents) { _, content ->
                    WorkRow(content = content, onTap = {})
                    RowDivider()
                }
            } else {
                // 번호는 지도 핀과 같은 배열의 같은 순서다 — "3번 행 = 3번 핀".
                itemsIndexed(places) { index, place ->
                    PlaceRow(
                        place = place,
                        number = index + 1,
                        saved = place.id in cart,
                        onTap = {},
                        onAdd = { onToggleCart(place.id) },
                    )
                    RowDivider()
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
