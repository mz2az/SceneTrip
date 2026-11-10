package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import kotlinx.coroutines.CoroutineScope

/**
 * 계획 화면 지도 위의 **편의시설 칩·지도 검색** 상태 (MZ2AZ-547 P5 M1–M7, iOS `RouteEditorMapPoi.swift`, 웹 `tripExtensions.tsx` `EditorMapTop`).
 *
 * 규칙은 `MapPoiRules.kt`·`MapSearchRules.kt`, 화면 부품은 `MapPoiBar`·`MapSearchPanel`·`MapSearchResultsView`·`MapPoiMarkers`.
 * 여기는 그 상태를 한데 들고 있다 — 계획 화면(`RouteEditorView`)은 이미 길어서 상태를 여기에 모았다. 받기(위치 동의·서버)는 계획 화면이 한다.
 */
internal class EditorMapPoi(
    scope: CoroutineScope,
    search: MapShopSearch,
) {
    /** 지도 위 갈래 칩 — **처음엔 다 꺼져 있고 켤 때만 받는다**(M1·M2). 예전엔 줌 13 이상이면 저절로 깔렸다. */
    var chips by mutableStateOf(MapPoiChips())
    var fetch by mutableStateOf(MapPoiFetch())

    /** 칩으로 받은 편의시설(켜진 갈래만). */
    var pois by mutableStateOf<List<PoiSummary>>(emptyList())

    /** 지도가 마지막으로 멈춘 범위 — 칩·검색이 이 범위로 묻는다. */
    var viewport by mutableStateOf<MapViewport?>(null)
        private set

    /** 🔍 검색칸이 열려 있나(M6) · 다시 열 때 채울 검색어. */
    var searching by mutableStateOf(false)
    var searchText by mutableStateOf("")
    val list = MapSearchModel(scope, search)
    val results = MapSearchResultsModel(scope, search)

    /**
     * 카메라가 멈췄다 — 범위를 기억해 둔다. **받지는 않는다**(M2·M3): 칩을 켜거나 「다시 찾기」 를 누를 때만 이 범위로 받고,
     * 지도를 옮기면 점은 그대로 두고 「↻ 이 지역에서 다시 찾기」 를 띄운다.
     */
    fun cameraIdle(
        next: MapViewport,
        nowMillis: Long,
    ) {
        viewport = next
        if (results.isOpen) {
            results.update { it.cameraIdle(nowMillis) }
        } else if (chips.anyOn) {
            fetch = fetch.cameraIdle()
        }
    }

    fun pill(): MapPoiPill = fetch.pill(chips.anyOn, viewport?.zoom ?: 0.0)

    /** 칩 줄을 닫을 때(보기로·여행 시작·가이드 답) — 다 끄고 검색도 닫는다. */
    fun reset() {
        chips = chips.reset()
        searching = false
        searchText = ""
        list.reset()
        results.close()
    }
}

/**
 * 지도 맨 위 — 칩 줄, 🔍 를 누르면 검색칸, 엔터 뒤면 결과 지도. 지도와 일정 서랍을 함께 덮는 칸에 얹는다(결과 서랍이 일정 서랍 자리를 쓴다).
 */
@Composable
internal fun EditorMapTop(
    state: EditorMapPoi,
    onSearch: () -> Unit,
    onToggle: (PoiCategoryGroup) -> Unit,
    onAll: () -> Unit,
    onPick: (PoiSummary) -> Unit,
    onCommit: (query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when {
            state.results.isOpen -> {
                MapSearchResultsView(
                    model = state.results,
                    viewport = state.viewport,
                    onPick = onPick,
                    onEdit = { query ->
                        state.searchText = query
                        state.results.close()
                        state.searching = true
                    },
                    onExit = { state.results.close() },
                )
            }

            state.searching -> {
                MapSearchPanel(
                    model = state.list,
                    viewport = state.viewport,
                    initialText = state.searchText,
                    onPick = { place ->
                        state.searching = false
                        onPick(place)
                    },
                    onCommit = { query ->
                        state.searching = false
                        onCommit(query)
                    },
                    onClose = { state.searching = false },
                )
            }

            else -> {
                MapPoiBar(
                    chips = state.chips,
                    pill = state.pill(),
                    onSearch = onSearch,
                    onToggle = onToggle,
                    onAll = onAll,
                    onRefresh = { state.chips = state.chips.refresh() },
                )
            }
        }
    }
}

/**
 * 확인용 뒷문(MZ2AZ-547 P5, iOS `-editorPoi`·`-editorSearch`·`-editorResults`). `MainActivity` 가 인텐트 값으로 채운다 —
 * `-e editorMode edit --ez editorMap true` 와 함께 쓴다. `-e editorPoi all|food|sight|stay|transit` 칩을 켠다 ·
 * `-e editorSearch 남산` 검색칸을 그 글자로 연다 · `-e editorResults 남산` 결과 지도를 연다(화면 가운데에서 가까운 순, 첫 결과를 고른다).
 * 한 번 쓰면 비운다.
 */
object MapPoiDebug {
    var poi: String? = null
    var search: String? = null
    var results: String? = null

    internal data class Taken(
        val poi: String?,
        val search: String?,
        val results: String?,
    ) {
        val any: Boolean get() = poi != null || search != null || results != null
    }

    internal fun take(): Taken =
        Taken(poi, search, results).also {
            poi = null
            search = null
            results = null
        }
}
