import CoreGraphics

/// 떠 있는 알약 탭바의 치수와 규칙 (MZ2AZ-547 T1–T3, 웹 `features/proto/ProtoTabBar.tsx`).
///
/// 탭 셋이 **같은 폭**이고 고른 탭 뒤에 보라 알약이 미끄러진다. 탭 내용은 알약 뒤까지 내려가 비치고,
/// 목록은 바닥을 `clearance` 만큼(+기기 안전영역) 비운다.
enum FloatingTabBarRules {
    /// 화면 좌우 여백.
    static let sideMargin: CGFloat = 16
    /// 기기 안전영역 위로 띄우는 높이.
    static let bottomGap: CGFloat = 8
    /// 알약 높이.
    static let height: CGFloat = 58
    /// 알약 안쪽 여백 — 고른 탭 알약이 이만큼 안에 들어앉는다.
    static let innerPadding: CGFloat = 5
    /// 목록이 바닥에서 비우는 높이(기기 안전영역 위). 웹 `--proto-tab-inset` 의 74.
    static let clearance: CGFloat = 74
    /// 고른 탭 알약이 미끄러지는 시간(초).
    static let slideDuration: Double = 0.38

    /// 고른 탭 알약의 폭 — 알약 안쪽(좌우 여백을 뺀) 폭을 탭 수로 나눈다.
    static func selectionWidth(barWidth: CGFloat, tabCount: Int) -> CGFloat {
        guard tabCount > 0 else { return 0 }
        return max(0, barWidth - innerPadding * 2) / CGFloat(tabCount)
    }

    /// 고른 탭 알약의 왼쪽 끝 x(알약의 왼쪽 끝 기준). 0번 탭은 안쪽 여백에서 시작한다.
    static func selectionOffset(index: Int, barWidth: CGFloat, tabCount: Int) -> CGFloat {
        innerPadding + CGFloat(index) * selectionWidth(barWidth: barWidth, tabCount: tabCount)
    }
}
