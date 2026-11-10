import CoreGraphics
import Foundation

// 지도 검색 결과 서랍 끌기 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §3).
// 서랍 높이는 지도 높이의 42%(`MapSearchResults.shortShare`) 또는 78%(`tallShare`) 둘 중 하나에 붙는다.
// 세로 거리·속도는 SwiftUI `DragGesture` 처럼 **아래가 +** 다.

enum MapSearchDrawerRules {
    /// 이만큼(pt/초) 빠르게 튕기면 손 뗀 자리와 상관없이 튕긴 방향의 높이로 붙는다.
    static let flickSpeed: CGFloat = 600
    /// 붙는 움직임의 길이(초).
    static let snapDuration: Double = 0.28
    /// 42% 아래·78% 위로 넘친 거리 가운데 서랍이 따라가는 몫.
    static let rubberBand: CGFloat = 0.3

    /// 끄는 동안의 서랍 높이(pt). 손가락을 그대로 따라가되(위로 끌면 높아진다), 42% 아래·78% 위로는 조금만 늘어난다.
    /// - Parameters:
    ///   - startShare: 끌기 시작할 때 붙어 있던 비율(0.42 또는 0.78).
    ///   - translation: 손가락이 움직인 세로 거리(아래가 +).
    ///   - mapHeight: 지도 칸의 높이(pt).
    static func dragHeight(startShare: Double, translation: CGFloat, mapHeight: CGFloat) -> CGFloat {
        let low = mapHeight * MapSearchResults.shortShare
        let high = mapHeight * MapSearchResults.tallShare
        let raw = mapHeight * startShare - translation
        if raw > high {
            return high + (raw - high) * rubberBand
        }
        if raw < low {
            return low - (low - raw) * rubberBand
        }
        return raw
    }

    /// 손을 뗐다 — 붙을 비율. 빠르게 튕겼으면(`|velocity| >= flickSpeed`) 그 방향(위 → 78%, 아래 → 42%),
    /// 아니면 지금 높이에서 가까운 쪽.
    /// - Parameters:
    ///   - height: 손 뗀 순간의 서랍 높이(pt, `dragHeight` 의 값).
    ///   - velocity: 손 뗀 순간의 세로 속도(pt/초, 아래가 +).
    ///   - mapHeight: 지도 칸의 높이(pt).
    static func settledShare(height: CGFloat, velocity: CGFloat, mapHeight: CGFloat) -> Double {
        if abs(velocity) >= flickSpeed {
            return velocity < 0 ? MapSearchResults.tallShare : MapSearchResults.shortShare
        }
        let middle = mapHeight * (MapSearchResults.shortShare + MapSearchResults.tallShare) / 2
        return height < middle ? MapSearchResults.shortShare : MapSearchResults.tallShare
    }

    /// 손잡이를 눌렀다 — 42% ↔ 78%.
    static func tappedShare(_ share: Double) -> Double {
        share >= MapSearchResults.tallShare ? MapSearchResults.shortShare : MapSearchResults.tallShare
    }
}
