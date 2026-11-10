import SceneApiClient
import SwiftUI

/// 계획 화면 지도 위의 **편의시설 칩·지도 검색** (MZ2AZ-547 P5 M1–M7, 웹 `route/editor/tripExtensions.tsx` `EditorMapTop`).
///
/// 규칙은 `MapPoiRules.swift`(칩·알약·골고루·검색 줄·결과 서랍), 화면 부품은 `MapPoiBar`·`MapSearchPanel`·`MapSearchResultsView`.
/// 여기는 그 둘을 계획 화면의 상태에 잇는다. 본체의 타입 본문 길이(swiftlint 350줄) 때문에 확장으로 뗐다.
extension RouteEditorView {
    /// 칩 줄이 보이나 — 편집 중이고 지도가 펼쳐져 있고 여행 안내 중이 아닐 때만(M1).
    var showsMapPoiBar: Bool {
        MapPoiChips.showsBar(mode: mode, mapOpen: mapToggle.isOpen, tripActive: trip.isActive)
    }

    /// 처음 만든 계획 화면 안내(C4) — 편집 중이고 새 코스·방금 만든 코스이며 묻는 창이 없을 때.
    var coachEditorFirstActive: Bool {
        CoachRules.editorFirstActive(
            editing: mode == .editing, isNew: isNew, fresh: fresh,
            confirming: confirmingLeave || confirmingDiscard || confirmingRevert
        )
    }

    /// 지도 검색 안내(C5, M8) — 칩 줄이 보이고 검색칸·결과 지도가 닫혀 있을 때.
    var coachMapSearchActive: Bool {
        CoachRules.mapSearchActive(barShown: showsMapPoiBar, searching: mapSearching, resultsOpen: mapResults.isOpen)
    }

    /// 결과 지도가 떠 있나 — 그동안 일정 서랍·지도 알약·＋·해태는 비킨다.
    var mapResultsOpen: Bool {
        showsMapPoiBar && mapResults.isOpen
    }

    /// 지도 검색의 핀 — 엔터 뒤 결과 서랍의 앞 100개만(§8-2, 목록은 더 길어도). 글자 치는 동안은 지도에 찍지 않는다(§7 구글처럼). 정보 카드가 뜬 곳은 고른 핀만.
    var mapSearchPins: MapSearchPins {
        MapSearchPins(
            shops: MapSearchRules.mapDots(
                searching: false, listed: [],
                results: mapResultsOpen ? mapResults.state?.shops.map(MapSearchPagingRules.pinnedResults) : nil,
                picked: guide.picked?.id
            ),
            camera: mapCamera
        )
    }

    /// 지도 맨 위 — 칩 줄, 🔍 를 누르면 검색칸, 엔터 뒤면 결과 지도.
    @ViewBuilder var mapTop: some View {
        if showsMapPoiBar {
            if mapResults.isOpen {
                GeometryReader { geo in
                    MapSearchResultsView(
                        model: mapResults, height: geo.size.height, viewport: mapViewport,
                        onPick: pickSearchPlace,
                        onEdit: { query in
                            mapSearchText = query
                            mapResults.close()
                            mapSearching = true
                        },
                        onExit: { mapResults.close() }
                    )
                }
            } else if mapSearching {
                MapSearchPanel(
                    model: mapSearch,
                    viewport: mapViewport,
                    onPick: { place in
                        mapSearching = false
                        pickSearchPlace(place)
                    },
                    onCommit: { query in
                        mapSearching = false
                        guide.picked = nil
                        mapResults.open(query: query, viewport: mapViewport)
                    },
                    // 줄어드는 움직임은 검색칸이 한다(M7) — 다 줄어든 뒤 칩 줄로 바로 바꾼다.
                    onClose: { mapSearching = false },
                    initialText: mapSearchText
                )
            } else {
                MapPoiBar(
                    chips: mapChips,
                    pill: poiFetch.pill(anyOn: mapChips.anyOn, zoom: mapViewport?.zoom ?? 0),
                    onSearch: openMapSearch,
                    onToggle: toggleMapChip,
                    onAll: toggleAllMapChips,
                    onRefresh: { mapChips.refresh() }
                )
            }
        }
    }

    /// 지도에 그릴 편의시설 — 칸마다 1등만(M4, §7 구글처럼: 나머지는 그리지 않는다). 고른 것은 1등이 아니어도 고른 핀으로 그린다.
    var spreadAmbient: [RouteGuide.Place] {
        let split = MapPoiSpread.split(visibleAmbientPois, zoom: mapViewport?.zoom ?? 0)
        guard let picked = guide.picked, split.minor.contains(where: { $0.id == picked.id }) else { return split.major }
        return split.major + split.minor.filter { $0.id == picked.id }
    }

    // MARK: 칩

    func toggleMapChip(_ group: RoutePoiGroup) {
        mapChips.toggle(group)
        // 감춘 갈래의 고른 핀은 놓는다 — 지도에 없는 것을 계속 골라 두면 카드만 남는다. AI 장소는 제 칩이 따로 놓는다.
        guard !mapChips.isOn(group), let picked = guide.picked, picked.poiGroup == group else { return }
        if !guide.places.contains(where: { $0.id == picked.id }) {
            guide.picked = nil
        }
    }

