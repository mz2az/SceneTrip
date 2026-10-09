import Foundation

/// 촬영지 유형(`PlaceSummary.type`)을 **화면에 뭐라고 적고 어느 칩에 묶는가** (MZ2AZ-372).
///
/// 계약은 이 값을 「수집된 장소 유형을 가공 없이」 내려준다. 2026-10-08 에 올린 촬영지 표부터 그 값이
/// 한국어 라벨(`카페`)이 아니라 **코드**(`cafe`·`store_daily`·`public_office`)다 — 데이터 파이프라인이
/// 네이버 분류를 코드로 접는다(`SceneTrip_flow/config/type_codes.json`). 앱은 그 값을 그대로 화면에 찍고
/// 한국어 라벨로만 칩을 묶고 있어서, 코드가 화면에 나오고 칩은 전부 0건이 됐다.
///
/// 그래서 **코드 → 표시말(한국어·영어) → 칩 묶음** 을 이 한곳에 둔다. 규칙은 셋이다.
///
/// 1. 코드와 옛 한국어 라벨을 **둘 다** 받는다 — DB 에 둘이 섞여 있다(코드 450 · 한국어 15 · 없음 21).
/// 2. 표에 없는 값과 빈 값은 **적지 않는다**(`label` 이 nil). 「보증보험」「방면정보」 처럼 수집 원본의 분류가
///    그대로 남은 행이 있는데, 그것을 날것으로 보이느니 비워 둔다. 칩에서는 「기타」 로 묶는다.
/// 3. 한국어 라벨로 들어온 옛 값도 코드로 접은 뒤 그 코드의 표시말로 적는다 — 「병원」 이 영어 화면에서
///    Hospital 이 된다.
///
/// **서버가 표시말을 내려주면 걷어낸다**(편의시설의 `categoryLabel` 처럼). 그때까지 이 표가 iOS·Android 에
/// 한 벌씩 있다 — 계획 `docs/project/plans/catalog-scale.md` §6.
enum PlaceType {
    /// 분류 칩의 묶음. `rawValue` 가 한국어 원문이자 번역 표의 열쇠다.
    enum Group: String, CaseIterable {
        case food = "음식점·카페"
        case sight = "명소·자연"
        case street = "거리·다리"
        case facility = "건물·시설"
        /// 표에 없는 유형과 유형이 없는 곳.
        case other = "기타"
    }

    struct Entry: Equatable {
        let code: String
        let korean: String
        let english: String
        let group: Group
    }

    /// 코드 한 줄에 하나. 코드는 파이프라인의 표(`type_codes.json`)에 나오는 서른여덟 가지 전부다.
    static let entries: [Entry] = [
        Entry(code: "restaurant", korean: "음식점", english: "Restaurant", group: .food),
        Entry(code: "cafe", korean: "카페", english: "Café", group: .food),
        Entry(code: "bar", korean: "바", english: "Bar", group: .food),
        Entry(code: "market", korean: "시장", english: "Market", group: .food),
        Entry(code: "store_daily", korean: "편의점·마트", english: "Store", group: .food),

        Entry(code: "landmark", korean: "명소", english: "Landmark", group: .sight),
        Entry(code: "nature", korean: "자연", english: "Nature", group: .sight),
        Entry(code: "park", korean: "공원", english: "Park", group: .sight),
        Entry(code: "beach", korean: "해변", english: "Beach", group: .sight),
        Entry(code: "port", korean: "항구", english: "Port", group: .sight),
        Entry(code: "viewpoint", korean: "전망대", english: "Viewpoint", group: .sight),
        Entry(code: "temple", korean: "사찰", english: "Temple", group: .sight),
        Entry(code: "church", korean: "성당", english: "Church", group: .sight),
        Entry(code: "palace", korean: "고궁", english: "Palace", group: .sight),
        Entry(code: "hanok", korean: "한옥", english: "Hanok", group: .sight),
        Entry(code: "village", korean: "마을", english: "Village", group: .sight),
        Entry(code: "theme_park", korean: "테마파크", english: "Theme park", group: .sight),
        Entry(code: "experience", korean: "체험시설", english: "Activity", group: .sight),
        Entry(code: "camping", korean: "캠핑장", english: "Campsite", group: .sight),

        Entry(code: "street", korean: "거리", english: "Street", group: .street),
        Entry(code: "bridge", korean: "다리", english: "Bridge", group: .street),
        Entry(code: "station", korean: "역·교통", english: "Station", group: .street),
        Entry(code: "airport", korean: "공항", english: "Airport", group: .street),

        Entry(code: "building", korean: "건물", english: "Building", group: .facility),
        Entry(code: "hotel", korean: "호텔", english: "Hotel", group: .facility),
        Entry(code: "hospital", korean: "병원", english: "Hospital", group: .facility),
        Entry(code: "school", korean: "학교", english: "School", group: .facility),
        Entry(code: "museum", korean: "박물관·미술관", english: "Museum", group: .facility),
        Entry(code: "bookstore", korean: "서점", english: "Bookstore", group: .facility),
        Entry(code: "shop", korean: "상점", english: "Shop", group: .facility),
        Entry(code: "mall", korean: "쇼핑몰", english: "Shopping mall", group: .facility),
        Entry(code: "stadium", korean: "경기장", english: "Stadium", group: .facility),
        Entry(code: "wedding_hall", korean: "예식장", english: "Wedding hall", group: .facility),
        Entry(code: "funeral_hall", korean: "장례식장", english: "Funeral hall", group: .facility),
        Entry(code: "set", korean: "세트장", english: "Film set", group: .facility),
        Entry(code: "public_office", korean: "관공서", english: "Public office", group: .facility),
        Entry(code: "theater", korean: "극장·공연장", english: "Theater", group: .facility),
        Entry(code: "library", korean: "도서관", english: "Library", group: .facility),
    ]

