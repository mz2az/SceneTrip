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
    ///
    /// 배정(2026-08-27 확정): 음식점·카페=빨강, 숙소=초록, 교통=노랑, 명소=파랑.
    /// 보라는 안 쓴다 — 코스 번호 핀과 AI 말풍선이 이미 보라다.
    static func of(_ group: RoutePoiGroup) -> Color {
        switch group {
        case .food: Color(red: 0.89, green: 0.16, blue: 0.20)
        case .stay: Color(red: 0.13, green: 0.66, blue: 0.37)
        case .transit: Color(red: 0.93, green: 0.73, blue: 0.05)
        case .sight: Color.accentColor
        }
    }
}

extension RoutePoiGroup {
    /// 프로토타입 서버(와 DB `poi.group`)가 쓰는 한국어 갈래 이름.
    var serverName: String {
        switch self {
        case .food: "음식"
        case .stay: "숙박"
        case .sight: "명소"
        case .transit: "교통"
        }
    }
}

extension RouteGuide.Place {
    /// 이 장소의 큰 갈래. 서버가 준 값을 먼저 믿고, 없으면(옛 서버) 업종
    /// 문자열에서 짐작한다 — 못 짐작하면 음식으로 둔다(50만 건의 대다수다).
    var poiGroup: RoutePoiGroup {
        switch group {
        case "음식": return .food
        case "숙박": return .stay
        case "명소": return .sight
        case "교통": return .transit
        default: break
        }
        let kind = category ?? ""
        if ["호텔", "모텔", "펜션", "게스트", "리조트", "숙박", "여관", "콘도"]
            .contains(where: kind.contains)
        {
            return .stay
        }
        if ["역", "공항", "터미널", "정류", "철도", "렌터카", "주차"]
            .contains(where: kind.contains)
        {
            return .transit
        }
        if ["관광", "명소", "박물관", "미술관", "궁", "공원", "문화", "사찰", "유적", "전시", "기념"]
            .contains(where: kind.contains)
        {
            return .sight
        }
        return .food
    }
}
