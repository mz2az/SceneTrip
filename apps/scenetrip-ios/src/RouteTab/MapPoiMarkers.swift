import NMapsMap
import SceneApiClient
import SwiftUI

/// 지도 검색 결과 핀 (M7) — 찾은 가게·장소를 기본 지도 1등과 같은 갈래 아이콘(`PinoPin.guideDot`)으로. 점이 아니다(§7 구글처럼).
/// 엔터 뒤 결과 서랍이 떠 있을 때만 찍는다(글자 치는 동안은 찍지 않는다). 더 받으면 핀도 는다.
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
            marker.iconImage = PinoPin.guideDot(for: place)
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

/// 지도 검색 결과 지도의 카메라 명령 (M7) — 결과 전체에 맞추기. 고른 곳으로 옮기기는 고른 핀(`pickedGuide`)의 카메라가 한다.
struct MapCameraCommand: Equatable {
    enum Action: Equatable {
        case fit(south: Double, west: Double, north: Double, east: Double)
    }

    let id: Int
    let action: Action
}

/// 지도 검색이 지도에 넘기는 것 — 찍을 핀(`MapSearchRules.mapDots`)·카메라 명령(`id` 가 바뀔 때만 움직인다).
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

    func renderMapSearch(_ search: MapSearchPins, on mapView: NMFMapView) {
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