    /// 옛 한국어 라벨 → 코드. 파이프라인의 표를 그대로 옮긴 것이다 — 앱이 처음 들고 있던 서른일곱 가지와,
    /// 수집 원본(네이버 분류)에서 온 것들. 같은 장소가 다시 수집되면 이 표대로 코드가 붙는다.
    ///
    /// 원본 표의 `세계문화유산 → restaurant` 한 줄은 옮기지 않았다(잘못 적힌 것으로 보인다 — 계획 §5).
    static let legacy: [String: String] = [
        "음식점": "restaurant", "카페": "cafe", "바": "bar", "시장": "market",
        "편의점": "store_daily", "마트": "store_daily",
        "자연": "nature", "공원": "park", "해변": "beach", "항구": "port", "전망대": "viewpoint",
        "사찰": "temple", "성당": "church", "고궁": "palace", "한옥": "hanok", "한옥마을": "hanok",
        "마을": "village", "테마파크": "theme_park", "체험시설": "experience", "캠핑장": "camping",
        "명소": "landmark",
        "거리": "street", "다리": "bridge", "역/교통": "station", "공항": "airport",
        "건물": "building", "호텔": "hotel", "병원": "hospital", "학교": "school",
        "박물관/미술관": "museum", "서점": "bookstore", "상점": "shop",
        "백화점": "mall", "쇼핑몰": "mall", "경기장": "stadium", "스포츠시설": "stadium",
        "예식장": "wedding_hall", "장례식장": "funeral_hall", "세트장": "set", "관공서": "public_office",
        "극장": "theater", "공연장": "theater", "도서관": "library",
        // 수집 원본의 분류.
        "촬영장소": "landmark", "페리,해운": "port", "생선회": "restaurant", "막국수": "restaurant",
        "촬영지": "landmark", "호수,연못,저수지": "nature", "골목길": "street", "영상테마파크": "set",
        "문화센터": "building", "기업,빌딩": "building", "근린공원": "park", "복합문화공간": "building",
        "고등학교": "school", "지역": "street", "곰탕,설렁탕": "restaurant", "갤러리카페": "cafe",
        "관광농원,팜스테이": "nature", "한식": "restaurant", "중식": "restaurant", "일식": "restaurant",
        "양식": "restaurant", "아시아음식": "restaurant", "분식": "restaurant", "패스트푸드": "restaurant",
        "카페,디저트": "cafe", "술집": "bar", "여행,명소": "landmark", "숙박": "hotel",
        "교통시설": "station", "문화,예술": "museum", "쇼핑,유통": "shop", "생활,편의": "store_daily",
        "교육,학문": "school", "의료": "hospital", "공공,사회기관": "public_office",
        "스포츠,오락": "stadium", "자연명소": "nature", "지역명소": "landmark", "자연공원": "landmark",
        "시민공원": "landmark", "광장": "landmark", "궁궐": "landmark", "박물관": "landmark",
        "문화,유적": "landmark", "체험마을": "landmark", "강,하천": "landmark",
        "지하철,전철": "station", "교량명": "bridge",
    ]

