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

/// 검색 결과 지도의 핀 (M7) — 촬영지는 사진 동그라미(34, 고르면 46 + 보라 테두리 + 이름표), 가게는 갈래 색 점(16).
@MainActor
final class MapSearchPinRenderer {
    private var markers: [NMFMarker] = []
    private var key = ""
    /// 받아 둔 사진 — 같은 핀을 다시 그릴 때 또 받지 않는다.
    private var photos: [String: UIImage] = [:]
    private var loading: Set<String> = []
    private var redraw: () -> Void = {}

    func render(_ search: MapSearchPins, on mapView: NMFMapView, onShop: @escaping (RouteGuide.Place) -> Void) {
        let (spots, selected, shops, onSpot) = (search.spots, search.selected, search.shops, search.onSpot)
        let next = "\(spots.map { "\($0.id)" }.joined(separator: ","))|\(selected ?? -1)|"
            + "\(shops.map(\.id).joined(separator: ","))|\(photos.count)"
        guard next != key else { return }
        key = next
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
        for place in spots {
            let isOn = place.id == selected
            let photo = place.imageUrl.flatMap { photos[$0] }
            let marker = NMFMarker(position: NMGLatLng(lat: place.latitude, lng: place.longitude))
            marker.iconImage = NMFOverlayImage(image: MapPoiImages.photoPin(photo, selected: isOn))
            marker.anchor = CGPoint(x: 0.5, y: 1)
            marker.zIndex = isOn ? 60 : 50
            if isOn {
                marker.captionText = place.name
                marker.captionTextSize = 12
                marker.captionMinZoom = 0
            }
            marker.touchHandler = { _ in
                onSpot(place)
                return true
            }
            marker.mapView = mapView
            markers.append(marker)
            if photo == nil, let url = place.imageUrl {
                load(url)
            }
        }
        redraw = { [weak self, weak mapView] in
            guard let self, let mapView else { return }
            render(search, on: mapView, onShop: onShop)
        }
    }

    func clear() {
        markers.forEach { $0.mapView = nil }
        markers = []
        key = ""
        redraw = {}
    }

    /// 사진을 받아 둔다. 실패하면 보라 바탕 핀 그대로. 받으면 **마지막으로 그린 것**을 다시 그린다(그 사이 결과가 바뀌었을 수 있다).
    private func load(_ url: String) {
        guard !loading.contains(url), let parsed = URL(string: url) else { return }
        loading.insert(url)
        Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: parsed),
                  let image = UIImage(data: data) else { return }
            self?.photos[url] = image
            self?.redraw()
        }
    }
}

/// 지도에 얹는 점·핀 그림. 같은 갈래의 점은 한 번 굽고 다시 쓴다.
@MainActor
enum MapPoiImages {
    private static var minor: [RoutePoiGroup: NMFOverlayImage] = [:]
    private static var shop: [RoutePoiGroup: NMFOverlayImage] = [:]
    /// 결과 핀의 보라(웹 `#5c4ad6`).
    static let purple = UIColor(red: 0.36, green: 0.29, blue: 0.84, alpha: 1)

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

    /// 사진 동그라미 핀 — 흰 테두리(고르면 보라 3) · 그림자 · 아래 작은 꼬리. 사진이 없으면 보라 바탕에 흰 점.
    static func photoPin(_ photo: UIImage?, selected: Bool) -> UIImage {
        let side = selected ? MapSearchResults.pinBig : MapSearchResults.pinSmall
        let pad: CGFloat = 4
        let size = CGSize(width: side + pad * 2, height: side + 6 + pad)
        return UIGraphicsImageRenderer(size: size).image { context in
            let ctx = context.cgContext
            let circle = CGRect(x: pad, y: pad, width: side, height: side)
            let ring = selected ? purple : UIColor.white
            // 꼬리 — 동그라미 아래 가운데의 작은 마름모.
            let tip = CGPoint(x: size.width / 2, y: size.height)
            let tail = UIBezierPath()
            tail.move(to: tip)
            tail.addLine(to: CGPoint(x: tip.x - 6, y: circle.maxY - 3))
            tail.addLine(to: CGPoint(x: tip.x + 6, y: circle.maxY - 3))
            tail.close()
            let shade = UIColor.black.withAlphaComponent(0.3).cgColor
            ctx.setShadow(offset: CGSize(width: 0, height: 2), blur: 4, color: shade)
            ring.setFill()
            tail.fill()
            UIBezierPath(ovalIn: circle).fill()
            ctx.setShadow(offset: .zero, blur: 0, color: nil)
            let border: CGFloat = selected ? 3 : 2.5
            let face = circle.insetBy(dx: border, dy: border)
            ctx.saveGState()
            UIBezierPath(ovalIn: face).addClip()
            if let photo {
                let scale = max(face.width / photo.size.width, face.height / photo.size.height)
                let drawn = CGSize(width: photo.size.width * scale, height: photo.size.height * scale)
                photo.draw(in: CGRect(x: face.midX - drawn.width / 2, y: face.midY - drawn.height / 2,
                                      width: drawn.width, height: drawn.height))
            } else {
                purple.setFill()
                UIRectFill(face)
                UIColor.white.setFill()
                let dot = face.width * 0.3
                let spot = CGRect(x: face.midX - dot / 2, y: face.midY - dot / 2, width: dot, height: dot)
                UIBezierPath(ovalIn: spot).fill()
            }
            ctx.restoreGState()
        }
    }
}

/// 지도 검색 결과 지도의 카메라 명령 (M7) — 결과 전체에 맞추기, 고른 곳으로 옮기기.
struct MapCameraCommand: Equatable {
    enum Action: Equatable {
        case fit(south: Double, west: Double, north: Double, east: Double)
        /// 그곳으로 — 확대는 지금과 `minZoom` 중 큰 것.
        case focus(latitude: Double, longitude: Double, minZoom: Double)
    }

    let id: Int
    let action: Action
}

/// 결과 지도가 지도에 넘기는 것 — 촬영지 사진 핀·가게 색 점·카메라 명령(`id` 가 바뀔 때만 움직인다).
struct MapSearchPins {
    var spots: [PlaceSummary] = []
    var selected: Int64?
    var shops: [RouteGuide.Place] = []
    var camera: MapCameraCommand?
    var onSpot: (PlaceSummary) -> Void = { _ in }
}

extension RouteMapView.Coordinator {
    func renderMapSearch(minor: [RouteGuide.Place], search: MapSearchPins, on mapView: NMFMapView) {
        minorRenderer.render(minor, on: mapView) { [weak self] in self?.onTapGuide($0) }
        searchRenderer.render(search, on: mapView) { [weak self] in self?.onTapGuide($0) }
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
            case let .focus(latitude, longitude, minZoom):
                update = NMFCameraUpdate(
                    scrollTo: NMGLatLng(lat: latitude, lng: longitude), zoomTo: max(mapView.zoomLevel, minZoom)
                )
            }
            update.animation = .easeIn
            mapView.moveCamera(update)
        }
    }
}
