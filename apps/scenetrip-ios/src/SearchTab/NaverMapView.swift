import NMapsMap
import SceneApiClient
import SwiftUI

/// 네이버 지도를 SwiftUI 에 얹는다. 핀은 `pins` 가 바뀔 때만 다시 그린다.
///
/// **카메라와 핀을 따로 다룬다.** 계획서 §3-5 가 그렇게 정했다 — 검색이 확정될 때만
/// 카메라를 결과 범위로 맞추고, 카테고리 칩은 핀만 갈고 카메라는 건드리지 않는다.
/// 칩을 껐다 켤 때 보던 위치를 잃지 않게 하는 것이 그 규칙의 목적이다.
///
/// 카메라를 움직이는 계기는 셋으로 나뉜다:
/// - `fitToken` — 검색 확정·드릴다운. 결과 **전체 범위**로 맞춘다.
/// - `focusToken` — 장소 선택(목록 행·핀). `focus` 한 곳으로 **확대**한다.
/// - `panToken` — 담기(+). `pan` 한 곳이 보이게 **이동만** 한다. 줌은 그대로다.
///
/// 어느 이동이든 검색바(위)와 바텀시트(아래)가 덮는 영역을 `contentInset` 으로
/// 빼고 남는 지도 영역의 가운데에 놓는다 — 이것 없이 fit 하면 시트 뒤에 핀이
/// 숨는다(실측). 인셋은 카메라를 움직이는 순간에만 갱신한다: 시트를 드래그할
/// 때마다 갱신하면 지도가 덩달아 출렁인다.
struct NaverMapView: UIViewRepresentable {
    let pins: [PlaceSummary]

    /// 값이 바뀐 순간에만 카메라를 결과 전체 범위로 맞춘다. 칩 조작에서는 그대로 둔다.
    let fitToken: Int

    /// 값이 바뀐 순간에만 `focus` 로 카메라를 확대한다.
    let focusToken: Int
    let focus: PlaceSummary?

    /// 값이 바뀐 순간에만 `pan` 이 가운데 오도록 이동한다. 줌은 바꾸지 않는다.
    let panToken: Int
    let pan: PlaceSummary?

    /// 시트가 지도를 덮는 높이 비율. 카메라 이동 시점의 인셋 계산에 쓴다.
    let bottomInsetFraction: CGFloat
    let onTapPin: (PlaceSummary) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTapPin: onTapPin)
    }

    func makeUIView(context: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        view.showLocationButton = false
        view.mapView.logoAlign = .leftBottom
        view.mapView.moveCamera(
            NMFCameraUpdate(scrollTo: NMGLatLng(lat: 37.5666, lng: 126.9784), zoomTo: 11)
        )
        context.coordinator.attach(to: view.mapView)
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapPin = onTapPin
        context.coordinator.render(
            pins: pins,
            fitToken: fitToken,
            focusToken: focusToken,
            focus: focus,
            panToken: panToken,
            pan: pan,
            bottomInsetFraction: bottomInsetFraction,
            on: view.mapView
        )
    }

    final class Coordinator {
        var onTapPin: (PlaceSummary) -> Void
        private var markers: [NMFMarker] = []
        private var lastPinKey: String = ""

        /// 첫 렌더에서는 카메라를 맞추지 않는다 — 초기값이 fitToken 의 초기값과 같다.
        /// 첫 진입은 서울 중심으로 열려야 한다 (MZ2AZ-162, 계획서 §3-1).
        private var lastFitToken: Int = 0
        private var lastFocusToken: Int = 0
        private var lastPanToken: Int = 0
        private var bottomInsetFraction: CGFloat = 0
        private weak var mapView: NMFMapView?

        init(onTapPin: @escaping (PlaceSummary) -> Void) {
            self.onTapPin = onTapPin
        }

        func attach(to mapView: NMFMapView) {
            self.mapView = mapView
        }

        // swiftlint:disable:next function_parameter_count
        func render(
            pins: [PlaceSummary],
            fitToken: Int,
            focusToken: Int,
            focus: PlaceSummary?,
            panToken: Int,
            pan: PlaceSummary?,
            bottomInsetFraction: CGFloat,
            on mapView: NMFMapView
        ) {
            self.bottomInsetFraction = bottomInsetFraction

            let key = pins.map { String($0.id) }.joined(separator: ",")
            if key != lastPinKey {
                lastPinKey = key
                markers.forEach { $0.mapView = nil }
                // 번호는 배열 순서다 — 목록도 같은 배열을 같은 순서로 그리므로
                // "목록의 3번 = 지도의 3번" 이 성립한다.
                markers = pins.enumerated().map { index, place in
                    let marker = NMFMarker(
                        position: NMGLatLng(lat: place.latitude, lng: place.longitude)
                    )
                    marker.iconImage = PinImage.numbered(index + 1)
                    marker.captionText = place.name
                    marker.captionMinZoom = 13
                    marker.touchHandler = { [weak self] _ in
                        self?.onTapPin(place)
                        return true
                    }
                    marker.mapView = mapView
                    return marker
                }
            }

            // 카메라는 토큰이 바뀐 순간에만 움직인다 — 칩은 어느 토큰도 올리지 않는다.
            if fitToken != lastFitToken {
                lastFitToken = fitToken
                fit(pins, on: mapView)
            }
            if focusToken != lastFocusToken {
                lastFocusToken = focusToken
                if let focus {
                    zoom(to: focus, on: mapView)
                }
            }
            if panToken != lastPanToken {
                lastPanToken = panToken
                if let pan {
                    center(on: pan, in: mapView)
                }
            }
        }

        /// 검색바·시트가 덮는 영역을 빼서, 카메라 이동이 **보이는 지도 영역** 기준이
        /// 되게 한다. 이동 직전에만 부른다.
        private func applyInset(on mapView: NMFMapView) {
            let inset = UIEdgeInsets(
                top: 108,
                left: 0,
                bottom: mapView.bounds.height * bottomInsetFraction,
                right: 0
            )
            if mapView.contentInset != inset {
                mapView.contentInset = inset
            }
        }

        /// 선택한 장소 하나로 확대한다.
        private func zoom(to place: PlaceSummary, on mapView: NMFMapView) {
            applyInset(on: mapView)
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: place.latitude, lng: place.longitude),
                zoomTo: 16
            )
            update.animation = .easeIn
            update.animationDuration = 0.5
            mapView.moveCamera(update)
        }

        /// 담은 장소가 가운데 오도록 **이동만** 한다 — 확대는 장소를 열 때만 한다.
        private func center(on place: PlaceSummary, in mapView: NMFMapView) {
            applyInset(on: mapView)
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }

        private func fit(_ pins: [PlaceSummary], on mapView: NMFMapView) {
            guard !pins.isEmpty else { return }
            if pins.count == 1, let only = pins.first {
                zoom(to: only, on: mapView)
                return
            }
            applyInset(on: mapView)
            let lats = pins.map(\.latitude)
            let lngs = pins.map(\.longitude)
            let bounds = NMGLatLngBounds(
                southWest: NMGLatLng(lat: lats.min()!, lng: lngs.min()!),
                northEast: NMGLatLng(lat: lats.max()!, lng: lngs.max()!)
            )
            let update = NMFCameraUpdate(fit: bounds, padding: 32)
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }
    }
}

