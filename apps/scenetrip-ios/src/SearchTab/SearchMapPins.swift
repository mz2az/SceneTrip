import CoreGraphics
import SceneApiClient

/// 검색 탭 지도에 **어느 장소를** 번호 핀으로 꽂는가 (MZ2AZ-547 G1, 웹 `useSearchTab.ts:100-108`).
///
/// 첫 화면(검색어·반경·고른 작품이 없는 상태)의 지도에는 **보관함에 담은 곳만** 꽂는다 — 번호는 보관함 목록 순서다.
/// 작품을 골랐으면 그 작품의 촬영지, 검색했으면 검색 결과(목록과 같은 배열)다.
enum SearchMapPins {
    /// 보관함 항목을 지도 핀으로. 좌표가 없는 곳과 목록에서 빠진 곳(`delisted`)은 뺀다. 순서는 보관함 그대로.
    static func saved(_ items: [CartItem]) -> [PlaceSummary] {
        items.compactMap { item in
            guard item.delisted != true, let latitude = item.latitude, let longitude = item.longitude else { return nil }
            return PlaceSummary(
                id: item.placeId, name: item.name, address: item.address,
                latitude: latitude, longitude: longitude, imageUrl: item.imageUrl
            )
        }
    }

    /// 지도에 꽂을 장소들 — 고른 작품 > 첫 화면(보관함) > 검색 결과.
    static func places(
        contentSelected: Bool,
        contentPlaces: [PlaceSummary],
        initial: Bool,
        saved: [PlaceSummary],
        visible: [PlaceSummary]
    ) -> [PlaceSummary] {
        if contentSelected {
            return contentPlaces
        }
        return initial ? saved : visible
    }

    /// 핀에 번호를 찍을지. 첫 화면은 보관함 순서 번호라 어느 탭이든 찍는다. 그 밖에는 목록 행 번호와 짝이 있을 때만 —
    /// 첫 화면이 아닌 한 늘 짝이 있다.
    static func numbered(initial _: Bool, tab _: SearchTabView.Tab, contentSelected _: Bool) -> Bool {
        // 예전에는 첫 화면 작품 탭(인기 핀, 짝지을 목록 없음)만 번호를 껐다. 첫 화면 핀이 보관함 순서가 되면서
        // 번호를 끌 자리가 없다 — 인자는 규칙이 다시 갈릴 때를 위해 남긴다(웹 `numbered` 와 같은 모양).
        true
    }
}

/// 코스 번호 핀의 모양 (MZ2AZ-547 E11·G1, 웹 `protoPoi.tsx` 의 `PROTO_NUMBERED`·`protoNumberedPinSvg`).
///
/// 보라 단색 동그라미(흰 테두리 2)에 흰 굵은 번호, 아래 짧은 꼬리. 크기 30×38, 꼬리 끝 (15, 37).
/// 고른 핀은 **한 번만** 1.2배 — 웹이 예전 핀의 1.3배와 겹쳐 1.6배가 되던 것을 고쳤다(2026-11-10).
enum CoursePinShape {
    static let width: CGFloat = 30
    static let height: CGFloat = 38
    /// 꼬리 끝 — 지도 좌표가 닿는 점.
    static let tip = CGPoint(x: 15, y: 37)
    static let selectedScale: CGFloat = 1.2

    /// 그린 그림의 크기. 고른 핀은 통째로 `selectedScale` 배.
    static func size(selected: Bool) -> CGSize {
        let scale = selected ? selectedScale : 1
        return CGSize(width: width * scale, height: height * scale)
    }

    /// 마커의 기준점(0…1) — 꼬리 끝이 좌표에 닿게. 크기를 키워도 비율이라 같다.
    static var anchor: CGPoint {
        CGPoint(x: tip.x / width, y: tip.y / height)
    }

    /// 번호 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9.
    static func fontSize(_ number: Int) -> CGFloat {
        number < 10 ? 13 : number < 100 ? 11.5 : 9
    }
}
