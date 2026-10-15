@testable import SceneTrip
import XCTest

@MainActor
final class RouteCartHintTests: XCTestCase {
    func testOnlyNewManualEmptyCourseConsumesHint() {
        let store = RouteCartHintStore(storage: MemoryAIConsentStorage())
        XCTAssertFalse(store.claim(isNew: false, madeByAI: false, isEmpty: true))
        XCTAssertFalse(store.claim(isNew: true, madeByAI: true, isEmpty: true))
        XCTAssertFalse(store.claim(isNew: true, madeByAI: false, isEmpty: false))
        XCTAssertTrue(store.claim(isNew: true, madeByAI: false, isEmpty: true))
        XCTAssertFalse(store.claim(isNew: true, madeByAI: false, isEmpty: true))
    }

    func testSeenFlagSurvivesStoreRecreationIncludingDismissal() {
        let storage = MemoryAIConsentStorage()
        XCTAssertTrue(RouteCartHintStore(storage: storage).claim(isNew: true, madeByAI: false, isEmpty: true))
        // 표시 시점에 기록하므로 닫기·시간 만료·화면 이탈 후에도 다시 나타나지 않는다.
        XCTAssertFalse(RouteCartHintStore(storage: storage).claim(isNew: true, madeByAI: false, isEmpty: true))
    }

    func testCartCopyDependsOnSavedPlaces() {
        XCTAssertEqual(RouteCartHintStore.messageKey(cartIsEmpty: true), "장소를 담으면 여기서 코스에 넣을 수 있어요")
        XCTAssertEqual(RouteCartHintStore.messageKey(cartIsEmpty: false), "담아둔 장소로 코스를 시작해 보세요")
    }

    func testHintAndNearbyCopyIsBundledInKoreanAndEnglish() throws {
        let keys = [
            RouteCartHintStore.messageKey(cartIsEmpty: true),
            RouteCartHintStore.messageKey(cartIsEmpty: false),
            "지도 주변 촬영지 보기", "코스 추천받기 (AI)",
            "AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요.",
        ]
        for language in ["ko", "en"] {
            let url = try XCTUnwrap(Bundle(for: Self.self).url(
                forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: language
            ))
            let values = try XCTUnwrap(PropertyListSerialization.propertyList(
                from: Data(contentsOf: url), format: nil
            ) as? [String: String])
            for key in keys {
                let value = try XCTUnwrap(values[key])
                XCTAssertFalse(value.isEmpty)
                if language == "en" {
                    XCTAssertNotEqual(key, value)
                }
            }
        }
    }
}
