package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.SceneData
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.EntityType
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.map.NaverMap
import kotlinx.coroutines.launch

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
 * 서버가 정본이다. 검색은 서버가 하고 두 탭이 **같은 `q`** 로 함께 채워진다.
 */
@Composable
fun SearchTabScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val keyboard = LocalSoftwareKeyboardController.current

    // 검색바는 상태바 **아래**에 놓이므로, 시트가 멈출 자리도 그만큼 내려간다.
    // iOS 는 안전영역 안에서 계산해 172pt 에서 멈추는데, 이것을 빼먹어 안드로이드
    // 시트가 66dp 더 올라가 지도 조작 버튼을 덮었다(대조 검사).
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val data = remember { SceneData(scope) }
    val cart = remember { CartStore(context) }
    val likes = remember { LikeStore(context) }

    var draft by remember { mutableStateOf("") }

    // **확정된** 검색어. `draft` 는 타이핑하는 동안에도 바뀌므로 "지금 이 목록이
    // 무엇을 검색한 결과인가" 를 그것으로 판단하면 헤더가 글자마다 깜빡인다.
    var committed by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ListTab.WORK) }
    var chip by remember { mutableStateOf(CategoryChip.ALL) }
    var detent by remember { mutableStateOf(Detent.MEDIUM) }
    var map by remember { mutableStateOf<NaverMap?>(null) }
    var sheetHeight by remember { mutableStateOf(0.dp) }
    var nearby by remember { mutableStateOf(false) }

    // 검색을 확정했고 결과 도착을 기다리는 중 — 도착하면 그때 카메라를 맞춘다.
    // **확정 즉시 맞추면 안 된다**(iOS 실측): 서버 응답이 오기 전에 지도가 직전
    // 검색의 핀 범위로 맞춰지고, 새 핀이 도착해도 카메라는 그대로다.
    //
    // ✕ 로 지울 때도 켠다 — 4 차에 안드로이드만 직전 검색지에 머물러 있었다.
    var pendingFit by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }

    // 자동완성 맨 위에 포스터와 함께 띄우는 **가장 연관된 작품**. 랭킹은 서버가
    // 정하므로 suggest 응답의 첫 작품을 `GET /contents/{id}` 로 채운 것이다.
    var topWork by remember { mutableStateOf<ContentDetail?>(null) }

    // 드릴다운 2단 — 고른 작품과 그 작품의 촬영지.
    var selectedContent by remember { mutableStateOf<ContentSummary?>(null) }
    var contentPlaces by remember { mutableStateOf<List<PlaceSummary>>(emptyList()) }
    var contentLoading by remember { mutableStateOf(false) }

    // 드릴다운 3단 — 고른 촬영지.
    var selectedPlace by remember { mutableStateOf<PlaceSummary?>(null) }

    // 장면 팝업 — 카드에서 두 줄로 잘린 설명의 전문을 본다.
    var selectedScene by remember { mutableStateOf<Scene?>(null) }

    // 지도가 남한 밖으로 나갔나 — 「한국으로」 버튼을 그때만 띄운다.
    var outsideKorea by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        data.search("")
        cart.refresh()
    }

    // 지도가 남한 밖으로 나갔는지 지켜본다.
    //
    // 카메라가 움직일 때마다 상태를 갱신하면 화면이 매 프레임 다시 그려진다.
    // 이 버튼은 즉각적일 필요가 없어 **1 초에 한 번**만 확인한다 — iOS 와 같다.
    LaunchedEffect(map) {
        val target = map ?: return@LaunchedEffect
        while (true) {
            val outside = target.isOutsideKorea()
            if (outside != outsideKorea) outsideKorea = outside
            kotlinx.coroutines.delay(1000)
        }
    }

    // 자동완성은 **글자마다** 부른다. 검색 확정과 달라서, 늦게 온 응답이 최신을
    // 덮지 않도록 응답 시점에 검색어가 그대로인지 본다 — iOS 와 같은 방식이다.
    LaunchedEffect(draft) {
        if (draft.isBlank()) {
            suggestions = emptyList()
        } else {
            val asked = draft
            val items = data.suggest(asked.trim())
            if (asked != draft) return@LaunchedEffect
            suggestions = items

            val first = items.firstOrNull { it.type == EntityType.content }
            if (first == null) {
                topWork = null
            } else if (topWork?.id != first.id) {
                val detail = data.contentDetail(first.id)
                if (asked == draft) topWork = detail
            }
        }
    }

    val isInitial = committed.isEmpty() && !nearby && selectedContent == null

    val visiblePlaces =
        data.places
            .filter { chip == CategoryChip.ALL || CategoryChip.of(it.type) == chip }
            // 첫 화면은 **인기 상위 10곳만** 본다. 전부 보여 주면 목록도 지도도 읽을 수
            // 없다 — 무엇부터 봐야 할지가 사라진다.
            .let { if (isInitial) it.take(10) else it }

    // 번호는 "목록의 N 번 = 지도의 N 번" 을 잇는 장치인데, 첫 화면의 작품 탭에서는
    // 목록이 작품이고 핀은 장소라 이어 볼 짝이 없다.
    val numbersOnPins = !(isInitial && tab == ListTab.WORK)

    // 첫 화면에서 장소 탭으로 옮기면 인기 10곳이 **한 화면에 다 들어오게** 맞춘다.
    // 서울 중심 그대로면 강릉·포항이 화면 밖이라 목록의 절반이 어디 있는지 보이지
    // 않는다. 작품 탭은 특정 촬영지를 가리키지 않으므로 남한 전체로 돌아간다.
    LaunchedEffect(tab, map) {
        if (!isInitial) return@LaunchedEffect
        val target = map ?: return@LaunchedEffect
        if (tab == ListTab.PLACE) {
            target.fit(visiblePlaces, density, screenHeight, sheetHeight)
        } else {
            target.showWholeKorea(density)
        }
    }

    // 지도에 꽂는 핀. 작품을 골랐으면 그 작품의 촬영지, 아니면 검색 결과다 —
    // 어느 쪽이든 **시트의 목록과 같은 배열**이라 행 번호가 곧 핀 번호다.
    val mapPlaces = selectedContent?.let { contentPlaces } ?: visiblePlaces

    // 드릴다운하면 그 작품의 촬영지가 **한 화면에 다 들어오게** 맞춘다. iOS 가
    // `fitToken` 을 올리는 자리다 — 도깨비 촬영지 57곳이 전국에 흩어져 있어
    // 서울 그대로면 목록의 대부분이 화면 밖이다.
    // 장소를 열면 그 장소로 **확대**하고, 닫으면 원래 보던 범위로 돌아간다.
    // iOS 의 focusToken / fitToken 이 하는 일이다.
    LaunchedEffect(selectedPlace) {
        val target = map ?: return@LaunchedEffect
        val place = selectedPlace
        if (place != null) {
            target.zoomTo(place, density, screenHeight, sheetHeight)
        } else {
            target.fit(
                if (selectedContent != null) contentPlaces else visiblePlaces,
                density,
                screenHeight,
                sheetHeight,
            )
        }
    }

    LaunchedEffect(contentPlaces) {
        if (contentPlaces.isEmpty()) return@LaunchedEffect
        map?.fit(contentPlaces, density, screenHeight, sheetHeight)
    }

    // 검색을 확정하면 결과 범위로 맞춘다. **결과가 도착한 뒤**여야 한다 — 확정
    // 즉시 맞추면 직전 검색의 핀 범위로 맞춰지고, 새 핀이 와도 카메라는 그대로다.
    LaunchedEffect(data.places) {
        if (!pendingFit || data.places.isEmpty()) return@LaunchedEffect
        pendingFit = false
        map?.fit(visiblePlaces, density, screenHeight, sheetHeight)
    }

    fun commit(
        term: String,
        kind: EntityType? = null,
    ) {
        when (kind) {
            EntityType.place -> tab = ListTab.PLACE
            EntityType.content, EntityType.person -> tab = ListTab.WORK
            else -> Unit
        }
        draft = term
        committed = term.trim()
        // 단어로 검색하는 순간 반경 모드는 끝난다 — 두 조건이 함께 걸려 있으면
        // 결과가 왜 그렇게 나왔는지 화면만 보고는 설명할 수 없다.
        nearby = false
        chip = CategoryChip.ALL
        selectedContent = null
        contentPlaces = emptyList()
        searching = false
        keyboard?.hide()
        detent = Detent.MEDIUM
        data.search(term)
        pendingFit = true
    }

    Box(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        NaverMapCanvas(
            modifier = Modifier.fillMaxSize(),
            sheetHeight = sheetHeight,
            // 첫 진입 카메라는 `NaverMapCanvas` 가 여백을 정한 뒤 스스로 맞춘다.
            // 첫 진입 카메라는 `NaverMapCanvas` 가 배치 뒤에 스스로 맞춘다 —
            // 여기서 맞추면 뷰 크기가 0 일 수 있다.
            onMapReady = { map = it },
        )
        MapPins(
            map = map,
            places = mapPlaces,
            numbered = numbersOnPins,
            // 핀을 누르면 그 장소의 상세가 열린다 — iOS `onTapPin` 과 같다.
            onTap = { place ->
                selectedPlace = place
                if (selectedContent == null) tab = ListTab.PLACE
                detent = Detent.MEDIUM
            },
        )

        BottomSheet(
            detent = detent,
            onDetentChange = { detent = it },
            topInset = SEARCH_BAR_INSET + statusBar,
            onHeightChange = { sheetHeight = it },
        ) {
            when {
                data.phase == SceneData.Phase.FAILED -> {
                    Centered(data.failure ?: "불러오지 못했습니다")
                }

                data.phase == SceneData.Phase.LOADING &&
                    data.contents.isEmpty() && data.places.isEmpty() -> {
                    Centered(null)
                }

                selectedPlace != null -> {
                    PlaceDetailView(
                        summary = selectedPlace!!,
                        saved = cart.contains(selectedPlace!!.id),
                        detailOf = { data.placeDetail(it) },
                        onBack = { selectedPlace = null },
                        onOpenScene = { selectedScene = it },
                        onToggleSave = {
                            val place = selectedPlace!!
                            scope.launch {
                                if (cart.contains(place.id)) {
                                    cart.remove(place.id)
                                } else {
                                    cart.add(place.id)
                                }
                            }
                        },
                    )
                }

                selectedContent != null -> {
                    ContentDetailView(
                        summary = selectedContent!!,
                        places = contentPlaces,
                        loading = contentLoading,
                        saved = { cart.contains(it) },
                        detailOf = { data.contentDetail(it) },
                        onBack = {
                            selectedContent = null
                            contentPlaces = emptyList()
                        },
                        onSelectPlace = { selectedPlace = it },
                        onSave = { place ->
                            scope.launch {
                                // **어느 작품 때문에 담았는지 함께 보낸다** — 같은
                                // 장소가 여러 작품에 나오므로 이것이 없으면 나중에
                                // 되짚을 수 없다 (MZ2AZ-208).
                                if (cart.contains(place.id)) {
                                    cart.remove(place.id)
                                } else {
                                    cart.add(place.id, selectedContent?.id)
                                }
                            }
                        },
                    )
                }

                else -> {
                    ListContent(
                        committed = committed,
                        onClearSearch = { commit("") },
                        tab = tab,
                        onTabChange = { tab = it },
                        chip = chip,
                        onChipChange = { chip = it },
                        isInitial = isInitial,
                        places = visiblePlaces,
                        contents = data.contents,
                        saved = { cart.contains(it) },
                        liked = { likes.contains(it) },
                        onToggleLike = { likes.toggle(it) },
                        onSelectPlace = { selectedPlace = it },
                        onOpenContent = { content ->
                            selectedContent = content
                            detent = Detent.MEDIUM
                            contentLoading = true
                            scope.launch {
                                contentPlaces = data.placesOf(content.id)
                                contentLoading = false
                            }
                        },
                        onAdd = { place ->
                            scope.launch {
                                if (cart.contains(place.id)) cart.remove(place.id) else cart.add(place.id)
                            }
                        },
                    )
                }
            }
        }

        // 검색 중에는 지도·시트를 스크림으로 덮는다 — 패널 밖을 누르면 닫힌다.
        if (searching) {
            SearchScrim(onDismiss = { searching = false })
        }

        Column(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
            SearchBar(
                draft = draft,
                cartCount = cart.items.size,
                onDraftChange = {
                    draft = it
                    searching = true
                },
                onSubmit = { commit(draft) },
                // iOS 는 ✕ 를 누르면 **포커스가 풀린다.** 유지하면 빈 검색어로
                // 자동완성이 계속 떠 있고, 다시 눌러도 새로 뜨지 않는다(4 차 검사).
                onClear = {
                    commit("")
                    searching = false
                },
                onOpenCart = { showCart = true },
                onFocus = { searching = true },
            )
            if (searching) {
                // 자동완성은 검색창 **바로 아래에 붙어** 내려오는 드롭다운이다.
                SuggestionPanel(
                    draft = draft,
                    suggestions = suggestions,
                    topWork = topWork,
                    onCommit = { term, kind -> commit(term, kind) },
                    onOpenWork = { work ->
                        // 검색어를 작품명으로 바꾸지 않고 **바로 상세로** 들어간다.
                        // 치환하면 뒤로 갔을 때 검색어에 작품명이 남아 원래 결과로
                        // 돌아갈 수 없다 (iOS 가 겪고 폐기한 구현이다).
                        searching = false
                        keyboard?.hide()
                        selectedContent = work.toSummary()
                        contentLoading = true
                        scope.launch {
                            contentPlaces = data.placesOf(work.id)
                            contentLoading = false
                        }
                    },
                    onSelectPlace = { item ->
                        searching = false
                        keyboard?.hide()
                        commit(item.name, EntityType.place)
                    },
                )
            } else if (!searching) {
                // 「현 지도 내 성지 검색」은 가운데, 조작 버튼은 오른쪽 — 위
                // 검색창의 장바구니와 같은 세로선에 놓아 지도를 덜 가린다.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    NearbyButton(
                        on = nearby,
                        count = data.places.size,
                        onToggle = {
                            if (nearby) {
                                nearby = false
                                draft = ""
                                committed = ""
                                chip = CategoryChip.ALL
                                data.search("")
                            } else {
                                map?.contentBounds?.let { box ->
                                    nearby = true
                                    draft = ""
                                    committed = ""
                                    selectedContent = null
                                    tab = ListTab.PLACE
                                    chip = CategoryChip.ALL
                                    detent = Detent.MEDIUM
                                    // **카메라를 건드리지 않는다.** 이 기능은 "지금
                                    // 보고 있는 이 화면 안" 을 묻는 것이다.
                                    data.searchInViewport(
                                        "${box.westLongitude},${box.southLatitude}," +
                                            "${box.eastLongitude},${box.northLatitude}",
                                    )
                                }
                            }
                        },
                    )
                    if (outsideKorea) {
                        MapControl(
                            label = "한국으로",
                            onClick = { map?.showWholeKorea(density) },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 68.dp),
                        ) { tint, size ->
                            Icon(
                                Icons.Filled.Place,
                                contentDescription = "한국으로",
                                tint = tint,
                                modifier = Modifier.size(size),
                            )
                        }
                    }
                    MapControl(
                        label = "내 위치",
                        onClick = {},
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                        // iOS 는 이 아이콘만 강조색이다(실측). 나머지 지도 조작
                        // 버튼은 검정이다.
                    ) { _, size -> ScopeIcon(IOS.accent, Modifier.size(size)) }
                }
            }
        }

        selectedScene?.let { scene ->
            ScenePopup(
                scene = scene,
                placeName = selectedPlace?.name.orEmpty(),
                onClose = { selectedScene = null },
            )
        }

        if (showCart) {
            CartSheet(
                items = cart.items,
                onRemove = { scope.launch { cart.remove(it) } },
                onClose = { showCart = false },
            )
        }
    }
}

