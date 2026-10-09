import SceneApiClient
@testable import SceneTrip
import XCTest

/// 촬영지 유형을 화면에 적고 칩으로 묶는 규칙 (MZ2AZ-372).
///
/// 2026-10-08 에 올린 촬영지 표부터 유형이 코드(`cafe`)로 온다. 앱이 한국어 라벨만 알던 때는 분류 칩이
/// 전부 0건이었고 `store_daily`·`public_office` 가 화면에 그대로 찍혔다.
final class PlaceTypeTests: XCTestCase {
    /// 로컬 DB(128편·486곳)에 실제로 있는 코드 서른네 가지 — 하나도 표에서 빠지면 안 된다.
    private static let seenCodes = [
        "landmark", "school", "restaurant", "hotel", "nature", "cafe", "beach", "building", "temple",
        "street", "public_office", "museum", "port", "park", "shop", "theme_park", "church", "bridge",
        "experience", "theater", "station", "market", "hospital", "store_daily", "wedding_hall", "bar",
        "stadium", "set", "mall", "village", "funeral_hall", "library", "viewpoint", "bookstore",
    ]

    // MARK: 칩

    /// 티켓의 3번 — 「음식·카페」 를 누르면 0건이었다. 코드로 온 유형이 칩에 묶인다.
    func testCodesFallIntoChips() {
        XCTAssertEqual(PlaceType.group(of: "restaurant"), .food)
        XCTAssertEqual(PlaceType.group(of: "cafe"), .food)
        XCTAssertEqual(PlaceType.group(of: "store_daily"), .food)
        XCTAssertEqual(PlaceType.group(of: "landmark"), .sight)
        XCTAssertEqual(PlaceType.group(of: "beach"), .sight)
        XCTAssertEqual(PlaceType.group(of: "bridge"), .street)
        XCTAssertEqual(PlaceType.group(of: "station"), .street)
        XCTAssertEqual(PlaceType.group(of: "school"), .facility)
        XCTAssertEqual(PlaceType.group(of: "public_office"), .facility)
    }

    /// 코드와 옛 한국어 라벨이 **같은 칩**에 묶인다 — DB 에 둘이 섞여 있다.
    func testCodeAndLegacyLabelShareAChip() {
        XCTAssertEqual(PlaceType.group(of: "병원"), PlaceType.group(of: "hospital"))
        XCTAssertEqual(PlaceType.group(of: "항구"), PlaceType.group(of: "port"))
        XCTAssertEqual(PlaceType.group(of: "근린공원"), PlaceType.group(of: "park"))
        XCTAssertEqual(PlaceType.group(of: "자연"), PlaceType.group(of: "nature"))
    }

    func testEverySeenCodeIsInTheTable() {
        for code in Self.seenCodes {
            XCTAssertNotNil(PlaceType.entry(of: code), "\(code) 가 표에 없다")
            XCTAssertNotEqual(PlaceType.group(of: code), .other, "\(code) 가 기타로 떨어진다")
        }
    }

    /// 표에 없는 값·빈 값은 「기타」 — 어느 칩에서도 안 보이는 곳이 없어야 한다.
    func testUnknownAndEmptyGoToOther() {
        for raw in ["보증보험", "방면정보", "피아노", "brand_new_code", "", "  "] {
            XCTAssertEqual(PlaceType.group(of: raw), .other, raw)
        }
        XCTAssertEqual(PlaceType.group(of: nil), .other)
    }

    // MARK: 표시말

    /// 티켓의 4번 — 코드가 화면에 그대로 나왔다.
    func testCodesAreShownAsWords() {
        // 편의점과 마트를 함께 덮는 말 — 「Convenience store」 라고 적으면 마트가 틀린다.
        XCTAssertEqual(PlaceType.label("store_daily", korean: false), "Store")
        XCTAssertEqual(PlaceType.label("public_office", korean: false), "Public office")
        XCTAssertEqual(PlaceType.label("set", korean: false), "Film set")
        XCTAssertEqual(PlaceType.label("store_daily", korean: true), "편의점·마트")
        XCTAssertEqual(PlaceType.label("public_office", korean: true), "관공서")
        XCTAssertEqual(PlaceType.label("set", korean: true), "세트장")
    }

    /// 한국어 라벨로 들어온 옛 값도 영어 화면에서는 영어다.
    func testLegacyKoreanLabelIsEnglishOnEnglishScreen() {
        XCTAssertEqual(PlaceType.label("병원", korean: false), "Hospital")
        XCTAssertEqual(PlaceType.label("병원", korean: true), "병원")
        XCTAssertEqual(PlaceType.label("근린공원", korean: false), "Park")
    }

