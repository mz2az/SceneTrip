import NMapsMap
import SwiftUI
import UIKit

/// 편집·여행 지도에서 같은 묶음 표시·선택 규칙을 쓴다. 카메라 idle 때 축척만 다시 계산한다.
@MainActor
final class PoiClusterRenderer {
    private var markers: [NMFMarker] = []
    private var places: [RouteGuide.Place] = []
    private var picked: RouteGuide.Place?
    private var groups: [PoiClusters.Cluster] = []
    private var language: String?
    private var pickedId: String?
    private var onSelect: (RouteGuide.Place) -> Void = { _ in }
    private var icons: [Int: NMFOverlayImage] = [:]
    private weak var listController: UIViewController?

    func render(
        _ places: [RouteGuide.Place], picked: RouteGuide.Place?,
        on mapView: NMFMapView, onSelect: @escaping (RouteGuide.Place) -> Void
    ) {
        self.places = places
        self.picked = picked
        self.onSelect = onSelect
        refresh(on: mapView)
    }

    func refresh(on mapView: NMFMapView) {
        let next = PoiClusters.make(places, zoom: mapView.zoomLevel, pickedId: picked?.id)
        guard next != groups || pickedId != picked?.id || language != AppLanguage.current.rawValue else { return }
        groups = next
        pickedId = picked?.id
        language = AppLanguage.current.rawValue
        markers.forEach { $0.mapView = nil }
        markers = next.map { marker(for: $0, on: mapView) }
    }

    private func marker(for cluster: PoiClusters.Cluster, on mapView: NMFMapView) -> NMFMarker {
        let marker = NMFMarker(position: NMGLatLng(lat: cluster.latitude, lng: cluster.longitude))
        if cluster.places.count == 1, let place = cluster.places.first {
            let isPicked = place.id == picked?.id
            marker.iconImage = isPicked ? PinoPin.marker(.picked) : PinoPin.guideDot(for: place)
            marker.anchor = isPicked ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 0.5, y: 0.5)
            PinoPin.caption(marker, name: place.label.caption, picked: isPicked, ambient: true)
            marker.zIndex = isPicked ? 30 : 3
            marker.touchHandler = { [weak self] _ in
                self?.onSelect(place)
                return true
            }
        } else {
            marker.iconImage = icon(count: cluster.places.count)
            marker.anchor = CGPoint(x: 0.5, y: 0.5)
            marker.zIndex = 4
            marker.captionText = String(format: tr("편의시설 %d곳"), cluster.places.count)
            marker.captionMinZoom = 17
            marker.touchHandler = { [weak self, weak mapView] _ in
                guard let mapView else { return true }
                self?.open(cluster, on: mapView)
                return true
            }
        }
        marker.mapView = mapView
        return marker
    }

    private func open(_ cluster: PoiClusters.Cluster, on mapView: NMFMapView) {
        if cluster.opensList(zoom: mapView.zoomLevel, maximumZoom: mapView.maxZoomLevel) {
            present(cluster, on: mapView)
        } else {
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: cluster.latitude, lng: cluster.longitude),
                zoomTo: min(mapView.zoomLevel + 2, mapView.maxZoomLevel)
            )
            update.animation = .easeIn
            mapView.moveCamera(update)
        }
    }

    private func present(_ cluster: PoiClusters.Cluster, on mapView: NMFMapView) {
        guard listController == nil, var presenter = mapView.window?.rootViewController else { return }
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        guard !presenter.isBeingDismissed else { return }
        let view = PoiClusterList(places: cluster.places, onPick: { [weak self] place in
            guard let self else { return }
            listController?.dismiss(animated: true) { [weak self] in self?.onSelect(place) }
        }, onClose: { [weak self] in self?.listController?.dismiss(animated: true) })
        let controller = UIHostingController(rootView: view.environment(\.locale, AppLanguage.shared.locale))
        controller.modalPresentationStyle = .pageSheet
        controller.sheetPresentationController?.detents = [.medium(), .large()]
        controller.sheetPresentationController?.prefersGrabberVisible = true
        listController = controller
        presenter.present(controller, animated: true)
    }

    private func icon(count: Int) -> NMFOverlayImage {
        if let found = icons[count] {
            return found
        }
        let size = CGSize(width: 42, height: 42)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            context.cgContext.setShadow(offset: .zero, blur: 2, color: UIColor.black.withAlphaComponent(0.25).cgColor)
            let circle = UIBezierPath(ovalIn: CGRect(x: 3, y: 3, width: 36, height: 36))
            UIColor.systemBlue.setFill()
            circle.fill()
            UIColor.white.setStroke()
            circle.lineWidth = 2
            circle.stroke()
            let text = String(count) as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 15), .foregroundColor: UIColor.white,
            ]
            let measured = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (size.width - measured.width) / 2, y: (size.height - measured.height) / 2),
                      withAttributes: attributes)
        }
        let overlay = NMFOverlayImage(image: image)
        icons[count] = overlay
        return overlay
    }
}

private struct PoiClusterList: View {
    let places: [RouteGuide.Place]
    let onPick: (RouteGuide.Place) -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(places) { place in
                        Button { onPick(place) } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: place.poiSymbol).foregroundStyle(RoutePoiTone.of(place.poiGroup))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(place.label.title).font(.headline).foregroundStyle(.primary)
                                    if let reading = place.label.reading {
                                        Text(reading).font(.subheadline)
                                    }
                                    if let category = place.label.category {
                                        Text(category).font(.caption)
                                    }
                                    if let address = place.label.address {
                                        Text(address).font(.caption)
                                    }
                                }
                                .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                        .accessibilityIdentifier("poi-cluster-place-\(place.id)")
                    }
                } header: {
                    Text(String(format: tr("현재 지도에서 받은 결과 중 %d곳"), places.count))
                } footer: {
                    Text("지역 전체 개수가 아니에요. 장소를 골라 정보를 확인하세요.")
                }
            }
            .navigationTitle(Text("주변 편의시설"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("닫기", action: onClose)
                }
            }
        }
    }
}
