@testable import SceneTrip
import XCTest

/// 닉네임 규칙 (MZ2AZ-363). 계약: 앞뒤 공백을 뗀 뒤 2~16자, 한글·영문·숫자·`_`.
final class NicknameRulesTests: XCTestCase {
    func testLengthBoundaries() {
        XCTAssertEqual(NicknameRules.problem(in: "가"), .tooShort)
        XCTAssertNil(NicknameRules.problem(in: "가나"))
        XCTAssertNil(NicknameRules.problem(in: String(repeating: "a", count: 16)))
        XCTAssertEqual(NicknameRules.problem(in: String(repeating: "a", count: 17)), .tooLong)
        XCTAssertEqual(NicknameRules.problem(in: ""), .tooShort)
    }

    /// 길이는 바이트가 아니라 **글자 수**다 — 한글 16자는 되고 17자는 안 된다.
    func testLengthCountsCharactersNotBytes() {
        XCTAssertNil(NicknameRules.problem(in: String(repeating: "가", count: 16)))
        XCTAssertEqual(NicknameRules.problem(in: String(repeating: "가", count: 17)), .tooLong)
    }

    /// 풀어쓴(NFD) 한글은 합친 꼴로 본다 — 맥에서 붙여 넣으면 이렇게 온다.
    func testDecomposedHangulIsComposed() {
        let decomposed = "제주".decomposedStringWithCanonicalMapping
        XCTAssertNotEqual(Array(decomposed.unicodeScalars).count, 2)
        XCTAssertNil(NicknameRules.problem(in: decomposed))
        XCTAssertEqual(NicknameRules.normalized(decomposed), "제주")
    }

    /// 눈에 안 보이는 한글 채움 문자로 빈칸 닉네임을 만들 수 없다.
    func testInvisibleFillerIsRejected() {
        XCTAssertEqual(NicknameRules.problem(in: "\u{3164}\u{3164}"), .badCharacters)
    }

    /// 길이는 **공백을 뗀 뒤** 센다. 보내는 값도 뗀 것이다.
    func testSurroundingSpacesAreTrimmed() {
        XCTAssertNil(NicknameRules.problem(in: "  제주러버  "))
        XCTAssertEqual(NicknameRules.problem(in: " a "), .tooShort)
        XCTAssertEqual(NicknameRules.normalized("  제주러버\n"), "제주러버")
    }

    func testAllowedCharacters() {
        XCTAssertNil(NicknameRules.problem(in: "제주러버12345"))
        XCTAssertNil(NicknameRules.problem(in: "Seoul_Fan_07"))
        XCTAssertNil(NicknameRules.problem(in: "ㅋㅋ")) // 낱자는 서버에 맡긴다
        XCTAssertNil(NicknameRules.problem(in: "__"))
        XCTAssertNil(NicknameRules.problem(in: "2026"))
    }

    /// 가운데 공백·기호·이모지·한자·가나는 못 쓴다. 한 글자여도 「짧다」 가 아니라 「못 쓰는 글자」 다.
    func testForbiddenCharacters() {
        for name in ["제주 러버", "hello!", "a-b", "여행자😀", "旅行者", "たびびと", "name@x", "!"] {
            XCTAssertEqual(NicknameRules.problem(in: name), .badCharacters, name)
        }
    }

    /// 「여행자 + 숫자」 는 서버가 자동으로 붙이는 꼴이라 고를 수 없다(서버가 `NICKNAME_INVALID` 로 거절한다).
    func testAutomaticPatternIsReserved() {
        XCTAssertEqual(NicknameRules.problem(in: "여행자10001"), .reserved)
        XCTAssertEqual(NicknameRules.problem(in: " 여행자7 "), .reserved)
        XCTAssertTrue(NicknameRules.isAutomatic("여행자48213"))
        // 숫자가 없거나 다른 글자가 섞이면 보통 이름이다.
        XCTAssertNil(NicknameRules.problem(in: "여행자"))
        XCTAssertNil(NicknameRules.problem(in: "여행자_01"))
        XCTAssertNil(NicknameRules.problem(in: "여행자10001번"))
        XCTAssertNil(NicknameRules.problem(in: "제주여행자1"))
    }

    /// 로그인 응답의 `nicknameConfirmed` 가 false 일 때만, 그 계정에 한 번만 묻는다.
    func testAskOnlyOncePerAccountAndOnlyWhenUnconfirmed() throws {
        let suite = "NicknameRulesTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        XCTAssertTrue(NicknameAsked.shouldAsk(confirmed: false, accountId: "a", in: defaults))
        XCTAssertFalse(NicknameAsked.shouldAsk(confirmed: true, accountId: "a", in: defaults))
        // 옛 서버는 칸이 없다 — 묻지 않는다.
        XCTAssertFalse(NicknameAsked.shouldAsk(confirmed: nil, accountId: "a", in: defaults))

        NicknameAsked.mark("a", in: defaults)
        XCTAssertFalse(NicknameAsked.shouldAsk(confirmed: false, accountId: "a", in: defaults))
        // 다른 계정은 따로다.
        XCTAssertTrue(NicknameAsked.shouldAsk(confirmed: false, accountId: "b", in: defaults))
    }
}
