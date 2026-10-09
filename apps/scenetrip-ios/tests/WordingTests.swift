@testable import SceneTrip
import XCTest

/// 화면에 적는 말의 작은 규칙 (MZ2AZ-372 의 7번).
final class WordingTests: XCTestCase {
    // MARK: 지도 핀 이름표

    func testShortNameIsKept() {
        XCTAssertEqual(PinCaption.text("Gyeongbokgung Palace"), "Gyeongbokgung Palace")
        XCTAssertEqual(PinCaption.text("  경복궁 "), "경복궁")
    }

    /// 80자 영어 이름이 한 줄로 뻗어 옆 핀을 덮었다 — 앞머리만 남긴다.
    func testLongNameIsCutWithEllipsis() {
        let long = "Korea National University of Arts Seokgwan-dong Campus Main Building Annex Hall"
        let shown = PinCaption.text(long)
        XCTAssertEqual(shown.count, PinCaption.limit)
        XCTAssertTrue(shown.hasSuffix("…"))
        XCTAssertTrue(long.hasPrefix(String(shown.dropLast())))
    }

    func testNameAtTheLimitIsNotCut() {
        let exact = String(repeating: "가", count: PinCaption.limit)
        XCTAssertEqual(PinCaption.text(exact), exact)
    }

    // MARK: 작품 카드의 다른 이름 배지

    /// 티켓의 7번 — 첫 별칭이 일본어·중국어면 그것이 배지로 떴다. 로마자 별칭을 고른다.
    func testPicksRomanAliasNotTheFirstOne() {
        let aliases = ["ソンジェ背負って走れ", "背著善宰跑", "Lovely Runner"]
        XCTAssertEqual(AliasBadge.pick(title: "선재 업고 튀어", aliases: aliases, matched: nil), "Lovely Runner")
    }

    /// 사용자가 친 말이 별칭에 걸렸으면 그 표기를 보인다 — 어느 글자든.
    func testMatchedTermWins() {
        let aliases = ["Goblin", "トッケビ"]
        XCTAssertEqual(AliasBadge.pick(title: "도깨비", aliases: aliases, matched: "トッケビ"), "トッケビ")
    }

    /// 걸린 표기가 제목 그대로면 배지로 또 적지 않고 영어 제목으로 간다.
    func testMatchedTermSameAsTitleFallsThrough() {
        XCTAssertEqual(AliasBadge.pick(title: "도깨비", aliases: ["Goblin"], matched: "도깨비"), "Goblin")
    }

    /// 영어 화면에서는 제목이 이미 영어다 — 같은 글자를 배지로 또 붙이지 않는다.
    func testAliasEqualToTitleIsSkipped() {
        XCTAssertNil(AliasBadge.pick(title: "Lovely Runner", aliases: ["lovely runner", "背著善宰跑"], matched: nil))
    }

    /// 로마자 별칭이 없으면 배지가 없다.
    func testNoRomanAliasMeansNoBadge() {
        XCTAssertNil(AliasBadge.pick(title: "폭싹 속았수다", aliases: ["おつかれさま", "苦盡柑來遇見你"], matched: nil))
        XCTAssertNil(AliasBadge.pick(title: "도깨비", aliases: [], matched: nil))
        XCTAssertNil(AliasBadge.pick(title: "도깨비", aliases: ["", "  "], matched: " "))
    }

    func testRomanDetection() {
        XCTAssertTrue(AliasBadge.isRoman("Guardian: The Lonely and Great God"))
        XCTAssertTrue(AliasBadge.isRoman("Café Minamdang 2"))
        XCTAssertFalse(AliasBadge.isRoman("도깨비"))
        XCTAssertFalse(AliasBadge.isRoman("Goblin 도깨비"))
        XCTAssertFalse(AliasBadge.isRoman("2016"))
    }

    // MARK: 수가 든 문구

    /// 한국어는 수에 따라 달라지지 않는다(시험은 한국어로 돈다 — 영어의 「1 place」 는 번역 표의 `|하나` 줄이 한다).
    func testCountedPhraseInKorean() {
        XCTAssertEqual(trCount("%lld곳", 1), "1곳")
        XCTAssertEqual(trCount("%lld곳", 12), "12곳")
        XCTAssertEqual(trCount("촬영지 %lld", 1), "촬영지 1")
    }
}
