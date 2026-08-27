import SwiftUI

/// 편집 화면의 **주변 편의시설** — 화면이 멈출 때마다 그 범위를 받아 깔아 주는
/// 쪽 논리. `RouteEditorView.swift` 에서 갈라 뒀다(타입 길이 한도).
extension RouteEditorView {
    /// 갈래 필터를 통과한 주변 편의시설. 챗봇이 이미 보여 준 곳과 코스에 담긴
    /// 곳은 뺀다 — 같은 가게가 두 겹으로 찍히면 어느 쪽을 누른 것인지 모른다.
    var visibleAmbientPois: [RouteGuide.Place] {
        let shown = Set(guide.places.map { RouteDedupe.key($0.asPlaceSummary) })
        let taken = takenSpotKeys
        return ambientPois.filter { place in
            let key = RouteDedupe.key(place.asPlaceSummary)
            return poiGroupsOn.contains(place.poiGroup)
                && !shown.contains(key) && !taken.contains(key)
        }
    }

    /// 갈래 칩이 세는 대상 — 챗봇 결과 + 주변. 겹침은 뺀 뒤 센다.
    var poisForChips: [RouteGuide.Place] {
        guide.places + {
            let shown = Set(guide.places.map { RouteDedupe.key($0.asPlaceSummary) })
            return ambientPois.filter { !shown.contains(RouteDedupe.key($0.asPlaceSummary)) }
        }()
    }

    /// 카메라가 멈췄다 — 0.35초 조용하면 그 범위의 주변을 받는다. **너무 넓은
    /// 화면(줌 13 미만, 수십 km)에서는 안 부른다** — 점이 먼지처럼 흩어질 뿐이고
    /// 서버도 헛돈다.
    func viewportChanged(
        south: Double, west: Double, north: Double, east: Double,
        centerLat: Double, centerLng: Double, zoom: Double
    ) {
        ambientTask?.cancel()
        guard zoom >= 13 else {
            ambientPois = []
            return
        }
        ambientTask = Task {
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            let found = await RouteGuide.pois(
                south: south, west: west, north: north, east: east,
                centerLat: centerLat, centerLng: centerLng, limit: 30
            )
            guard !Task.isCancelled else { return }
            ambientPois = found
        }
    }
}
