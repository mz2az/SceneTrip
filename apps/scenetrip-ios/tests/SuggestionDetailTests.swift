import SceneApiClient
@testable import SceneTrip
import XCTest

/// 자동완성 장소 행의 보조 문구 (MZ2AZ-450).
final class SuggestionDetailTests: XCTestCase {
    func testPlaceDetailJoinsAvailablePartsOrFallsBackToSubtitle() {
        let cases: [(String?, String?, String)] = [
            ("hotel", "눈물의 여왕", "호텔 · 눈물의 여왕"),
            ("hotel", nil, "호텔"),
            (nil, "눈물의 여왕", "눈물의 여왕"),
            (nil, nil, "서울 용산구"),
            ("unknown_type", "눈물의 여왕", "눈물의 여왕"),
            ("unknown_type", nil, "서울 용산구"),
            ("", "", "서울 용산구"),
            (" \n", " \n", "서울 용산구"),
            ("hotel", "", "호텔"),
            ("", "눈물의 여왕", "눈물의 여왕"),
        ]
        for (placeType, contentTitle, expected) in cases {
            let item = Suggestion(
                type: .place, id: 1, name: "몬드리안서울 이태원", subtitle: "서울 용산구",
                placeType: placeType, contentTitle: contentTitle
            )
            XCTAssertEqual(SuggestionDetail.text(for: item, korean: true), expected, "\(item)")
        }
    }

    func testEnglishLabelKeepsServerLocalizedTitle() {
        let item = Suggestion(
            type: .place, id: 1, name: "Mondrian Seoul Itaewon", subtitle: "Yongsan-gu, Seoul",
            placeType: "hotel", contentTitle: "Queen of Tears"
        )
        XCTAssertEqual(SuggestionDetail.text(for: item, korean: false), "Hotel · Queen of Tears")
    }

    func testNonPlaceRowsKeepSubtitle() {
        for type in [EntityType.content, .person] {
            let item = Suggestion(
                type: type, id: 1, name: "이름", subtitle: "기존 보조 문구",
                placeType: "hotel", contentTitle: "눈물의 여왕"
            )
            XCTAssertEqual(SuggestionDetail.text(for: item, korean: true), "기존 보조 문구")
        }
    }

    func testMissingSubtitleAndPartsReturnEmptyText() {
        let item = Suggestion(type: .place, id: 1, name: "장소")
        XCTAssertEqual(SuggestionDetail.text(for: item, korean: true), "")
    }
}