/**
 * 검색바가 차지하는 높이. 시트의 최대 단계가 이 아래까지만 올라온다.
 * iOS `BottomSheet(topInset: 108)` 과 같은 값이다.
 */
private val SEARCH_BAR_INSET: Dp = 108.dp

@Composable
private fun Centered(message: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (message == null) {
            CircularProgressIndicator(color = IOS.accent)
        } else {
            Text(message, style = IOS.subheadline, color = IOS.secondaryLabel)
        }
    }
}

@Composable
private fun ListContent(
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
                val count = if (entry == ListTab.WORK) contents.size else places.size
                (if (isInitial) "인기 " else "") + "${entry.label} $count"
            },
            onSelect = onTabChange,
        )
        Spacer(Modifier.height(8.dp))

        if (tab == ListTab.PLACE) {
            ChipRow(selected = chip, onSelect = onChipChange)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn {
            if (tab == ListTab.WORK) {
                itemsIndexed(contents) { _, content ->
                    WorkRow(
                        content = content,
                        liked = liked(content.id),
                        onTap = { onOpenContent(content) },
                        onToggleLike = { onToggleLike(content.id) },
                    )
                    RowDivider()
                }
            } else {
                // 번호는 지도 핀과 같은 배열의 같은 순서다 — "3번 행 = 3번 핀".
                itemsIndexed(places) { index, place ->
                    PlaceRow(
                        place = place,
                        number = index + 1,
                        saved = saved(place.id),
                        onTap = { onSelectPlace(place) },
                        onAdd = { onAdd(place) },
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

/**
 * 상세를 목록 요약으로 줄인다.
 *
 * 자동완성의 포스터 카드에서 바로 작품 상세로 들어갈 때 쓴다 — 상세 화면은
 * `ContentSummary` 로 그리기 시작하고 나머지는 스스로 채우기 때문이다.
 */
private fun ContentDetail.toSummary() =
    ContentSummary(
        id = id,
        category = category,
        title = title,
        placeCount = placeCount,
        posterUrl = posterUrl,
        broadcaster = broadcaster,
        releaseYear = releaseYear,
        genres = genres,
    )
