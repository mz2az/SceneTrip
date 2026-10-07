@testable import SceneTrip
import XCTest

/// 분석 이벤트의 이름과 매개변수 (MZ2AZ-353). 정본은 `docs/project/plans/analytics-events.md`.
///
/// 이름이 바뀌면 **대시보드의 퍼널이 조용히 끊긴다** — 앱은 멀쩡하고 숫자만 0 이 된다.
final class AppEventTests: XCTestCase {
    private let all: [AppEvent] = [
        .selectLanguage("en"), .tutorialBegin, .tutorialComplete,
        .signUp(method: "google"), .login(method: "google"), .logout, .deleteAccount,
        .setNickname(skipped: true),
        .screenView("home"), .search(termLength: 6, kind: "content"),
        .viewTitle(contentId: 1), .viewPlace(placeId: 38), .viewReviews(targetType: "place"),
        .likeTitle(contentId: 1, liked: true), .savePlace(placeId: 38),
        .generatePlan(dayCount: 2, titleCount: 3),
        .createCourse(origin: "ai", dayCount: 2, placeCount: 7),
        .startTrip(placeId: 38), .getDirections, .visitStamp(placeId: 38), .askGuide,
        .postReview(photoCount: 4, hasCourse: true),
    ]

    /// GA4 규칙: 소문자·숫자·밑줄, 글자로 시작, 40자 이하.
    func testNamesFollowGa4Rules() {
        for event in all {
            XCTAssertNotNil(event.name.range(of: "^[a-z][a-z0-9_]{0,39}$", options: .regularExpression), event.name)
        }
        XCTAssertEqual(Set(all.map(\.name)).count, all.count, "이름이 겹치면 두 행동이 한 줄로 합쳐진다")
    }

    /// 퍼널의 이름은 문서와 글자까지 같아야 한다.
    func testFunnelNamesAreFixed() {
        XCTAssertEqual(AppEvent.signUp(method: "google").name, "sign_up")
        XCTAssertEqual(AppEvent.savePlace(placeId: 1).name, "save_place")
        XCTAssertEqual(AppEvent.likeTitle(contentId: 1, liked: true).name, "like_title")
        XCTAssertEqual(AppEvent.createCourse(origin: "ai", dayCount: 1, placeCount: 1).name, "create_course")
        XCTAssertEqual(AppEvent.startTrip(placeId: 1).name, "start_trip")
        XCTAssertEqual(AppEvent.visitStamp(placeId: 1).name, "visit_stamp")
    }

    func testCreateCourseCarriesOriginAndSize() {
        let params = AppEvent.createCourse(origin: "review", dayCount: 1, placeCount: 5).parameters
        XCTAssertEqual(params["course_origin"], "review")
        XCTAssertNil(params["origin"], "Firebase 가 쓰는 이름과 겹치면 안 된다")
        XCTAssertEqual(params["day_count"], 1)
        XCTAssertEqual(params["place_count"], 5)
    }

    /// 사람을 가리키거나 위치를 드러내는 값은 어떤 이벤트에도 없다. 검색어 원문도 없다.
    func testNoPersonalDataInParameters() {
        let forbidden = ["email", "name", "latitude", "longitude", "lat", "lng", "term", "query", "body", "title"]
        for event in all {
            for key in event.parameters.keys {
                XCTAssertFalse(forbidden.contains(key), "\(event.name).\(key)")
            }
            for value in event.parameters.values {
                XCTAssertTrue(value is String || value is Int || value is Int64, "\(event.name) \(value)")
            }
        }
        XCTAssertNil(AppEvent.search(termLength: 6, kind: "content").parameters["search_term"])
    }

    /// 화면 코드가 부른 것이 그대로 수집기에 닿는다.
    func testLogReachesTheSink() {
        final class Recorder: AnalyticsSink {
            var events: [AppEvent] = []
            var properties: [String: String] = [:]
            func log(_ event: AppEvent) {
                events.append(event)
            }

            func setUserProperty(_ value: String?, for name: String) {
                properties[name] = value
            }
        }
        let before = AppAnalytics.sink
        defer { AppAnalytics.sink = before }
        let recorder = Recorder()
        AppAnalytics.sink = recorder

        AppAnalytics.log(.savePlace(placeId: 38))
        AppAnalytics.setLanguage("en")
        AppAnalytics.setMember(true)

        XCTAssertEqual(recorder.events, [.savePlace(placeId: 38)])
        XCTAssertEqual(recorder.properties, ["app_language": "en", "account": "member"])
    }
}
