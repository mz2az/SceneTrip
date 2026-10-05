import NMapsMap
import SceneApiClient
import SwiftUI

/// 지도에 **핀을 그리는 일**만. `RouteMapView.Coordinator` 의 확장이다.
///
/// 본체(`RouteMapView.swift`)에서 떼어 냈다 — 타입 본문 길이(swiftlint 350줄)를 넘겼다.
/// 나누는 선은 「무엇을 그리는가」다: 카메라·손짓·상태는 본체에, **마커 만들기**는 여기에.
///
/// 핀은 넷이다.
///
/// | 핀 | 무엇 | 누르면 |
/// | --- | --- | --- |
/// | 번호·해태·발바닥 | 코스의 정지점 | 성지 카드 |
/// | 빨간 해태(미리보기) | 담을까 보는 곳(검색·장바구니) | 성지 카드 |
/// | **작은 해태** | 챗봇이 찾아 준 곳 — 「AI 장소」 | 편의시설 카드 |
/// | 파문 | 내 자리 | — |
extension RouteMapView.Coordinator {
    func drawPins(
        _ stops: [RouteStop],
        focused: RouteStop?,
        previews: [PlaceSummary],
        guidePlaces: [RouteGuide.Place],
        pickedGuide: RouteGuide.Place?,
        navTarget: RouteStop? = nil,
        on mapView: NMFMapView
    ) {
        markers.forEach { $0.mapView = nil }
        markers = stops.enumerated().map { index, stop in
            let marker = NMFMarker(
                position: NMGLatLng(lat: stop.place.latitude, lng: stop.place.longitude)
            )
            if stop.visited {
                // **다녀온 곳은 번호 핀이 발바닥으로 바뀐다**(2026-09-03 사용자 요청 —
                // 「도착한 표시로 그 핀이 발바닥으로」). 자리 위에 얹는다.
                marker.iconImage = PinoPin.pawPin()
                marker.anchor = CGPoint(x: 0.5, y: 0.5)
                marker.zIndex = 8
            } else if stop.id == focused?.id || stop.id == navTarget?.id {
                // 고른 곳과 **지금 안내 중인 목적지**는 해태다.
                marker.iconImage = PinoPin.marker(.normal)
                // 다른 핀에 가리지 않게 위로 올린다.
                marker.zIndex = 10
            } else {
                marker.iconImage = PinImage.numbered(index + 1)
            }
            marker.captionText = stop.place.name
            marker.captionMinZoom = 12
            // 번호 핀을 누르면 성지 카드(장면 설명·여기로 길찾기)가 뜬다.
            marker.touchHandler = { [weak self] _ in
                self?.onTapStop(stop)
                return true
            }
            marker.mapView = mapView
            return marker
        }

        // 담을까 보는 곳(검색·장바구니에서 체크한 것) — 빨간 고양이.
        // 코스 핀보다 위에 올려 가리지 않게 한다.
        markers += previews.map { place in
            let marker = NMFMarker(
                position: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            marker.iconImage = PinoPin.marker(.picked)
            marker.captionText = place.name
            marker.captionMinZoom = 10
            marker.zIndex = 20
            // 눌러야 한다. 챗봇이 찍어 준 곳도 이 핀으로 그려지므로, 손잡이가 없으면
            // 「주변 음식점」을 받아도 카드를 볼 길이 없다(2026-09-16).
            marker.touchHandler = { [weak self] _ in
                self?.onTapPreview(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }

        // 가이드가 찾아 준 곳 — **작은 해태, 고른 하나만 빨간 해태**(2026-09-17 사용자 결정).
        // 갈래 점으로 그렸을 때는 주변 편의시설 점과 안 갈려 「AI 가 골라 준 곳」이 어느 것인지
        // 알 수 없었다. 주변 점(갈래 칩)과 AI 장소(해태 칩)는 이제 다른 모양·다른 칩이다.
        markers += guidePlaces.map { place in
            let marker = NMFMarker(
                position: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            let isPicked = place.id == pickedGuide?.id
            marker.iconImage = PinoPin.marker(isPicked ? .picked : .ai)
            marker.anchor = CGPoint(x: 0.5, y: 1) // 핀 끝이 그 자리다
            PinoPin.caption(marker, name: place.name, picked: isPicked, ambient: false)
            marker.zIndex = isPicked ? 30 : 15
            marker.touchHandler = { [weak self] _ in
                self?.onTapGuide(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }
}