/// 번호가 박힌 핀 이미지.
///
/// SDK 기본 마커에 틴트를 입히면 밑그림(초록 핀)과 섞여 탁해진다 — 그래서 핀을
/// 직접 그린다. 단색 빨강은 "옛날 앱" 처럼 보인다는 피드백으로 파스텔
/// 하늘→보라 그라데이션에 그림자를 깔고, 번호는 머리의 흰 원 배지 안에 컬러로
/// 넣는다. 같은 번호는 캐시로 재사용한다: 계획서 §3-5 가 "타이핑마다 수십 개를
/// 다시 굽으면 버벅인다" 고 적은 그 비용을 피하는 장치다.
enum PinImage {
    private static var cache: [Int: NMFOverlayImage] = [:]

    /// 그라데이션 양끝 색. 목록의 번호 배지도 같은 색을 써서 핀과 짝이 맞는다.
    /// deep 은 흰 배지 위 번호에도 쓰이므로 파스텔이어도 이쪽은 살짝 진하게 둔다.
    static let deep = UIColor(red: 0.48, green: 0.41, blue: 0.93, alpha: 1) // 보라
    static let light = UIColor(red: 0.56, green: 0.80, blue: 0.97, alpha: 1) // 하늘

    static func numbered(_ number: Int) -> NMFOverlayImage {
        if let cached = cache[number] {
            return cached
        }
        let size = CGSize(width: 38, height: 50)
        let head = CGPoint(x: 19, y: 17)
        let tip = CGPoint(x: 19, y: 45)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            let cgContext = ctx.cgContext

            // 머리 원의 아래쪽 좌우(135°→45°, 시계방향으로 위를 지나)에서
            // 꼬리 끝점으로 이어 물방울 모양을 만든다.
            let path = UIBezierPath(
                arcCenter: head,
                radius: 14,
                startAngle: .pi * 0.75,
                endAngle: .pi * 0.25,
                clockwise: true
            )
            path.addLine(to: tip)
            path.close()

            // 바닥에 살짝 뜬 그림자 — 지도 위에 얹힌 입체감을 만든다.
            cgContext.saveGState()
            cgContext.setShadow(
                offset: CGSize(width: 0, height: 2),
                blur: 3.5,
                color: UIColor.black.withAlphaComponent(0.28).cgColor
            )
            deep.setFill()
            path.fill()
            cgContext.restoreGState()

            // 세로 그라데이션.
            cgContext.saveGState()
            path.addClip()
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: [light.cgColor, deep.cgColor] as CFArray,
                locations: [0, 1]
            )!
            cgContext.drawLinearGradient(
                gradient,
                start: CGPoint(x: head.x, y: 0),
                end: CGPoint(x: head.x, y: tip.y),
                options: []
            )
            cgContext.restoreGState()

            UIColor.white.setStroke()
            path.lineWidth = 1.5
            path.stroke()

            // 흰 원 배지에 컬러 번호 — 흰 바탕이라 작은 크기에서도 또렷하다.
            UIColor.white.setFill()
            UIBezierPath(
                arcCenter: head, radius: 10,
                startAngle: 0, endAngle: .pi * 2, clockwise: true
            ).fill()

            let text = "\(number)"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: number < 100 ? 11.5 : 9, weight: .heavy),
                .foregroundColor: deep,
            ]
            let textSize = text.size(withAttributes: attributes)
            text.draw(
                at: CGPoint(x: head.x - textSize.width / 2, y: head.y - textSize.height / 2),
                withAttributes: attributes
            )
        }
        let overlay = NMFOverlayImage(image: image)
        cache[number] = overlay
        return overlay
    }
}