    /// **모르는 값은 날것으로 내지 않는다** — nil 이면 화면이 그 자리를 비운다.
    func testUnknownIsHiddenNotShownRaw() {
        for raw in ["보증보험", "법률사무소", "brand_new_code", "", " "] {
            XCTAssertNil(PlaceType.label(raw, korean: true), raw)
            XCTAssertNil(PlaceType.label(raw, korean: false), raw)
        }
        XCTAssertNil(PlaceType.label(nil, korean: false))
    }

    func testCodeLookupIgnoresCaseAndSpaces() {
        XCTAssertEqual(PlaceType.label(" Cafe ", korean: false), "Café")
    }

    // MARK: 표 자체

    func testCodesAreUnique() {
        let codes = PlaceType.entries.map(\.code)
        XCTAssertEqual(Set(codes).count, codes.count)
    }

    /// 표시말이 빠졌거나 코드를 그대로 베껴 넣은 줄이 없어야 한다.
    func testEveryEntryHasBothLabels() {
        for entry in PlaceType.entries {
            XCTAssertFalse(entry.korean.isEmpty, entry.code)
            XCTAssertFalse(entry.english.isEmpty, entry.code)
            XCTAssertFalse(entry.english.contains("_"), entry.code)
            XCTAssertNotEqual(entry.korean, entry.code)
        }
    }

    /// 옛 라벨은 전부 표에 있는 코드를 가리킨다 — 오타가 나면 그 라벨이 조용히 「기타」 가 된다.
    func testEveryLegacyLabelPointsAtAKnownCode() {
        let codes = Set(PlaceType.entries.map(\.code))
        for (label, code) in PlaceType.legacy {
            XCTAssertTrue(codes.contains(code), "\(label) → \(code)")
        }
    }

    // MARK: 직접 찍은 핀

    /// 방금 찍은 핀은 앱의 갈래 이름으로, 서버에서 다시 읽은 핀은 계약의 값으로 온다.
    func testPinKindAcceptsBothForms() {
        XCTAssertEqual(PlaceType.pinKind("숙소"), "숙소")
        XCTAssertEqual(PlaceType.pinKind("lodging"), "숙소")
        XCTAssertEqual(PlaceType.pinKind("food"), "음식점·카페")
        XCTAssertEqual(PlaceType.pinKind("attraction"), "명소·자연")
        XCTAssertNil(PlaceType.pinKind("cafe"))
        XCTAssertNil(PlaceType.pinKind(nil))
    }

    /// 핀을 고르는 다섯 갈래에 「기타」 는 없다 — 계약의 `PinCategory` 가 다섯이다.
    func testPinKindsAreTheFiveOfTheContract() {
        XCTAssertEqual(PlaceType.pinKinds, ["숙소", "음식점·카페", "명소·자연", "거리·다리", "건물·시설"])
    }

    /// 저장해 둔 코스를 열어 다시 저장해도 핀의 분류가 그대로다 — 전에는 전부 `building` 이 됐다.
    func testPinCategorySurvivesARoundTrip() {
        XCTAssertEqual(RouteBridge.pinCategory(from: "food"), .food)
        XCTAssertEqual(RouteBridge.pinCategory(from: "lodging"), .lodging)
        XCTAssertEqual(RouteBridge.pinCategory(from: "attraction"), .attraction)
        XCTAssertEqual(RouteBridge.pinCategory(from: "street"), .street)
        XCTAssertEqual(RouteBridge.pinCategory(from: "building"), .building)
        XCTAssertEqual(RouteBridge.pinCategory(from: "음식점·카페"), .food)
        XCTAssertEqual(RouteBridge.pinCategory(from: nil), .building)
    }

    /// 코스 줄 — 핀이면 핀의 갈래, 촬영지면 유형의 표시말. 핀의 `building` 과 촬영지의 `building` 은 다른 말이다.
    func testStopLabelSeparatesPinsFromPlaces() {
        let english: (String) -> String = { "EN(\($0))" }
        XCTAssertEqual(PlaceType.stopLabel("food", pinned: true, korean: false, translate: english), "EN(음식점·카페)")
        XCTAssertEqual(PlaceType.stopLabel("building", pinned: true, korean: true, translate: { $0 }), "건물·시설")
        XCTAssertEqual(PlaceType.stopLabel("building", pinned: false, korean: true, translate: { $0 }), "건물")
        XCTAssertEqual(PlaceType.stopLabel("cafe", pinned: false, korean: false, translate: english), "Café")
        XCTAssertNil(PlaceType.stopLabel("보증보험", pinned: false, korean: true, translate: { $0 }))
    }
}
