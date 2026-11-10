import Foundation
import SceneApiClient

/// 도시 대표 사진 한 장 — 앱에 싣는 고정 8장 (MZ2AZ-547 H2, 웹 `proto/protoCovers.ts`, 사용자 선택 2026-11-09).
///
/// `asset` 은 `Images.xcassets` 의 이름이다. `placeId` 는 DEV·PRD 같은 촬영지 번호.
struct CityCover: Equatable {
    /// 도시 열쇠 — `GET /places/cities` 의 `key`(한국어 이름).
    let key: String
    let cityKo: String
    let cityEn: String
    let placeId: Int64
    let placeKo: String
    let placeEn: String
    let workKo: String
    let workEn: String
    let asset: String
}

enum CityCovers {
    /// 사진 머리에 도는 순서 — 서울 · 부산 · 경주 · 제주 · 인천 · 수원 · 포항 · 대구.
    static let all: [CityCover] = [
        CityCover(
            key: "서울", cityKo: "서울", cityEn: "Seoul", placeId: 186,
            placeKo: "뚝섬한강공원", placeEn: "Ttukseom Hangang Park",
            workKo: "마이데몬", workEn: "My Demon", asset: "city-cover-seoul"
        ),
        CityCover(
            key: "부산", cityKo: "부산", cityEn: "Busan", placeId: 403,
            placeKo: "감천문화마을", placeEn: "Gamcheon Culture Village",
            workKo: "유미의 세포들", workEn: "Yumi's Cells", asset: "city-cover-busan"
        ),
        CityCover(
            key: "경주", cityKo: "경주", cityEn: "Gyeongju", placeId: 390,
            placeKo: "경주 오릉", placeEn: "Gyeongju Oreung Royal Tombs",
            workKo: "21세기 대군부인", workEn: "Perfect Crown", asset: "city-cover-gyeongju"
        ),
        CityCover(
            key: "제주", cityKo: "제주", cityEn: "Jeju", placeId: 295,
            placeKo: "군산오름", placeEn: "Gunsan Oreum",
            workKo: "폭싹 속았수다", workEn: "When Life Gives You Tangerines", asset: "city-cover-jeju"
        ),
        CityCover(
            key: "인천", cityKo: "인천", cityEn: "Incheon", placeId: 199,
            placeKo: "월미도", placeEn: "Wolmido",
            workKo: "브로커", workEn: "Broker", asset: "city-cover-incheon"
        ),
        CityCover(
            key: "수원", cityKo: "수원", cityEn: "Suwon", placeId: 410,
            placeKo: "화성행궁", placeEn: "Hwaseong Haenggung Palace",
            workKo: "왕의 남자", workEn: "King and the Clown", asset: "city-cover-suwon"
        ),
        CityCover(
            key: "포항", cityKo: "포항", cityEn: "Pohang", placeId: 195,
            placeKo: "호미곶해맞이광장", placeEn: "Homigot Sunrise Plaza",
            workKo: "스프링 피버", workEn: "Spring Fever", asset: "city-cover-pohang"
        ),
        CityCover(
            key: "대구", cityKo: "대구", cityEn: "Daegu", placeId: 140,
            placeKo: "서문시장", placeEn: "Seomun Market",
            workKo: "김비서가 왜 그럴까", workEn: "What's Wrong with Secretary Kim", asset: "city-cover-daegu"
        ),
    ]

    /// 고른 대표 사진이 있는 도시면 그 사진.
    static func fixed(_ key: String) -> CityCover? {
        all.first { $0.key == key }
    }
}

/// 홈 맨 위 「어디로 여행 가세요?」 사진 카드의 규칙 (MZ2AZ-547 H1–H3).
enum HomeCityRules {
    /// 카드에 늘어놓는 도시 칩의 최대 수.
    static let chipLimit = 8

    /// 칩으로 올릴 도시 — **서버 순서 그대로**(서울·부산·경주·제주·인천 → 수), 앞에서 `chipLimit` 개.
    /// 촬영지 0곳인 도시는 뺀다.
    static func chips(_ cities: [PlaceCity]) -> [PlaceCity] {
        Array(cities.filter { $0.placeCount > 0 }.prefix(chipLimit))
    }

    /// 도시 사진이 어디서 오는가.
    enum Photo: Equatable {
        /// 앱에 실린 고정 사진(`Images.xcassets` 이름).
        case asset(String)
        /// 그 도시 인기 1위 촬영지의 사진 주소.
        case remote(String)
    }

    /// 도시의 사진 — 고정 사진이 먼저, 없으면 인기 1위 촬영지 사진(절대 http(s) 주소만), 그것도 없으면 nil.
    static func photo(cityKey: String, topPlaceImageUrl: String?) -> Photo? {
        if let cover = CityCovers.fixed(cityKey) {
            return .asset(cover.asset)
        }
        guard let raw = topPlaceImageUrl, let url = URL(string: raw), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host?.isEmpty == false
        else { return nil }
        return .remote(raw)
    }

    /// 사진 위 작은 글 — 「도시 · 촬영지」. 앱 언어가 영어면 영어 이름.
    static func caption(_ cover: CityCover, lang: Lang) -> String {
        lang == .ko ? "\(cover.cityKo) · \(cover.placeKo)" : "\(cover.cityEn) · \(cover.placeEn)"
    }
}

/// 사진 머리 슬라이드의 규칙 (MZ2AZ-547 H2) — 4초마다 겹쳐 바뀌고, 움직임 줄이기면 첫 장에 멈춘다.
enum CitySlideRules {
    /// 다음 장으로 넘어가는 간격(초).
    static let interval: Double = 4
    /// 겹쳐 바뀌는 시간(초).
    static let crossfade: Double = 1.2
    /// 현재 장이 천천히 커지는 시간(초)과 배율.
    static let zoomDuration: Double = 5
    static let zoomScale: Double = 1.06

    /// 다음 장의 번호 — 끝에서 처음으로 돈다. 장이 없으면 0.
    static func next(after index: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return (index + 1) % count
    }

    /// 저절로 넘기는가 — 두 장 이상이고 움직임 줄이기가 꺼져 있을 때만.
    static func advances(count: Int, reduceMotion: Bool) -> Bool {
        count >= 2 && !reduceMotion
    }

    /// 이 장의 그림을 그려 두는가 — 지금 장과 앞·뒤 장만(나머지는 받지 않는다).
    static func isMounted(_ index: Int, current: Int, count: Int) -> Bool {
        guard count > 0, (0 ..< count).contains(index) else { return false }
        let now = ((current % count) + count) % count
        return index == now || index == (now + 1) % count || index == (now - 1 + count) % count
    }
}
