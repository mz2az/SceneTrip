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
/// | 번호(고른 곳은 크게)·발바닥 | 코스의 정지점 | 성지 카드 |
/// | 번호 없는 보라 핀(미리보기) | 담을까 보는 곳(장바구니) | 성지 카드 |
/// | 갈래 아이콘 | 챗봇이 찾아 준 곳 — 「AI 장소」 | 편의시설 카드 |
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
                // 고른 곳과 **지금 안내 중인 목적지**는 같은 번호 핀을 크게(2026-11-10 사용자 결정 — 해태·파란 고리 뺌).
                marker.iconImage = RouteNumberPin.image(index + 1, large: true)
                marker.anchor = RouteNumberPin.anchor
                // 다른 핀에 가리지 않게 위로 올린다.
                marker.zIndex = 10
            } else {
                // 보라 단색 동그라미 + 흰 테두리 + 꼬리, 흰 번호(MZ2AZ-547 E11).
                marker.iconImage = RouteNumberPin.image(index + 1)
                marker.anchor = RouteNumberPin.anchor
            }
            marker.captionText = PinCaption.text(stop.label.title)
            marker.captionRequestedWidth = PinCaption.width
            marker.captionMinZoom = 12
            // 번호 핀을 누르면 성지 카드(장면 설명·여기로 길찾기)가 뜬다.
            marker.touchHandler = { [weak self] _ in
                self?.onTapStop(stop)
                return true
            }
            marker.mapView = mapView
            return marker
        }

        // 담을까 보는 곳(장바구니 창에서 체크한 것) — 번호 없는 보라 핀(2026-11-10 사용자 결정, 해태 뺌).
        // 코스 핀보다 위에 올려 가리지 않게 한다.
        markers += previews.map { place in
            let marker = NMFMarker(
                position: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            marker.iconImage = RouteNumberPin.plain()
            marker.anchor = RouteNumberPin.anchor
            marker.captionText = PinCaption.text(place.name)
            marker.captionRequestedWidth = PinCaption.width
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

        // 가이드가 찾아 준 곳 — 기본 지도와 같은 갈래 아이콘, 고른 하나만 크게(2026-11-10 사용자 결정, 해태 뺌).
        // 일반 가게와는 「AI 장소」 칩을 켜고 꺼서 가른다.
        markers += guidePlaces.map { place in
            let marker = NMFMarker(
                position: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            let isPicked = place.id == pickedGuide?.id
            marker.iconImage = PinoPin.guideDot(for: place, picked: isPicked)
            marker.anchor = CGPoint(x: 0.5, y: 0.5) // 동그라미 가운데가 그 자리다
            PinoPin.caption(marker, name: place.label.caption, picked: isPicked, ambient: false)
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
