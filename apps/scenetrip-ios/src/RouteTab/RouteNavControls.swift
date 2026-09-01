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
        onVisited?(stop)
        guard let courseId, let itemId = stop.serverItemId else { return }
        Task {
            try? await CoursesAPI.updateCourseItemVisit(
                xDeviceId: InstallIdentity.current,
                courseId: courseId, itemId: itemId,
                visitUpdate: VisitUpdate(visited: true)
            )
        }
    }

    /// 목적지 반경 안에 **머무르면** 저절로 스탬프가 찍힌다(2026-08-28 「반경에 들면」
    /// → 2026-09-02 「머무르면」, 계획 trip-mode.md §2). 지나가기만 한 것은 도착이 아니다.
    /// 위치가 올 때마다 부른다 — `TripArrival` 이 연속 체류 시간을 센다.
    func autoStampIfArrived(_ spot: (latitude: Double, longitude: Double)) {
        guard !arrived, detour == nil else { return }
        let hereSpot = PlaceSummary(
            id: 0, name: "여기", latitude: spot.latitude, longitude: spot.longitude
        )
        let meters = RouteGeometry.kilometers(hereSpot, stop.place) * 1000
        if tripArrival.observe(distanceMeters: meters) {
            markVisited()
            withAnimation { stamped = true }
        }
    }

    /// 지금 목적지의 번호(1부터). 일차 목록에 없는 곳(오늘의 성지)이면 nil.
    var stopNumber: Int? {
        dayStops.firstIndex { $0.id == stop.id }.map { $0 + 1 }
    }

    /// 스탬프 다음 — **다음 미방문 성지로 목적지를 바꾼다.** 서버에 방문이 찍힌 것과
    /// 이 화면에서 찍은 것을 다 뺀 첫 정지점이다. 남은 것이 없으면 오늘 일정 완료.
    func advanceToNextStop() {
        visitedIds.insert(stop.id)
        stamped = false
        let next = dayStops.first { !$0.visited && !visitedIds.contains($0.id) && $0.id != stop.id }
        guard let next else {
            dayDone = true
            return
        }
        stop = next
        arrived = false
        detour = nil
        result = nil
        routeError = nil
        guide.picked = nil
        pickedStop = nil
        tripArrival = TripArrival()
        demoPathIndex = 0
        recenterTick += 1
        if let here {
            Task { await load(from: here.latitude, longitude: here.longitude) }
        }
    }

    /// 데모 주행 한 걸음(0.4초마다). 목적지까지의 경로선을 따라 움직이고, 반경 30 m 안에
    /// 들면 그 자리에 서서 머무름(스탬프)을 기다린다. `-demoDrive N` 번 성지를 지나면 멈춘다.
    /// 도착 판정·스탬프·다음 성지는 실제 규칙이 한다 — 여기는 위치만 낸다.
    func demoStep() {
        guard DemoDrive.isOn, !dayDone, !stamped,
              let number = stopNumber, number <= DemoDrive.untilStop
        else { return }
        let target: DemoDrive.Point = (destination.latitude, destination.longitude)
        var position = demoPosition ?? here.map { ($0.latitude, $0.longitude) } ?? DemoDrive.start(near: stop)
        if DemoDrive.meters(position, target) > DemoDrive.stopWithinMeters {
            let path = (result?.legs ?? []).flatMap(\.path).compactMap { pair -> DemoDrive.Point? in
                pair.count >= 2 ? (pair[1], pair[0]) : nil // [경도, 위도] 순으로 온다
            }
            position = DemoDrive.step(
                from: position, along: path, index: &demoPathIndex,
                toward: target, meters: DemoDrive.metersPerSecond * DemoDrive.tick
            )
            demoPosition = position
        }
        // 서 있을 때도 같은 자리를 다시 넣는다 — 머무름 판정과 파문이 이어진다.
        locator.inject(latitude: position.latitude, longitude: position.longitude)
    }
}
