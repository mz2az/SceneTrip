import NMapsMap
import SwiftUI

/// 길찾기 결과 지도.
///
/// 코스 지도(`RouteMapView`)와 갈라 둔 이유는 **그리는 것이 다르기** 때문이다.
/// 코스 지도는 담은 곳을 번호 핀으로 찍고 직선으로 잇는다. 여기는 한 구간의
/// **실제 경로**와 그 주변 편의시설을 그린다 — 겹치는 것은 지도를 띄운다는 사실뿐이다.
///
/// ## 편의시설은 점으로만 그린다
///
/// 이름표(캡션)를 달 수 있으나 달지 않는다. 반경 안에 수십 개가 잡히면 이름표가
/// 서로 겹쳐 **정작 봐야 할 경로선을 가린다.** 이름은 화면 아래 목록이 맡고,
/// 지도와 목록의 점 색이 같아서 둘이 이어진다.
///
/// ## 값이 목이다
///
/// 좌표는 지어낸 것이다. 서버가 실제 경로 좌표를 주면(`NextLeg`) `path` 를 채우는
/// 쪽만 바뀌고 이 파일의 그리는 규칙은 그대로다.
struct RouteNavMapView: UIViewRepresentable {
    let stop: RouteStop

    /// 그 일차의 코스 전체. **번호 핀으로 함께 그린다** — 목적지 하나만 보이면
    /// 「다음에 어디로 가는 여정 중인가」가 지도에서 사라진다(2026-08-28 사용자
    /// 요청). 번호는 편집 화면의 번호와 같다.
    var dayStops: [RouteStop] = []

    /// 갈아탄 목적지. 있으면 **여기에 피노를 꽂는다** — 경로선은 이미 여기로
    /// 이어지는데 핀만 옛 촬영지에 남아 있으면 지도가 거짓말을 한다.
    var goal: (latitude: Double, longitude: Double)?

    /// 챗봇이 찾아 준 곳. **묻기 전에는 비어 있다.**
    ///
    /// 앞서 `RouteNavMock.pois`(지어낸 여섯 곳)를 늘 그렸다. 편의시설은 이제
    /// 챗봇에게 물었을 때만 뜬다(2026-08-27 방향 변경) — 자세한 것은
    /// `RouteNavView.guidePlaces` 주석.
    let guidePlaces: [RouteGuide.Place]

    /// 지금 고른 곳. **빨갛고 크게** 그린다.
    var picked: RouteGuide.Place?

    /// 핀을 눌렀다. 정보 카드를 띄우는 쪽이 받는다.
    var onTapPlace: (RouteGuide.Place) -> Void = { _ in }

    /// 구간별 실제 길 좌표. 비어 있으면 경로선을 그리지 않는다 —
    /// **직선으로 대신 긋지 않는다.** 그 선은 사실이 아니다.
    let legs: [RouteLeg]

    /// 지금 위치. **못 받았으면 nil 이고, 그러면 파란 점을 그리지 않는다** —
    /// 지어낸 자리에 「내가 여기 있다」를 찍는 것이 아무 표시도 없는 것보다 나쁘다.
    let here: (latitude: Double, longitude: Double)?

    /// 화면 범위 안의 주변 편의시설 — 편집 지도와 같은 배경 점. **기본은 빈
    /// 목록**이다(길찾기에서는 꺼진 것이 기본값, 2026-08-28 사용자 결정).
    var ambientPlaces: [RouteGuide.Place] = []

    /// 카메라가 멈췄다. (남, 서, 북, 동, 가운데위도, 가운데경도, 줌).
    var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?

