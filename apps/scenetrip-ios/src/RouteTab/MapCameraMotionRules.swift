import Foundation

// 지도를 고른 곳·코스 장소로 옮기는 움직임 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §10-3).
// 가까우면 부드럽게, 아주 멀면 한 번에 — 2026-11-10 사용자 지적: 「너무 정신없이 휙휙 간다」.

/// 카메라를 옮기는 방법.
enum MapCameraMotion: Equatable {
    /// `duration` 초 동안 감속 곡선(`easeOut`)으로.
    case smooth(duration: Double)
    /// 움직임 없이 한 번에.
    case jump
}

enum MapCameraMotionRules {
    /// 부드럽게 옮길 최대 거리 — 지금 화면 너비의 이 배수까지.
    static let smoothWithinScreens: Double = 3
    /// 부드럽게 옮기는 시간(초).
    static let smoothDuration: Double = 0.6

    /// 지금 화면 가운데에서 가려는 곳까지 `distanceMeters`, 지금 화면 너비가 `visibleWidthMeters` 일 때의 움직임.
    static func motion(distanceMeters: Double, visibleWidthMeters: Double) -> MapCameraMotion {
        distanceMeters <= visibleWidthMeters * smoothWithinScreens ? .smooth(duration: smoothDuration) : .jump
    }
}
