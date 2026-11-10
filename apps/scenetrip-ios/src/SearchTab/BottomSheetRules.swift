import CoreGraphics

/// `BottomSheet` 의 단 높이 계산 — 떠 있는 탭바 뒤까지 내려가는 서랍을 위해 떼어 냈다 (MZ2AZ-547 T3).
enum BottomSheetRules {
    /// 최대 단의 높이 — 위 검색바(`topInset`) 바로 아래까지. 화면이 작아도 80 은 남긴다.
    static func maxHeight(total: CGFloat, topInset: CGFloat) -> CGFloat {
        max(80, total - topInset)
    }

    /// 한 단의 높이(pt).
    ///
    /// - `bottomClearance`: 화면 바닥에서 떠 있는 탭바가 차지하는 높이(알약 + 기기 안전영역). 서랍은 그 뒤까지 내려가지만
    ///   접힘·중간 단은 **그 위에 보이는 몫**이 예전(탭바가 화면을 나눠 갖던 때)과 같은 비율이 되게 잰다 —
    ///   `clearance + (total − clearance) × fraction`. 0 이면 예전 계산(`total × fraction`) 그대로다.
    /// - 최대 단은 `maxHeight` 그대로다.
    static func height(
        fraction: CGFloat, isExpanded: Bool, total: CGFloat, topInset: CGFloat, bottomClearance: CGFloat
    ) -> CGFloat {
        if isExpanded {
            return maxHeight(total: total, topInset: topInset)
        }
        return bottomClearance + max(0, total - bottomClearance) * fraction
    }
}
