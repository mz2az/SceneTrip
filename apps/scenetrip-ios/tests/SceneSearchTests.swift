@testable import SceneTrip
import XCTest

/// 계획서 §3-2 · §3-3 · §3-5 가 정한 규칙을 고정한다.
///
/// 픽스처는 손으로 만든다 — 목데이터 파일에 기대면 데이터가 바뀔 때 테스트가 같이
/// 흔들리고, 무엇이 깨졌는지 읽기 어려워진다. 검사 대상은 데이터가 아니라 규칙이다.
final class SceneSearchTests: XCTestCase {
    // 도깨비: 공유·김고은 출연, 촬영지 셋(카페 / 음식점 / 한옥마을).
    private let goblinCafe = SceneSearchTests.row(
        id: "g1", title: "도깨비", aliases: ["Goblin"], cast: "공유, 김고은",
        place: PlaceFixture(name: "중앙highschool", type: "카페", address: "서울 종로구 계동길 71")
    )
    private let goblinFood = SceneSearchTests.row(
        id: "g2", title: "도깨비", aliases: ["Goblin"], cast: "공유, 김고은",
        place: PlaceFixture(name: "BBQ 삼청점", type: "음식점", address: "서울 종로구 삼청로 100")
    )
    private let goblinVillage = SceneSearchTests.row(
        id: "g3", title: "도깨비", aliases: ["Goblin"], cast: "공유, 김고은",
        place: PlaceFixture(name: "북촌한옥마을", type: "한옥마을", address: "서울 종로구 계동길 37")
    )
    /// 다른 작품 — 검색이 새지 않는지 보는 대조군.
    private let itaewon = SceneSearchTests.row(
        id: "i1", title: "이태원 클라쓰", aliases: [], cast: "박서준",
        place: PlaceFixture(name: "녹사평역", type: "역/교통", address: "서울 용산구 녹사평대로 195")
    )

    private var all: [SceneRow] {
        [goblinCafe, goblinFood, goblinVillage, itaewon]
    }

    private var works: [Work] {
        [
            Work(title: "도깨비", rows: [goblinCafe, goblinFood, goblinVillage]),
            Work(title: "이태원 클라쓰", rows: [itaewon]),
        ]
    }

    // MARK: §3-2 검색이 걸리는 범위

    func testWorkTitleFillsBothTabs() {
        let places = all.filter { SceneSearch.matches($0, "도깨비") }
        XCTAssertEqual(places.count, 3, "작품 제목으로 그 작품의 촬영지가 전부 걸려야 한다")
        XCTAssertEqual(SceneSearch.works(works, query: "도깨비").map(\.title), ["도깨비"])
    }

    func testEnglishAliasAlsoMatches() {
        XCTAssertEqual(all.filter { SceneSearch.matches($0, "Goblin") }.count, 3)
        XCTAssertEqual(SceneSearch.works(works, query: "goblin").map(\.title), ["도깨비"])
    }

    /// §3-2 의 핵심 — 배우로 검색하면 **장소 탭까지** 채워진다.
    /// 프로토타입에서 이것이 빠졌을 때 탭 하나가 죽은 화면이 됐다.
    func testCastNameFillsPlaceTab() {
        let places = all.filter { SceneSearch.matches($0, "공유") }
        XCTAssertEqual(places.count, 3, "배우 이름으로 그 사람 작품의 촬영지가 전부 걸려야 한다")
        XCTAssertEqual(SceneSearch.works(works, query: "공유").map(\.title), ["도깨비"])
    }

    func testPlaceNameMatchesOnlyThatPlace() {
        let places = all.filter { SceneSearch.matches($0, "북촌한옥마을") }
        XCTAssertEqual(places.map(\.id), ["g3"])
    }

    /// 지역은 주소 앞 두 토막에서 파생된다 — 별칭 컬럼에 몰아넣지 않는다 (§3-4).
    func testRegionMatchesPlacesInThatArea() {
        XCTAssertEqual(all.filter { SceneSearch.matches($0, "종로구") }.count, 3)
        XCTAssertEqual(all.filter { SceneSearch.matches($0, "용산구") }.map(\.id), ["i1"])
    }

