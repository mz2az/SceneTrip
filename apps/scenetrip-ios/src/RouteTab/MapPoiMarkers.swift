import NMapsMap
import SceneApiClient
import SwiftUI

/// 골고루 찍기의 **작은 점** (MZ2AZ-547 M4, 웹 `route/nav/mapOverlay.ts` 의 `minor`) — 보이는 9pt 갈래 색 점(흰 테두리 1.5),
/// 누르는 자리는 16pt. 칸마다 1등은 원래 점(`PoiMarkerRenderer`), 나머지가 이것이다.
@MainActor
final class MinorDotRenderer {
    private var markers: [NMFMarker] = []
    private var ids: [String] = []

    func render(_ places: [RouteGuide.Place], on mapView: NMFMapView, onSelect: @escaping (RouteGuide.Place) -> Void) {
        let next = places.map(\.id)
        guard next != ids else { return }
        ids = next
        markers.forEach { $0.mapView = nil }
        markers = places.map { place in
            let marker = NMFMarker(position: NMGLatLng(lat: place.latitude, lng: place.longitude))
            marker.iconImage = MapPoiImages.minorDot(place.poiGroup)
            marker.anchor = CGPoint(x: 0.5, y: 0.5)
            marker.zIndex = 2
            marker.isHideCollidedSymbols = false
            marker.touchHandler = { _ in
                onSelect(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }
}

/// 지도 검색의 점 (M6·M7) — 찾은 가게·장소를 갈래 색 점(16, 흰 테두리)으로. 엔터 전 목록이 떠 있는 동안에도, 엔터 뒤 결과 지도에서도 찍는다.
/// 촬영지 사진 핀은 없앴다 — 지도 검색은 촬영지를 찾지 않는다(2026-11-10 사용자 요청).
@MainActor
final class MapSearchPinRenderer {
    private var markers: [NMFMarker] = []
    private var ids: [String] = []

    func render(_ shops: [RouteGuide.Place], on mapView: NMFMapView, onShop: @escaping (RouteGuide.Place) -> Void) {
        let next = shops.map(\.id)
        guard next != ids else { return }
        ids = next
        markers.forEach { $0.mapView = nil }
        markers = shops.map { place in
            let marker = NMFMarker(position: NMGLatLng(lat: place.latitude, lng: place.longitude))
            marker.iconImage = MapPoiImages.shopDot(place.poiGroup)
            marker.anchor = CGPoint(x: 0.5, y: 0.5)
            marker.zIndex = 40
            marker.touchHandler = { _ in
                onShop(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }
}

/// 지도에 얹는 점·핀 그림. 같은 갈래의 점은 한 번 굽고 다시 쓴다.
@MainActor
enum MapPoiImages {
    private static var minor: [RoutePoiGroup: NMFOverlayImage] = [:]
    private static var shop: [RoutePoiGroup: NMFOverlayImage] = [:]

    static func minorDot(_ group: RoutePoiGroup) -> NMFOverlayImage {
        if let found = minor[group] {
            return found
        }
        let image = dot(group, visible: MapPoiSpread.minorVisible, canvas: MapPoiSpread.minorHit, border: 1.5)
        minor[group] = image
        return image
    }

    static func shopDot(_ group: RoutePoiGroup) -> NMFOverlayImage {
        if let found = shop[group] {
            return found
        }
        let image = dot(group, visible: 16, canvas: 20, border: 2.5)
        shop[group] = image
        return image
    }

    /// 갈래 색 원 + 흰 테두리 + 옅은 그림자. `canvas` 가 누르는 자리다.
    private static func dot(
        _ group: RoutePoiGroup, visible: CGFloat, canvas: CGFloat, border: CGFloat
    ) -> NMFOverlayImage {
        let image = UIGraphicsImageRenderer(size: CGSize(width: canvas, height: canvas)).image { context in
            let inset = (canvas - visible) / 2
            let rect = CGRect(x: inset, y: inset, width: visible, height: visible)
            let shade = UIColor.black.withAlphaComponent(0.3).cgColor
            context.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 2, color: shade)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: rect).fill()
            context.cgContext.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor(RoutePoiTone.of(group)).setFill()
            UIBezierPath(ovalIn: rect.insetBy(dx: border, dy: border)).fill()
        }
        return NMFOverlayImage(image: image)
    }
}

/// 지도 검색 결과 지도의 카메라 명령 (M7) — 결과 전체에 맞추기. 고른 곳으로 옮기기는 고른 핀(`pickedGuide`)의 카메라가 한다.
struct MapCameraCommand: Equatable {
    enum Action: Equatable {
        case fit(south: Double, west: Double, north: Double, east: Double)
    }

    let id: Int
    let action: Action
}

/// 지도 검색이 지도에 넘기는 것 — 찍을 점(`MapSearchRules.mapDots`)·카메라 명령(`id` 가 바뀔 때만 움직인다).
struct MapSearchPins {
    var shops: [RouteGuide.Place] = []
    var camera: MapCameraCommand?
}

extension RouteMapView.Coordinator {
    /// 고른 편의시설·검색 결과로 — **덮이지 않은 지도의 가운데에** 오게(2026-11-10 사용자 지적: 「핀과 효과 위치가 일치하지 않는다」).
    /// 아래 시트·결과 서랍은 `contentInset` 이 이미 비켜 주고, 정보 카드(적어도 300pt)가 그보다 높으면 그만큼 더 올린다
    /// (`MapSearchRules.extraLift`). 확대는 지금과 16 중 큰 것 — 더 확대해 둔 화면을 풀지 않는다.
    func move(to place: RouteGuide.Place, on mapView: NMFMapView) {
        region.source = .userSelected
        let zoom = MapSearchRules.focusZoom(current: mapView.zoomLevel)
        let target = MapSearchRules.focusTarget(
            latitude: place.latitude, longitude: place.longitude,
            lift: MapSearchRules.extraLift(contentInset: mapView.contentInset.bottom),
            metersPerPoint: mapView.projection.metersPerPixel(atLatitude: place.latitude, zoom: zoom)
        )
        let update = NMFCameraUpdate(scrollTo: NMGLatLng(lat: target.latitude, lng: target.longitude), zoomTo: zoom)
        update.animation = .easeIn
        mapView.moveCamera(update)
    }

    func renderMapSearch(minor: [RouteGuide.Place], search: MapSearchPins, on mapView: NMFMapView) {
        minorRenderer.render(minor, on: mapView) { [weak self] in self?.onTapGuide($0) }
        searchRenderer.render(search.shops, on: mapView) { [weak self] in self?.onTapGuide($0) }
        apply(search.camera, on: mapView)
    }

    /// 서랍이 덮은 아래는 지도의 `contentInset` 이 이미 뺀다 — 여기서는 둘레 여백만 준다.
    func apply(_ command: MapCameraCommand?, on mapView: NMFMapView) {
        guard let command, command.id != lastSearchCamera else { return }
        lastSearchCamera = command.id
        region.source = .userSelected
        DispatchQueue.main.async { [weak mapView] in
            guard let mapView else { return }
            let update: NMFCameraUpdate
            switch command.action {
            case let .fit(south, west, north, east):
                if max(north - south, east - west) < 0.002 {
                    let middle = NMGLatLng(lat: (south + north) / 2, lng: (west + east) / 2)
                    update = NMFCameraUpdate(scrollTo: middle, zoomTo: 16)
                } else {
                    let bounds = NMGLatLngBounds(
                        southWest: NMGLatLng(lat: south, lng: west), northEast: NMGLatLng(lat: north, lng: east)
                    )
                    let padding = UIEdgeInsets(top: 80, left: 40, bottom: 40, right: 40)
                    update = NMFCameraUpdate(fit: bounds, paddingInsets: padding)
                }
            }
            update.animation = .easeIn
            mapView.moveCamera(update)
        }
    }
}
