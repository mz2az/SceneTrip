import NMapsMap
import SceneApiClient
import SwiftUI

/// 길찾기 화면의 **주변 편의시설 칩과 방문 스탬프** — 본체(`RouteNavView.swift`)에서
/// 가른 파일이다(타입 길이 한도). 상태는 본체가 들고, 여기는 그 상태로 그리고 적는다.
extension RouteNavView {
    /// 갈래 칩 — 편집 화면과 같은 부품에 **성지 칩**이 하나 더 붙는다.
    /// 성지는 코스의 번호 핀이라 「전체」(편의시설 마스터 스위치) 소관 밖이다.
    var poiChips: some View {
        RoutePoiChips(
            places: ambientPois, groupsOn: $poiGroupsOn,
            onGroupOff: { group in
                // 챗봇이 찾아 준 핀은 이 칩의 소관이 아니다 — 주변 점에서 고른
                // 것만 놓는다.
                if let picked = guide.picked, picked.poiGroup == group,
                   ambientPois.contains(where: { $0.id == picked.id })
                {
                    guide.picked = nil
                }
            },
            extras: [
                .init(
                    id: "sanctum",
                    label: "성지 \(dayStops.count)",
                    tone: Color(PinImage.deep),
                    isOn: showSanctums
                ) {
                    showSanctums.toggle()
                    if !showSanctums {
                        pickedStop = nil // 지도에서 사라진 핀의 카드는 닫는다
                    }
                },
            ]
        )
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
    }

    /// 갈래 필터를 통과한 주변 점. 챗봇 결과와 겹치면 뺀다.
    var visibleAmbientPois: [RouteGuide.Place] {
        let shown = Set(guide.places.map { RouteDedupe.key($0.asPlaceSummary) })
        return ambientPois.filter { place in
            poiGroupsOn.contains(place.poiGroup)
                && !shown.contains(RouteDedupe.key(place.asPlaceSummary))
        }
    }

    /// 카메라가 멈췄다 — 편집 화면과 같은 규칙(0.35초 조용하면, 줌 13 미만은 안 부름).
    func viewportChanged(
        south: Double, west: Double, north: Double, east: Double,
        centerLat: Double, centerLng: Double, zoom: Double
    ) {
        ambientTask?.cancel()
        // 줌이 아니라 **화면의 실제 남북 폭**으로 거른다. 이 지도는 높이가
        // 300pt 라 같은 동네를 봐도 줌 숫자가 낮게 나온다 — 줌 13 가드에 늘
        // 걸려 주변 목록이 영영 비었다(2026-08-28 사용자 발견: 칩이 안 뜸).
        _ = zoom
        guard north - south <= 0.1 else { // 약 11 km — 이보다 넓으면 점이 먼지다
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

    /// 지금 안내하는 목적지 — 갈아탔으면 그 가게, 아니면 원래 촬영지.
    var destination: (name: String, latitude: Double, longitude: Double) {
        if let detour {
            return (detour.name, detour.latitude, detour.longitude)
        }
        return (stop.place.name, stop.place.latitude, stop.place.longitude)
    }

    /// 목적지를 이 가게로 갈아타고 경로를 다시 받는다.
    func reroute(to place: RouteGuide.Place) {
        detour = place
        guide.picked = nil
        result = nil // `load` 의 「이미 받았으면 안 받는다」 문을 다시 연다.
        routeError = nil
        if let here {
            Task { await load(from: here.latitude, longitude: here.longitude) }
        }
    }

    func load(from latitude: Double, longitude: Double) async {
        guard result == nil, !asking else { return }
        asking = true
        defer { asking = false }
        do {
            result = try await KakaoTransit.leg(
                from: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                to: CLLocationCoordinate2D(
                    latitude: destination.latitude,
                    longitude: destination.longitude
                ),
                destinationName: destination.name
            )
            routeError = nil
        } catch KakaoTransit.Failure.noKey {
            routeError = "길찾기 키가 없어 안내를 받을 수 없습니다"
        } catch KakaoTransit.Failure.noRoute {
            routeError = "대중교통으로 갈 수 있는 길을 찾지 못했습니다"
        } catch {
            routeError = "길찾기 안내를 받지 못했습니다"
        }
    }

    /// 방문을 서버에 남긴다(`visitedAt` — 마이페이지의 스탬프가 이것을 읽는다).
    ///
    /// 갈아탄 경유지(음식점)가 아니라 **원래 목적지(성지)에만** 찍는다 — 스탬프는
    /// 성지 순례의 기록이다. 시각은 서버가 찍는다.
    func markVisited() {
        guard !arrived else { return }
        arrived = true
        guard let courseId, let itemId = stop.serverItemId else { return }
        Task {
            try? await CoursesAPI.updateCourseItemVisit(
                xDeviceId: InstallIdentity.current,
                courseId: courseId, itemId: itemId,
                visitUpdate: VisitUpdate(visited: true)
            )
        }
    }

    /// GPS 가 목적지 반경 100 m 에 들면 **저절로** 스탬프가 찍힌다(2026-08-28
    /// 사용자 요청 — 진짜 갔다는 기록). 단추를 안 눌러도 남고, 화면은 잠깐
    /// 「스탬프 찍힘」을 알린다.
    func autoStampIfArrived(_ spot: (latitude: Double, longitude: Double)) {
        guard !arrived, detour == nil else { return }
        let hereSpot = PlaceSummary(
            id: 0, name: "여기", latitude: spot.latitude, longitude: spot.longitude
        )
        let meters = RouteGeometry.kilometers(hereSpot, stop.place) * 1000
        if meters <= 100 {
            markVisited()
            withAnimation { stamped = true }
        }
    }
}
