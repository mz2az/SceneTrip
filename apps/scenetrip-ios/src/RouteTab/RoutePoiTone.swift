import SwiftUI

/// 편의시설 갈래의 **색**.
///
/// ## 지어낸 목록은 걷어냈다 (2026-08-27)
///
/// 여기 반경 POI 여섯 곳(성수동 왕갈비·뚝섬역…)이 박혀 있었다. 위치와 무관하게
/// 늘 같은 값이라 광화문에서 길찾기를 눌러도 「성수동 왕갈비 240 m」가 떴다.
///
/// **방향이 바뀌어서 지운 것이지, 진짜 값으로 갈아 끼운 것이 아니다.** 편의시설은
/// 이제 지도에 늘 뿌리지 않고 **챗봇에게 물었을 때만** 그 답으로 뜬다
/// (`RouteGuide`). 50만 건 POI 는 가이드가 쓰는 자료다.
///
/// 색은 남는다 — 갈래마다 무슨 색인가는 지어낸 값이 아니고, 온보딩 화면도 이것을
/// 쓴다. 지도 점과 목록 점이 **같은 색이어야** 눈으로 이어진다.
enum RoutePoiTone {
    /// 편의시설 갈래의 색. 지도 점과 목록 점이 **같은 색이어야** 눈으로 이어진다.
    static func of(_ group: RoutePoiGroup) -> Color {
        switch group {
        case .food: Color(red: 0.94, green: 0.58, blue: 0.17)
        case .sight: Color(red: 0.18, green: 0.80, blue: 0.44)
        case .stay: Color(PinImage.deep)
        case .transit: Color.accentColor
        }
    }
}