    func toggleAllMapChips() {
        let wasAll = mapChips.allOn
        mapChips.toggleAll()
        if wasAll, let picked = guide.picked, !guide.places.contains(where: { $0.id == picked.id }) {
            guide.picked = nil
        }
    }

    /// 칩을 켰다·「다시 찾기」 — 지금 범위를 서버의 「골고루」 순서로 받는다(M2). 다 꺼졌으면 점을 비운다.
    /// 기기 위치에서 비롯한 지도면 위치 동의를 먼저 묻는다(예전 주변 조회와 같다).
    func fetchMapPois() {
        ambientTask?.cancel()
        ambientLocationGate.cancel()
        guard let viewport = mapViewport, let request = mapChips.request(zoom: viewport.zoom) else {
            ambientPois = []
            poiFetch.clear()
            return
        }
        let chips = mapChips
        ambientLocationGate.requestLocation(for: viewport.source) {
            poiFetch.begin()
            ambientTask = Task {
                let found = await MapPoiClient.spread(request, in: viewport, chips: chips, source: viewport.source)
                guard !Task.isCancelled else { return }
                ambientPois = found ?? []
                poiFetch.finish()
            }
        }
    }

    /// 칩 줄을 닫을 때(보기로·여행 시작) — 다 끄고 검색도 닫는다.
    func resetMapPoi() {
        mapChips.reset()
        mapSearching = false
        mapSearchText = ""
        mapSearch.reset()
        mapResults.close()
    }

    // MARK: 검색

    /// 🔍 — 지도 범위로 찾으므로, 그 범위가 기기 위치에서 왔으면 위치 동의를 먼저 묻는다.
    func openMapSearch() {
        mapSearchText = ""
        ambientLocationGate.requestLocation(for: mapViewport?.source ?? .userSelected) {
            // 펼치는 움직임은 검색칸이 한다(M7, 🔍 자리에서 오른쪽으로 0.28초).
            mapSearching = true
        }
    }

    /// 검색 목록·결과의 가게를 골랐다 — 정보 카드가 뜨고 지도가 그곳으로 간다(카드의 「코스에 담기」 로 넣는다).
    /// 지도는 시트·결과 서랍·카드가 덮지 않은 곳의 가운데로 옮긴다(`RouteMapView` 의 고른 핀 카메라, `MapSearchRules.extraLift`).
    func pickSearchPlace(_ place: RouteGuide.Place) {
        mapResults.state?.markSelfMove(at: Date())
        pickedStop = nil
        guide.picked = place
    }

    /// 찾은 것을 받았다 — 결과가 다 보이게 맞춘다(서랍이 덮은 아래는 빼고).
    func fitSearchResults() {
        guard let shops = mapResults.state?.shops, !shops.isEmpty else { return }
        mapResults.state?.markSelfMove(at: Date())
        let lats = shops.map(\.latitude)
        let lngs = shops.map(\.longitude)
        mapCamera = MapCameraCommand(
            id: (mapCamera?.id ?? 0) + 1,
            action: .fit(south: lats.min()!, west: lngs.min()!, north: lats.max()!, east: lngs.max()!)
        )
    }
}

extension RouteEditorView {
    /// 확인용 뒷문(MZ2AZ-547 P5) — `-editorMode edit -editorMap 1` 과 함께 쓴다. 지도가 한 번 멈춘 뒤에.
    /// `-editorPoi all|food|sight|stay|transit` 칩을 켠다 · `-editorSearch 남산` 검색칸을 그 글자로 연다 ·
    /// `-editorResults 남산` 결과 지도를 연다(이 지역 밖까지, 첫 결과를 고른다 — 정보 카드가 뜬다).
    func runMapPoiBackdoor() async {
        let defaults = UserDefaults.standard
        let poi = defaults.string(forKey: "editorPoi")
        let search = defaults.string(forKey: "editorSearch")
        let results = defaults.string(forKey: "editorResults")
        guard poi != nil || search != nil || results != nil else { return }
        for _ in 0 ..< 50 where mapViewport == nil {
            try? await Task.sleep(for: .milliseconds(100))
        }
        // 뒷문으로 펼친 지도는 아직 코스에 안 맞춰져 있다 — 맞추고 움직임이 끝난 뒤에 묻는다.
        fitToken += 1
        try? await Task.sleep(for: .milliseconds(2500))
        if poi == "all" {
            toggleAllMapChips()
        } else if let poi, let group = RoutePoiGroup(rawValue: poi) {
            toggleMapChip(group)
        }
        if let search {
            mapSearchText = search
            mapSearching = true
        }
        if let results {
            mapResults.open(query: results, viewport: mapViewport)
            for _ in 0 ..< 50 where mapResults.state?.shops == nil {
                try? await Task.sleep(for: .milliseconds(100))
            }
            if let first = mapResults.state?.shops?.first {
                try? await Task.sleep(for: .milliseconds(1500))
                pickSearchPlace(first)
            }
        }
    }
}