    func testUnrelatedQueryMatchesNothing() {
        XCTAssertTrue(all.filter { SceneSearch.matches($0, "존재하지않는말") }.isEmpty)
    }

    // MARK: §3-3 랭킹과 배지

    func testRankingPrefersPrefixOverContains() {
        XCTAssertEqual(MatchScore.of("북촌한옥마을", "북촌"), MatchScore.prefix)
        XCTAssertEqual(MatchScore.of("서울 종로구 계동길", "종로구"), MatchScore.wordPrefix)
        XCTAssertEqual(MatchScore.of("북촌한옥마을", "한옥"), MatchScore.contains)
        XCTAssertNil(MatchScore.of("북촌한옥마을", "부산"))
    }

    func testSuggestionsAreGroupedWorkThenPersonThenPlace() {
        let kinds = SceneSearch.suggestions(rows: all, works: works, query: "ㅇ")
            .map(\.kind.rawValue)
        XCTAssertEqual(kinds, kinds.sorted(), "작품 → 인물 → 장소 순서가 지켜져야 한다")
    }

    /// 배우로 걸린 작품에만 배지를 단다 — 제목으로 걸린 것과 구분한다.
    func testCastBadgeOnlyWhenMatchedByPerson() {
        let goblin = works[0]
        XCTAssertEqual(SceneSearch.castBadge(goblin, query: "공유"), "출연 공유")
        XCTAssertNil(SceneSearch.castBadge(goblin, query: "도깨비"))
        XCTAssertNil(SceneSearch.castBadge(goblin, query: "Goblin"))
    }

    // MARK: §3-5 카테고리 칩

    /// 칩은 목록과 지도를 **둘 다** 좁힌다. 프로토타입은 목록만 좁혔으나 뒤집었다 —
    /// 목록에 없는 핀이 지도에 남으면 그 핀을 눌렀을 때 목록에 없는 장소가 열린다.
    func testChipNarrowsTheSharedResultSet() {
        let searchRows = all.filter { SceneSearch.matches($0, "도깨비") }
        let narrowed = searchRows.filter { CategoryChip.of($0.placeType) == "음식점·카페" }
        XCTAssertEqual(narrowed.map(\.id).sorted(), ["g1", "g2"])
        XCTAssertEqual(searchRows.count, 3, "칩은 검색 결과 자체를 바꾸지 않는다")
    }

    func testChipMappingCoversKnownTypes() {
        XCTAssertEqual(CategoryChip.of("카페"), "음식점·카페")
        XCTAssertEqual(CategoryChip.of("한옥마을"), "명소·자연")
        XCTAssertEqual(CategoryChip.of("역/교통"), "거리·다리")
        // 표에 없는 값은 버리지 않고 건물·시설로 떨어뜨린다 — 서버가 37 종 자유
        // 문자열을 내려주므로 표에 없는 값이 언제든 온다 (§4).
        XCTAssertEqual(CategoryChip.of("처음보는유형"), "건물·시설")
    }

    // MARK: 픽스처

    /// `SceneRow` 의 멤버와이즈 이니셜라이저를 그대로 쓴다 — `init(from:)` 이 확장에
    /// 있어 합성 이니셜라이저가 살아 있고, `@testable import` 로 접근된다.
    /// JSON 을 거치지 않으므로 픽스처가 디코딩 실패로 깨질 여지가 없다.
    /// 장소 세 값을 묶는다 — 인자를 늘어놓으면 호출부에서 순서를 헷갈린다.
    private struct PlaceFixture {
        let name: String
        let type: String
        let address: String
    }

    private static func row(
        id: String, title: String, aliases: [String], cast: String,
        place: PlaceFixture
    ) -> SceneRow {
        SceneRow(
            id: id, title: title, aliases: aliases, category: "드라마",
            network: "tvN", year: "2016", genre: "판타지", cast: cast,
            director: "이응복", famousRank: 1, poster: "",
            placeName: place.name, placeType: place.type, address: place.address,
            lat: 37.58, lng: 126.98, sceneDesc: ""
        )
    }
}
