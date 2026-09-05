import NMapsMap
import SwiftUI

/// 편집 지도의 **여행 안내** — 내 자리(파문)·실제 경로선·안내 카메라
/// (계획 trip-mode.md §8, 2026-09-03).
///
/// `RouteMapView.swift` 에서 떼어 냈다(타입 길이 한도). 지도 그리기와 만나는 곳이
/// `render` 의 몇 줄뿐이라 자르는 선이 깨끗하다.
extension RouteMapView.Coordinator {
    /// 여행 안내의 자리를 받는다. 있으면 그 자리에 파문을 띄우고, 없어지면 놓는다.
    func applyTrip(here spot: TripSpot?, on mapView: NMFMapView) {
        guard let spot else {
            tripHere = nil
            return
        }
        let latLng = NMGLatLng(lat: spot.latitude, lng: spot.longitude)
        tripHere = latLng
        here = latLng
        showPulse(on: mapView)
    }

    /// **직선이다.** 8/11 회의 2부 확정 — 여행 전 계획에서는 길찾기 API 를 부르지
    /// 않으므로 실제 도로 궤적을 알 수 없다. 곡선으로 그리면 실제 경로처럼
    /// 읽히므로 오히려 거짓말이 된다.
    ///
    /// **다녀온 곳에서 나가는 선은 긋지 않는다**(2026-09-03 사용자 요청). 1번을 찍고 나면
    /// 「1번 → 2번」 계획선은 할 일을 다했다 — 그 자리는 「내 자리 → 2번」 미리보기
    /// (`renderPreview`)와 실제 경로가 맡는다. 남은 곳끼리의 선은 그대로다.
    ///
    /// 단 **지금 가는 중인 곳**(`keepFrom`)에서 나가는 선은 남긴다 — 앞선 여행에서 이미
    /// 찍은 1번으로 다시 가는 동안에도 「1번 다음은 2번」이 지도에 보여야 한다(사용자
    /// 지적: "현재 위치에서 1번 갈 땐 2번까지의 직선은 아직 유지").
    func drawLine(_ stops: [RouteStop], keepFrom: RouteStop? = nil, on mapView: NMFMapView) {
        planPaths.forEach { $0.mapView = nil }
        planPaths = []
        guard stops.count > 1 else { return }
        var run: [NMGLatLng] = []
        func flush() {
            if run.count > 1, let line = NMFPath(points: run) {
                line.color = PinImage.deep
                line.outlineColor = .white
                line.width = 4
                line.outlineWidth = 1
                line.mapView = mapView
                planPaths.append(line)
            }
            run = []
        }
        for stop in stops {
            run.append(NMGLatLng(lat: stop.place.latitude, lng: stop.place.longitude))
            if stop.visited, stop.id != keepFrom?.id {
                flush() // 다녀온 곳까지는 잇되, 거기서 나가는 선은 끊는다.
            }
        }
        flush()
    }

    /// 「내 자리 → 다음 곳」 직선 미리보기. 실제 경로가 오면 부르는 쪽이 `nil` 을 줘 지운다.
    /// 계획선과 같은 굵기·색이되 **점선**이다 — 아직 길이 아니라 방향이라는 뜻.
    func renderPreview(to target: RouteStop?, on mapView: NMFMapView) {
        let key = target.map { "\($0.id)|\(here?.lat ?? 0),\(here?.lng ?? 0)" } ?? "-"
        guard key != lastPreviewKey else { return }
        lastPreviewKey = key
        previewPath?.mapView = nil
        previewPath = nil
        guard let target, let here,
              let line = NMFPath(points: [
                  here, NMGLatLng(lat: target.place.latitude, lng: target.place.longitude),
              ])
        else { return }
        line.color = PinImage.deep.withAlphaComponent(0.55)
        line.outlineWidth = 0
        line.width = 5
        line.patternInterval = 12
        line.patternIcon = NMFOverlayImage(image: Self.dash())
        line.zIndex = 4
        line.mapView = mapView
        previewPath = line
    }