    private static let byCode: [String: Entry] = Dictionary(
        uniqueKeysWithValues: entries.map { ($0.code, $0) }
    )

    /// 서버가 준 값(코드든 옛 라벨이든)을 표의 한 줄로. 표에 없으면 nil.
    static func entry(of raw: String?) -> Entry? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return nil
        }
        if let found = byCode[text.lowercased()] {
            return found
        }
        return legacy[text].flatMap { byCode[$0] }
    }

    /// 화면에 적을 말. **표에 없으면 nil — 적지 않는다.**
    static func label(_ raw: String?, korean: Bool) -> String? {
        entry(of: raw).map { korean ? $0.korean : $0.english }
    }

    /// 지금 앱 언어로.
    static func label(_ raw: String?) -> String? {
        label(raw, korean: AppLanguage.current == .ko)
    }

    /// 어느 칩에 묶이는가. 표에 없거나 비었으면 「기타」.
    static func group(of raw: String?) -> Group {
        entry(of: raw)?.group ?? .other
    }

    // MARK: 직접 찍은 핀

    /// 직접 찍은 핀의 분류 — 앱이 고르게 하는 다섯 갈래(한국어 원문).
    static let pinKinds: [String] = ["숙소"] + Group.allCases.filter { $0 != .other }.map(\.rawValue)

    /// 계약의 `PinCategory` 값 → 앱의 갈래 이름. 서버에서 다시 읽은 핀은 이 값으로 온다.
    private static let pinCodes: [String: String] = [
        "lodging": "숙소", "food": Group.food.rawValue, "attraction": Group.sight.rawValue,
        "street": Group.street.rawValue, "building": Group.facility.rawValue,
    ]

    /// 핀의 분류를 앱의 갈래 이름(한국어 원문)으로. 방금 찍은 핀은 이름 그대로, 서버에서 읽은 핀은 코드로 온다.
    static func pinKind(_ raw: String?) -> String? {
        guard let raw else { return nil }
        if pinKinds.contains(raw) {
            return raw
        }
        return pinCodes[raw]
    }

    /// 코스 줄에 적을 유형. 직접 찍은 핀이면 핀의 갈래를, 촬영지면 유형의 표시말을 — 어느 쪽이든 모르면 nil.
    static func stopLabel(_ raw: String?, pinned: Bool, korean: Bool, translate: (String) -> String) -> String? {
        pinned ? pinKind(raw).map(translate) : label(raw, korean: korean)
    }

    /// 갈래까지 아는 줄(MZ2AZ-380). **편의시설은 분류를 늘 적는다** — 촬영지 유형 표에 있으면 그 표시말
    /// (「카페」 → Café), 없으면 서버가 준 분류 그대로(「한식」). 편의시설 분류는 촬영지 유형과 값의 범위가
    /// 달라(계약 `CourseItem.category`) 표에 없는 것이 보통이고, 그렇다고 비우면 무슨 가게인지 알 수 없다.
    static func stopLabel(
        _ raw: String?, kind: RouteStop.Kind, korean: Bool, translate: (String) -> String
    ) -> String? {
        guard case .poi = kind else {
            return stopLabel(raw, pinned: kind == .pin, korean: korean, translate: translate)
        }
        let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return label(raw, korean: korean) ?? (text.isEmpty ? nil : text)
    }

    static func stopLabel(_ raw: String?, kind: RouteStop.Kind) -> String? {
        stopLabel(raw, kind: kind, korean: AppLanguage.current == .ko, translate: { tr($0) })
    }
}

// MARK: - 카테고리 칩

/// 검색 탭 장소 목록의 분류 칩. 묶는 규칙은 `PlaceType` 에 있고 여기는 칩 이름만 든다.
enum CategoryChip {
    static let all = "전체"

    /// 「전체」 + 네 묶음 + 「기타」.
    static var names: [String] {
        [all] + PlaceType.Group.allCases.map(\.rawValue)
    }

    static func of(_ placeType: String?) -> String {
        PlaceType.group(of: placeType).rawValue
    }
}
