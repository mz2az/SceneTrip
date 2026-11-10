import SceneApiClient
@testable import SceneTrip
import XCTest

/// 홈 맨 위 사진 카드 — 고정 8장·도시 칩·사진·글·슬라이드 (MZ2AZ-547 H1–H3, 웹 `protoCovers.ts`·`protoParts.tsx`).
final class HomeCityRulesTests: XCTestCase {
    private func city(_ key: String, _ count: Int) -> PlaceCity {
        PlaceCity(key: key, name: key, placeCount: count, center: LatLng(latitude: 37, longitude: 127), bbox: [126, 36, 128, 38])
    }

    // MARK: 고정 8장

    func testEightCoversInWebOrder() {
        XCTAssertEqual(CityCovers.all.map(\.key), ["서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구"])
        XCTAssertEqual(CityCovers.all.map(\.placeId), [186, 403, 390, 295, 199, 410, 195, 140])
    }

    func testCoverNamesKoEn() {
        XCTAssertEqual(CityCovers.all.map(\.cityEn), ["Seoul", "Busan", "Gyeongju", "Jeju", "Incheon", "Suwon", "Pohang", "Daegu"])
        XCTAssertEqual(CityCovers.all.map(\.cityKo), ["서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구"])
        XCTAssertEqual(
            CityCovers.all.map(\.placeKo),
            ["뚝섬한강공원", "감천문화마을", "경주 오릉", "군산오름", "월미도", "화성행궁", "호미곶해맞이광장", "서문시장"]
        )
        XCTAssertEqual(CityCovers.all.map(\.placeEn), [
            "Ttukseom Hangang Park", "Gamcheon Culture Village", "Gyeongju Oreung Royal Tombs", "Gunsan Oreum",
            "Wolmido", "Hwaseong Haenggung Palace", "Homigot Sunrise Plaza", "Seomun Market",
        ])
        XCTAssertEqual(
            CityCovers.all.map(\.workKo),
            ["마이데몬", "유미의 세포들", "21세기 대군부인", "폭싹 속았수다", "브로커", "왕의 남자", "스프링 피버", "김비서가 왜 그럴까"]
        )
        XCTAssertEqual(CityCovers.all.map(\.workEn), [
            "My Demon", "Yumi's Cells", "Perfect Crown", "When Life Gives You Tangerines",
            "Broker", "King and the Clown", "Spring Fever", "What's Wrong with Secretary Kim",
        ])
    }

    func testCoverAssetNamesAreUniqueAndNonEmpty() {
        let assets = CityCovers.all.map(\.asset)
        XCTAssertEqual(assets.count, 8)
        XCTAssertEqual(Set(assets).count, 8)
        XCTAssertFalse(assets.contains(""))
    }

    func testFixedFindsCoverByKey() {
        XCTAssertEqual(CityCovers.fixed("서울")?.placeId, 186)
        XCTAssertEqual(CityCovers.fixed("대구")?.placeEn, "Seomun Market")
        XCTAssertNil(CityCovers.fixed("강릉"))
    }

    // MARK: 도시 칩

    func testChipsKeepServerOrderAndLimitEight() {
        XCTAssertEqual(HomeCityRules.chipLimit, 8)
        let keys = ["서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구", "강릉", "전주"]
        let chips = HomeCityRules.chips(keys.map { city($0, 5) })
        XCTAssertEqual(chips.map(\.key), Array(keys.prefix(8)))
    }

    func testChipsDropCitiesWithoutPlaces() {
        let chips = HomeCityRules.chips([city("서울", 3), city("빈곳", 0), city("부산", 1)])
        XCTAssertEqual(chips.map(\.key), ["서울", "부산"])
    }

    // MARK: 사진

    func testPhotoPrefersFixedAsset() {
        let asset = CityCovers.fixed("서울")?.asset ?? "missing"
        XCTAssertEqual(HomeCityRules.photo(cityKey: "서울", topPlaceImageUrl: "https://x.example/a.jpg"), .asset(asset))
    }

    func testPhotoFallsBackToTopPlaceAbsoluteUrl() {
        XCTAssertEqual(
            HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "https://img.example/p.jpg"),
            .remote("https://img.example/p.jpg")
        )
        XCTAssertEqual(
            HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "http://img.example/p.jpg"),
            .remote("http://img.example/p.jpg")
        )
    }

    func testPhotoRejectsNonHttpOrRelativeUrl() {
        // 대조: 같은 도시라도 절대 https 주소면 받는다.
        XCTAssertEqual(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "https://img.example/ok.jpg"), .remote("https://img.example/ok.jpg"))
        XCTAssertNil(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: nil))
        XCTAssertNil(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "/img/p.jpg"))
        XCTAssertNil(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "ftp://img.example/p.jpg"))
        XCTAssertNil(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: "https://"))
        XCTAssertNil(HomeCityRules.photo(cityKey: "강릉", topPlaceImageUrl: ""))
    }

    // MARK: 사진 위 글

    func testCaptionKoEn() {
        guard let seoul = CityCovers.fixed("서울") else { return XCTFail("서울 고정 사진 없음") }
        XCTAssertEqual(HomeCityRules.caption(seoul, lang: .ko), "서울 · 뚝섬한강공원")
        XCTAssertEqual(HomeCityRules.caption(seoul, lang: .en), "Seoul · Ttukseom Hangang Park")
    }

    // MARK: 슬라이드

    func testSlideTiming() {
        XCTAssertEqual(CitySlideRules.interval, 4)
        XCTAssertEqual(CitySlideRules.crossfade, 1.2, accuracy: 0.0001)
        XCTAssertEqual(CitySlideRules.zoomDuration, 5)
        XCTAssertEqual(CitySlideRules.zoomScale, 1.06, accuracy: 0.0001)
    }

    func testNextWraps() {
        XCTAssertEqual(CitySlideRules.next(after: 0, count: 8), 1)
        XCTAssertEqual(CitySlideRules.next(after: 7, count: 8), 0)
        XCTAssertEqual(CitySlideRules.next(after: 0, count: 0), 0)
    }

    func testAdvancesOnlyWithTwoOrMoreAndMotionAllowed() {
        XCTAssertTrue(CitySlideRules.advances(count: 8, reduceMotion: false))
        XCTAssertTrue(CitySlideRules.advances(count: 2, reduceMotion: false))
        XCTAssertFalse(CitySlideRules.advances(count: 1, reduceMotion: false))
        XCTAssertFalse(CitySlideRules.advances(count: 0, reduceMotion: false))
        XCTAssertFalse(CitySlideRules.advances(count: 8, reduceMotion: true))
    }

    func testMountedOnlyCurrentPreviousNext() {
        let mounted = (0 ..< 8).filter { CitySlideRules.isMounted($0, current: 3, count: 8) }
        XCTAssertEqual(mounted, [2, 3, 4])
    }

    func testMountedWrapsAtEdges() {
        XCTAssertEqual((0 ..< 8).filter { CitySlideRules.isMounted($0, current: 0, count: 8) }, [0, 1, 7])
        XCTAssertEqual((0 ..< 8).filter { CitySlideRules.isMounted($0, current: 7, count: 8) }, [0, 6, 7])
    }
}
