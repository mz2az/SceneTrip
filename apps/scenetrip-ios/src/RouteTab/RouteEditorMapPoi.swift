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

    /// 결과 지도가 떠 있나 — 그동안 일정 서랍·지도 알약·＋·해태는 비킨다.
    var mapResultsOpen: Bool {
        showsMapPoiBar && mapResults.isOpen
    }

    /// 결과 지도의 핀 — 보고 있는 탭의 것만(촬영지 사진 핀 / 가게 색 점).
    var mapSearchPins: MapSearchPins {
        let state = mapResultsOpen ? mapResults.state : nil
        return MapSearchPins(
            spots: state?.tab == .spots ? state?.spots ?? [] : [],
            selected: state?.selected,
            shops: state?.tab == .shops ? state?.shops ?? [] : [],
            camera: mapCamera,
            onSpot: tapSearchSpot
        )
    }

    /// 지도 맨 위 — 칩 줄, 🔍 를 누르면 검색칸, 엔터 뒤면 결과 지도.
    @ViewBuilder var mapTop: some View {
        if showsMapPoiBar {
            if mapResults.isOpen {
                GeometryReader { geo in
                    MapSearchResultsView(
                        model: mapResults, height: geo.size.height, viewport: mapViewport,
                        dayCounts: dayCounts, currentDay: dayIndex, taken: takenPlaceIds,
                        onAddToDay: { place, day in addToDay([place], day: day) },
                        onFocus: focusSearchSpot,
                        onPickShop: pickSearchPlace,
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
                    viewport: mapViewport,
                    onPick: { place in
                        mapSearching = false
                        pickSearchPlace(place)
                    },
                    onCommit: { query, wide in
                        mapSearching = false
                        guide.picked = nil
                        mapResults.open(query: query, wide: wide, viewport: mapViewport)
                    },
                    onClose: { withAnimation(.easeOut(duration: 0.2)) { mapSearching = false } },
                    initialText: mapSearchText
                )
            } else {
                MapPoiBar(
                    chips: mapChips,
                    counts: MapPoiChips.counts(poisForChips),
                    pill: poiFetch.pill(anyOn: mapChips.anyOn, zoom: mapViewport?.zoom ?? 0),
                    onSearch: openMapSearch,
                    onToggle: toggleMapChip,
                    onAll: toggleAllMapChips,
                    onRefresh: { mapChips.refresh() }
                )
            }
        }
    }

    /// 지도에 그릴 편의시설을 칸마다 큰 점·작은 점으로 가른다(M4). 고른 점은 작은 점이어도 크게(고른 핀으로) 그린다.
    var spreadAmbient: (major: [RouteGuide.Place], minor: [RouteGuide.Place]) {
        let split = MapPoiSpread.split(visibleAmbientPois, zoom: mapViewport?.zoom ?? 0)
        guard let picked = guide.picked, split.minor.contains(where: { $0.id == picked.id }) else { return split }
        return (split.major + split.minor.filter { $0.id == picked.id }, split.minor.filter { $0.id != picked.id })
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
        mapResults.close()
    }

    // MARK: 검색

    /// 🔍 — 지도 범위로 찾으므로, 그 범위가 기기 위치에서 왔으면 위치 동의를 먼저 묻는다.
    func openMapSearch() {
        mapSearchText = ""
        ambientLocationGate.requestLocation(for: mapViewport?.source ?? .userSelected) {
            withAnimation(.easeOut(duration: 0.2)) { mapSearching = true }
        }
    }

    /// 검색 목록·결과의 가게를 골랐다 — 정보 카드가 뜨고 지도가 그곳으로 간다(카드의 「코스에 담기」 로 넣는다).
    func pickSearchPlace(_ place: RouteGuide.Place) {
        pickedStop = nil
        guide.picked = place
    }

    /// 결과의 촬영지 줄을 눌렀다 — 고른 핀으로 크게, 지도는 그곳으로(확대는 15 이상).
    func focusSearchSpot(_ place: PlaceSummary) {
        mapResults.state?.markSelfMove(at: Date())
        mapCamera = MapCameraCommand(
            id: (mapCamera?.id ?? 0) + 1,
            action: .focus(latitude: place.latitude, longitude: place.longitude, minZoom: 15)
        )
    }

    /// 촬영지를 받았다 — 결과가 다 보이게 맞춘다(서랍이 덮은 아래는 빼고).
    func fitSearchResults() {
        guard let spots = mapResults.state?.spots, !spots.isEmpty else { return }
        mapResults.state?.markSelfMove(at: Date())
        let lats = spots.map(\.latitude)
        let lngs = spots.map(\.longitude)
        mapCamera = MapCameraCommand(
            id: (mapCamera?.id ?? 0) + 1,
            action: .fit(south: lats.min()!, west: lngs.min()!, north: lats.max()!, east: lngs.max()!)
        )
    }

    /// 결과 핀을 눌렀다 — 서랍의 그 줄을 고른다.
    func tapSearchSpot(_ place: PlaceSummary) {
        mapResults.state?.tab = .spots
        mapResults.state?.select(place.id)
    }
}

extension RouteEditorView {
    /// 확인용 뒷문(MZ2AZ-547 P5) — `-editorMode edit -editorMap 1` 과 함께 쓴다. 지도가 한 번 멈춘 뒤에.
    /// `-editorPoi all|food|sight|stay|transit` 칩을 켠다 · `-editorSearch 남산` 검색칸을 그 글자로 연다 ·
    /// `-editorResults 남산` 결과 지도를 연다(이 지역 밖까지, 첫 촬영지를 고른다).
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
            mapResults.open(query: results, wide: true, viewport: mapViewport)
            for _ in 0 ..< 50 where mapResults.state?.spots == nil {
                try? await Task.sleep(for: .milliseconds(100))
            }
            if let first = mapResults.state?.spots?.first {
                try? await Task.sleep(for: .milliseconds(1500))
                mapResults.state?.select(first.id)
            }
        }
    }
}