    func makeUIView(context: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        view.showLocationButton = false
        view.mapView.logoAlign = .leftBottom
        // 파문·주변 갱신이 카메라를 들어야 한다 — 파문이 처음 뜰 때가 아니라
        // **지도를 만들 때** 등록한다. 안 그러면 위치를 받기 전까지 카메라
        // 멈춤(idle)을 못 들어 주변 목록이 영영 안 온다.
        view.mapView.addCameraDelegate(delegate: context.coordinator)
        context.coordinator.onTapPlace = onTapPlace
        context.coordinator.onViewport = onViewport
        context.coordinator.render(stop: stop, dayStops: dayStops, goal: goal,
                                   guidePlaces: guidePlaces, picked: picked,
                                   here: here, legs: legs, on: view.mapView)
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapPlace = onTapPlace
        context.coordinator.onViewport = onViewport
        context.coordinator.renderAmbient(ambientPlaces, picked: picked, on: view.mapView)
        context.coordinator.render(stop: stop, dayStops: dayStops, goal: goal,
                                   guidePlaces: guidePlaces, picked: picked,
                                   here: here, legs: legs, on: view.mapView)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    /// 지도를 그리는 일은 전부 메인 스레드에서 일어난다(UIKit 규칙). 명시해 두면
    /// 피노 핀처럼 SwiftUI 를 굽는 것도 여기서 그대로 부를 수 있다.
    @MainActor
    final class Coordinator: NSObject, NMFMapViewCameraDelegate {
        private var path: NMFPath?
        private var walkPaths: [NMFPath] = []
        private var markers: [NMFMarker] = []
        private var lastKey = ""
        var onTapPlace: (RouteGuide.Place) -> Void = { _ in }
        var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?
        /// 주변 편의시설 마커 — 경로·추천 마커와 살림을 따로 낸다(갱신 주기가 다르다).
        var ambientMarkers: [NMFMarker] = []
        var lastAmbientKey = ""

        /// 레이더 파문. 지도 마커가 아니라 **지도 위에 얹은 뷰**다 — 이유는
        /// `RadarPulse` 머리말 참고.
        private var pulse: RadarPulse?
        /// 파문이 서 있어야 할 지도 좌표. 카메라가 움직이면 화면 좌표가 달라지므로
        /// 이 값을 들고 다니며 다시 계산한다.
        private var pulseAt: NMGLatLng?
        private weak var host: NMFMapView?

        func render(
            stop: RouteStop,
            dayStops: [RouteStop] = [],
            goal: (latitude: Double, longitude: Double)?,
            guidePlaces: [RouteGuide.Place],
            picked: RouteGuide.Place?,
            here: (latitude: Double, longitude: Double)?,
            legs: [RouteLeg],
            on mapView: NMFMapView
        ) {
            let spot = here.map { "\($0.latitude),\($0.longitude)" } ?? "-"
            let goalKey = goal.map { "\($0.latitude),\($0.longitude)" } ?? "-"
            let shape = legs.map { "\($0.mode)\($0.path.count)" }.joined(separator: "-")
            let key = "\(stop.id)|\(spot)|\(goalKey)|\(shape)|"
                + guidePlaces.map(\.id).joined(separator: ",")
                + "|" + dayStops.map { "\($0.id)" }.joined(separator: ",")
            guard key != lastKey else { return }
            lastKey = key

            clear()

            let target = NMGLatLng(
                lat: goal?.latitude ?? stop.place.latitude,
                lng: goal?.longitude ?? stop.place.longitude
            )
            // 위치를 못 받았으면 목적지만 보여 준다. 경로선도 파란 점도 없다 —
            // 출발지를 모르는데 선을 그으면 그 선이 거짓말이 된다.
            guard let here else {
                let only = NMFMarker(position: target)
                only.iconImage = PinoPin.marker()
                only.mapView = mapView
                markers.append(only)
                let update = NMFCameraUpdate(scrollTo: target, zoomTo: 14)
                mapView.moveCamera(update)
                return
            }

            let start = NMGLatLng(lat: here.latitude, lng: here.longitude)

            // **API 가 준 실제 길 좌표를 그대로 그린다.** 구간마다 따로 그어야
            // 도보(점선)와 대중교통(실선)이 갈린다 — 어디를 걷는지가 한눈에 보여야
            // 짐을 든 사람이 준비할 수 있다.
            //
            // 좌표는 `[경도, 위도]` 순서로 온다. 뒤집으면 지도가 태평양 한가운데를 그린다.
            var drawn: [NMGLatLng] = []
            for leg in legs where leg.path.count > 1 {
                let points = leg.path.compactMap { pair -> NMGLatLng? in
                    guard pair.count >= 2 else { return nil }
                    return NMGLatLng(lat: pair[1], lng: pair[0])
                }
                guard points.count > 1 else { continue }
                draw(points: points, on: mapView, dashed: leg.mode == .walk)
                drawn += points
            }

            // 목적지는 **피노**다. 여기는 목적지가 하나뿐이라 번호가 필요 없어
            // 얼굴을 넣을 자리가 있다 — 코스 목록 쪽은 ①②③ 이 목록과 지도를 잇는
            // 끈이라 그대로 둔다(`PinoPin` 머리말).
            let goal = NMFMarker(position: target)
            goal.iconImage = PinoPin.marker()
            goal.zIndex = 20
            goal.mapView = mapView
            markers.append(goal)

            // 코스의 다른 곳들 — **번호 핀 그대로.** 지금 가는 곳은 피노가 서
            // 있으니 번호를 겹쳐 그리지 않는다. 단 **갈아탔을 때는 예외다** —
            // 피노가 음식점으로 옮겨 가면 원래 목적지가 고양이도 번호도 아닌
            // 상태가 되어 지도에서 사라졌다(2026-08-28 사용자 발견: 경복궁으로
            // 가다 음식점을 끼워 넣으니 3번 핀이 증발). 들렀다가 이어 갈 곳이
            // 지도에 남아 있어야 여정이 이어진다.
            for (index, dayStop) in dayStops.enumerated()
                where !(goal == nil && dayStop.id == stop.id)
            {
                let marker = NMFMarker(position: NMGLatLng(
                    lat: dayStop.place.latitude, lng: dayStop.place.longitude
                ))
                marker.iconImage = PinImage.numbered(index + 1)
                marker.captionText = dayStop.place.name
                marker.captionMinZoom = 12
                marker.zIndex = 10
                marker.mapView = mapView
                markers.append(marker)
            }

            // 현재 위치 — **레이더 파문.** 정지된 점은 촬영지 핀·편의시설 점 사이에
            // 묻힌다. 움직이는 것은 눈이 먼저 잡는다.
            showPulse(at: start, on: mapView)

            // **가이드가 찾아 준 곳은 진짜 좌표다.** 앞서 목 데이터는 좌표가 없어
            // 목적지 둘레에 흩뿌렸는데(`spread`), 이제 서버가 실제 자리를 준다.
            //
            // **빨간 점이 추천, 고른 하나만 빨간 고양이.** 고양이 열다섯이 몰리면
            // 서로 겹쳐 지도가 고양이밭이 된다(2026-08-27 사용자 결정).
            for place in guidePlaces {
                let marker = NMFMarker(
                    position: NMGLatLng(lat: place.latitude, lng: place.longitude)
                )
                let isPicked = place.id == picked?.id
                marker.iconImage = isPicked ? PinoPin.marker(.picked) : PinoPin.guideDot(place.poiGroup)
                marker.anchor = isPicked ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 0.5, y: 0.5)
                marker.captionText = place.name
                marker.captionMinZoom = 14
                marker.zIndex = isPicked ? 30 : 5
                marker.touchHandler = { [weak self] _ in
                    self?.onTapPlace(place)
                    return true
                }
                marker.mapView = mapView
                markers.append(marker)
            }

            // 경로 전체가 보이게 맞춘다. 좌표가 없으면 출발지·목적지 둘만으로.
            // 카메라의 우선순위 —
            //   고른 가게가 있으면 **그 핀으로 확대**,
            //   추천이 와 있으면 **추천만** 다 보이는 가장 확대된 화면,
            //   아니면 경로 전체.
            // 추천을 경로(수십 km)와 섞어 맞추면 반경 300 m 짜리 점 열다섯이
            // 한 점으로 보인다(2026-08-27 사용자 지적).
            if let picked {
                let update = NMFCameraUpdate(
                    scrollTo: NMGLatLng(lat: picked.latitude, lng: picked.longitude),
                    zoomTo: 16
                )
                update.animation = .easeIn
                mapView.moveCamera(update)
                return
            }
            let guideSpots = guidePlaces.map { NMGLatLng(lat: $0.latitude, lng: $0.longitude) }
            let extent = guideSpots.isEmpty
                ? (drawn.isEmpty ? [start, target] : drawn + [start, target])
                : guideSpots
            let bounds = NMGLatLngBounds(latLngs: extent)
            let update = NMFCameraUpdate(fit: bounds, padding: 56)
            mapView.moveCamera(update)
        }

        // MARK: 레이더 파문

        /// 지도 위에 파문을 얹고 자리를 잡는다.
        private func showPulse(at spot: NMGLatLng, on mapView: NMFMapView) {
            host = mapView
            pulseAt = spot

            if pulse == nil {
                let view = RadarPulse(tint: UIColor(Color.accentColor))
                mapView.addSubview(view)
                pulse = view
            }
            pulse?.restartIfNeeded()
            positionPulse()
        }

        private func clearPulse() {
            pulse?.removeFromSuperview()
            pulse = nil
            pulseAt = nil
        }

        /// 지도 좌표를 화면 좌표로 바꿔 파문을 그 자리에 놓는다.
        private func positionPulse() {
            guard let pulse, let pulseAt, let host else { return }
            pulse.place(at: host.projection.point(from: pulseAt))
        }

        /// 지도가 움직이는 **동안 계속** 부른다. 멈춘 뒤에만 옮기면 미는 사이에
        /// 파문이 제자리에 남아 따라오지 않는 것처럼 보인다.
        nonisolated func mapView(_: NMFMapView, cameraIsChangingByReason _: Int) {
            Task { @MainActor in self.positionPulse() }
        }

        nonisolated func mapView(_: NMFMapView, cameraDidChangeByReason _: Int, animated _: Bool) {
            Task { @MainActor in self.positionPulse() }
        }

        private func draw(points: [NMGLatLng], on mapView: NMFMapView, dashed: Bool) {
            // `NMFPath(points:)` 는 옵셔널을 돌려준다 — 점이 둘보다 적으면 nil 이다.
            guard let line = NMFPath(points: points) else { return }
            line.width = dashed ? 7 : 9
            line.color = UIColor(Color(PinImage.deep))
            line.outlineWidth = 0
            if dashed {
                line.patternInterval = 14
                line.patternIcon = NMFOverlayImage(image: Self.dash())
            }
            line.mapView = mapView
            if dashed {
                walkPaths.append(line)
            } else {
                path = line
            }
        }

        private func clear() {
            path?.mapView = nil
            path = nil
            walkPaths.forEach { $0.mapView = nil }
            walkPaths = []
            markers.forEach { $0.mapView = nil }
            markers = []
            clearPulse()
        }

        /// 목 좌표를 목적지 둘레에 흩는다. 실제 좌표가 오면 이 함수는 사라진다.
        private static func spread(around center: NMGLatLng, id: Int64) -> NMGLatLng {
            let angle = Double(id) * 1.05
            let radius = 0.004 + Double(id % 3) * 0.0025
            return NMGLatLng(
                lat: center.lat - 0.006 + cos(angle) * radius,
                lng: center.lng - 0.008 + sin(angle) * radius
            )
        }

        private static func poiDot(_ color: Color) -> UIImage {
            dot(size: 14, fill: UIColor(color), ring: 2.5)
        }

        /// 흰 테두리를 두른 원. 테두리가 없으면 지도 배경 위에서 점이 묻힌다.
        private static func dot(size: CGFloat, fill: UIColor, ring: CGFloat) -> UIImage {
            let bounds = CGRect(x: 0, y: 0, width: size, height: size)
            return UIGraphicsImageRenderer(size: bounds.size).image { context in
                let shadow = bounds.insetBy(dx: 0.5, dy: 0.5)
                context.cgContext.setShadow(
                    offset: .zero, blur: 2, color: UIColor.black.withAlphaComponent(0.28).cgColor
                )
                UIColor.white.setFill()
                UIBezierPath(ovalIn: shadow).fill()
                context.cgContext.setShadow(offset: .zero, blur: 0, color: nil)
                fill.setFill()
                UIBezierPath(ovalIn: bounds.insetBy(dx: ring, dy: ring)).fill()
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
}
