import NMapsMap
import SceneApiClient
import SwiftUI

/// 길찾기 화면의 **경로 받기와 방문 스탬프** — 본체(`RouteNavView.swift`)에서
/// 가른 파일이다(타입 길이 한도). 상태는 본체가 들고, 여기는 그 상태로 그리고 적는다.
///
/// 갈래 칩·주변 편의시설 배경 점은 여기 있다가 뺐다(2026-08-28 사용자 결정) —
/// 길찾기에서 편의시설은 챗봇이 찾아 준 것만 그린다.
extension RouteNavView {
    /// 지도에 그릴 챗봇 결과 — **코스(그 일차)에 이미 담긴 곳은 뺀다.** 같은
    /// 좌표에 번호 핀과 겹쳐 두 장으로 보인다(편집 지도와 같은 규칙).
    var navGuidePlaces: [RouteGuide.Place] {
        let taken = Set(dayStops.map { RouteDedupe.key($0.place) })
        return guide.places.filter { !taken.contains(RouteDedupe.key($0.asPlaceSummary)) }
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
