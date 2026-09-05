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

/// 편의시설의 **아이콘**(SF Symbol 이름). 색(`RoutePoiTone`)과 짝이다.
///
/// 처음엔 갈래 넷을 색만 다른 발바닥 점으로 그렸다. 색 넷은 외우면 알지만
/// 「이게 카페인지 밥집인지, 지하철인지 공항인지」 는 색으로 못 가른다 — 그래서
/// 업종 이름으로 글리프를 고른다(2026-09-01 사용자 요청). 갈래 하나에 글리프가 여럿인
/// 이유가 그것이다: 음식 갈래 안에서 카페와 식당이, 교통 갈래 안에서 지하철·기차·버스·
/// 공항이 서로 달라야 쓸모가 있다.
///
/// 업종 이름은 프로토타입 서버의 `poi.kind`(TMAP 분류를 우리 열두 개 안팎으로 접은 것 —
/// `/api/poi-categories` 가 목록을 준다)다. 못 맞추면 갈래의 기본 글리프로 떨어진다.
enum RoutePoiGlyph {
    static func symbol(group: RoutePoiGroup, category: String?) -> String {
        let kind = category ?? ""
        return switch group {
        case .food: food(kind)
        case .stay: "bed.double.fill"
        case .transit: transit(kind)
        case .sight: sight(kind)
        }
    }

    /// 업종 이름(`kind`)에 **첫 번째로 걸리는 낱말**의 글리프. 표의 순서가 우선순위다.
    private static func pick(
        _ kind: String, _ table: [([String], String)], fallback: String
    ) -> String {
        table.first { words, _ in words.contains(where: kind.contains) }?.1 ?? fallback
    }

    private static func food(_ kind: String) -> String {
        pick(
            kind, [(["카페", "커피", "제과", "베이커리", "디저트"], "cup.and.saucer.fill")],
            fallback: "fork.knife"
        )
    }

    private static func transit(_ kind: String) -> String {
        pick(kind, [
            (["공항"], "airplane"),
            (["버스"], "bus.fill"),
            (["기차", "철도", "KTX"], "train.side.front.car"),
        ], fallback: "tram.fill") // 지하철역, 그 밖의 역
    }

    private static func sight(_ kind: String) -> String {
        pick(kind, [
            (["해수욕장", "해변"], "beach.umbrella.fill"),
            (["전망대"], "binoculars.fill"),
            (["테마파크", "놀이"], "ferriswheel"),
            (["농원", "공원", "수목원", "정원"], "leaf.fill"),
        ], fallback: "building.columns.fill") // 박물관/기념관 · 미술관 · 문화유적지 · 절 · 탑 — 랜드마크
    }
}

extension RouteGuide.Place {
    /// 이 장소의 아이콘. 지도 점과 목록 점이 **같은 글리프**라야 눈으로 이어진다.
    var poiSymbol: String {
        RoutePoiGlyph.symbol(group: poiGroup, category: category)
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
        case "음식", "food": return .food
        case "숙박", "stay": return .stay
        case "명소", "sight": return .sight
        case "교통", "transit": return .transit
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