    /// 발자취 — 지나온 자리마다 **황금 발자국** 하나. 진행 방향으로 돌리고, 왼발·오른발처럼
    /// 번갈아 살짝 비껴 찍는다. 25 m 마다 한 점이라 수백 개여도 가볍다.
    ///
    /// **화면 간격은 줌과 무관하게 일정하다** — 기록은 25 m 마다지만 축소하면 자국이 겹쳐 노란
    /// 줄이 됐다(2026-09-04 사용자 지적). 줌에 맞춰 솎아, 자국 사이가 화면에서 약 28pt 이상
    /// (최소 35 m)이 되게 한다. 카메라가 움직이면 다시 솎는다.
    func renderFootprints(_ all: [FootprintPoint], on mapView: NMFMapView) {
        lastFootPoints = all
        let metersPerPoint = mapView.projection.metersPerPixel()
        // 50 m · 40pt 는 너무 성겼다(2026-09-05 사용자 지적) → 35 m · 28pt.
        let minMeters = max(35, 28 * metersPerPoint)
        var points: [FootprintPoint] = []
        for point in all {
            if let last = points.last {
                let gap = NMGLatLng(lat: last.latitude, lng: last.longitude)
                    .distance(to: NMGLatLng(lat: point.latitude, lng: point.longitude))
                if gap < minMeters {
                    continue
                }
            }
            points.append(point)
        }
        let key = "\(all.count)|\(all.last?.at.timeIntervalSince1970 ?? 0)|\(Int(minMeters))"
        guard key != lastFootKey else { return }
        lastFootKey = key
        footMarkers.forEach { $0.mapView = nil }
        footMarkers = []
        for (index, point) in points.enumerated() {
            let marker = NMFMarker(position: NMGLatLng(lat: point.latitude, lng: point.longitude))
            marker.iconImage = PinoPin.footprint()
            marker.anchor = CGPoint(x: 0.5, y: 0.5)
            marker.zIndex = -2 // 계획선·경로선 아래
            marker.isHideCollidedMarkers = false
            // 다음 점을 향하는 각도. 마지막 점은 직전 점의 방향을 잇는다.
            let from = index + 1 < points.count ? point : (index > 0 ? points[index - 1] : point)
            let to = index + 1 < points.count ? points[index + 1] : point
            let dx = (to.longitude - from.longitude) * cos(from.latitude * .pi / 180)
            let dy = to.latitude - from.latitude
            if dx != 0 || dy != 0 {
                marker.angle = CGFloat(atan2(dx, dy) * 180 / .pi)
            }
            marker.mapView = mapView
            footMarkers.append(marker)
        }
    }

    /// 안내 중 — 내 자리·목적지·경로선이 다 들어오게. 자리를 모르면 목적지만.
    func fitTrip(to target: RouteStop, legs: [RouteLeg], on mapView: NMFMapView) {
        let goal = NMGLatLng(lat: target.place.latitude, lng: target.place.longitude)
        var points = legs.flatMap(\.path).compactMap { pair -> NMGLatLng? in
            pair.count >= 2 ? NMGLatLng(lat: pair[1], lng: pair[0]) : nil // [경도, 위도]
        }
        points.append(goal)
        if let here {
            points.append(here)
        }
        guard points.count > 1 else {
            let update = NMFCameraUpdate(scrollTo: goal, zoomTo: 15)
            update.animation = .easeIn
            mapView.moveCamera(update)
            return
        }
        let update = NMFCameraUpdate(fit: NMGLatLngBounds(latLngs: points), padding: 56)
        update.animation = .easeIn
        update.animationDuration = 0.4
        mapView.moveCamera(update)
    }

    /// 안내 경로 — **API 가 준 실제 길 좌표를 그대로 그린다.** 구간마다 따로 그어야
    /// 도보(점선)와 대중교통(실선)이 갈린다. 계획선(직선) 위에 굵게 얹는다.
    func renderLegs(_ legs: [RouteLeg], to target: RouteStop?, on mapView: NMFMapView) {
        let key = legs.map { "\($0.id)" }.joined(separator: ",") + "|\(target?.id.uuidString ?? "-")"
        guard key != lastLegsKey else { return }
        lastLegsKey = key
        legPaths.forEach { $0.mapView = nil }
        legPaths = []
        // 카카오 경로는 목적지에서 가장 가까운 **도로 접점**에서 끝난다 — 건물 안·캠퍼스 안의
        // 핀까지 마지막 몇십 m 는 길이 없다. 그 사이를 얇은 회색 점선으로 이어 「여기서부터
        // 걸어 들어간다」가 보이게 한다(2026-09-04 사용자 결정).
        if let target,
           let lastPair = legs.last(where: { $0.path.count > 1 })?.path.last, lastPair.count >= 2,
           let line = NMFPath(points: [
               NMGLatLng(lat: lastPair[1], lng: lastPair[0]),
               NMGLatLng(lat: target.place.latitude, lng: target.place.longitude),
           ])
        {
            line.width = 4
            line.color = UIColor(red: 0.54, green: 0.58, blue: 0.65, alpha: 0.85)
            line.outlineWidth = 0
            line.patternInterval = 8
            line.patternIcon = NMFOverlayImage(image: Self.dash())
            line.zIndex = 5
            line.mapView = mapView
            legPaths.append(line)
        }
        for leg in legs where leg.path.count > 1 {
            let points = leg.path.compactMap { pair -> NMGLatLng? in
                pair.count >= 2 ? NMGLatLng(lat: pair[1], lng: pair[0]) : nil
            }
            guard points.count > 1, let line = NMFPath(points: points) else { continue }
            let dashed = leg.mode == .walk
            line.width = dashed ? 7 : 9
            line.color = UIColor(Color(PinImage.deep))
            line.outlineWidth = 0
            if dashed {
                line.patternInterval = 14
                line.patternIcon = NMFOverlayImage(image: Self.dash())
            }
            line.zIndex = 5 // 계획선 위
            line.mapView = mapView
            legPaths.append(line)
        }
    }

    private static func dash() -> UIImage {
        let size = CGSize(width: 6, height: 6)
        return UIGraphicsImageRenderer(size: size).image { _ in
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).fill()
        }
    }
}
