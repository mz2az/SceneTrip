@testable import SceneTrip
import XCTest

/// 코스 편집의 장소 검색이 서버에 **무엇을, 언제** 묻는가 (MZ2AZ-372 의 6번).
final class RoutePlaceSearchTests: XCTestCase {
    /// 앞뒤 공백은 뗀다 — 「 Gyeongbok 」 과 「Gyeongbok」 은 같은 검색이다.
    func testKeywordIsTrimmed() {
        XCTAssertEqual(RoutePlaceSearch.keyword(from: "  Gyeongbok \n"), "Gyeongbok")
        XCTAssertEqual(RoutePlaceSearch.keyword(from: "경복궁"), "경복궁")
    }

    /// 빈 입력은 검색어 없음 — 서버가 인기순을 준다. 빈 문자열을 `q` 로 보내지 않는다.
    func testEmptyInputMeansNoKeyword() {
        XCTAssertNil(RoutePlaceSearch.keyword(from: ""))
        XCTAssertNil(RoutePlaceSearch.keyword(from: "   "))
    }

    /// 계약의 `q` 는 100자까지다 — 넘겨 보내면 400 이다.
    func testKeywordIsCutAtTheContractLimit() {
        let long = String(repeating: "a", count: 250)
        XCTAssertEqual(RoutePlaceSearch.keyword(from: long)?.count, RoutePlaceSearch.maxLength)
    }

    /// 글자를 치는 동안에는 기다렸다 한 번 묻는다. 처음 열 때(빈 검색어)는 기다릴 타자가 없어 바로 묻는다.
    func testTypingWaitsButOpeningDoesNot() {
        XCTAssertEqual(RoutePlaceSearch.delay(for: "Gyeongbok"), RoutePlaceSearch.debounce)
        XCTAssertEqual(RoutePlaceSearch.delay(for: nil), .zero)
        XCTAssertGreaterThan(RoutePlaceSearch.debounce, .milliseconds(200))
    }
}
