import Combine
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 앱 언어와 지도·시스템 글자·받아 둔 사본이 어긋나지 않는가 (MZ2AZ-529, 웹 c5724c98 따라잡기).
final class LanguageSwitchTests: XCTestCase {
    // MARK: 지도 언어

    /// 지도 지명은 **앱 언어**로 정한다 — 기기 언어(시스템 로캘)에 맡기면 한국어 기기의 영어 화면에
    /// 지명만 한국어로 나온다(2026-11-07 시뮬레이터 재현).
    func testMapLocaleFollowsTheAppLanguage() {
        XCTAssertEqual(NaverMapLocale.code(for: .en), "en")
        XCTAssertEqual(NaverMapLocale.code(for: .ko), "ko")
    }

    /// 번역 표가 없는 언어(고를 수 없는 언어)는 지도도 시스템 로캘에 둔다.
    func testMapLocaleLeavesUnofferedLanguagesToTheSystem() {
        XCTAssertNil(NaverMapLocale.code(for: .ja))
        XCTAssertNil(NaverMapLocale.code(for: .zhHant))
    }

    // MARK: 시스템 언어 키(AppleLanguages)

    /// 고른 적이 없으면 시작 언어(영어)를 적는다 — 한국어 기기에서 영어 화면인데 권한 창·시트 낭독이
    /// 한국어로 남지 않게. 다음 실행부터 반영된다.
    func testNoSavedLanguageWritesTheStartingLanguage() {
        XCTAssertEqual(AppLanguage.systemLanguage(saved: nil), .en)
    }

    /// 읽을 수 없는 저장값도 시작 언어(영어)와 같다 — 화면이 영어이므로 시스템 글자도 영어.
    func testUnreadableSavedLanguageWritesTheStartingLanguage() {
        XCTAssertEqual(AppLanguage.systemLanguage(saved: "fr"), .en)
        XCTAssertEqual(AppLanguage.systemLanguage(saved: ""), .en)
    }

    /// 고른 사람은 고른 그대로다(MZ2AZ-367 규칙).
    func testSavedLanguageIsWrittenAsChosen() {
        XCTAssertEqual(AppLanguage.systemLanguage(saved: "ko"), .ko)
        XCTAssertEqual(AppLanguage.systemLanguage(saved: "en"), .en)
    }

    // MARK: 받아 둔 찜 목록

    /// 찜 목록의 작품 이름은 받을 때의 언어다. 언어가 바뀌면 새 언어로 다시 받는다 —
    /// `LikeStore` 는 앱에 하나라 화면을 다시 만들어도(`RootTabs().id`) 남는다.
    @MainActor
    func testLikedWorksAreFetchedAgainWhenTheLanguageChanges() async throws {
        let suite = "LanguageSwitchTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set([7], forKey: "scenetrip.likedContents")
        defaults.set(true, forKey: "scenetrip.likesOnServer")

        var title = "사랑의 불시착"
        var fetches = 0
        // 앱 전체의 `AppLanguage.changes` 대신 이 시험만의 알림 — 시험 프로세스의 `LikeStore.shared` 가 서버로 가지 않게.
        let changes = PassthroughSubject<Lang, Never>()
        let likes = LikeStore(defaults: defaults, refreshOnInit: false, fetchSummaries: {
            fetches += 1
            return [ContentSummary(id: 7, category: .drama, title: title, placeCount: 1)]
        }, languageChanges: changes.eraseToAnyPublisher())
        await likes.refresh()
        XCTAssertEqual(likes.likedWorks.map(\.title), ["사랑의 불시착"])

        title = "Crash Landing on You"
        changes.send(.en)
        for _ in 0 ..< 50 where likes.likedWorks.first?.title != title {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(likes.likedWorks.map(\.title), ["Crash Landing on You"])
        XCTAssertEqual(fetches, 2)
        XCTAssertEqual(likes.contentIds, [7], "다시 받기는 요약만 바꾼다 — 하트는 그대로")
    }
}
