@testable import SceneTrip
import XCTest

/// 도시로 여행 짜기 질문 Q1–Q3 의 흐름 — 도시 → 며칠 → 어떻게 → 추천 코스(R) | 직접 고르기(S)
/// (MZ2AZ-547 P3, 웹 `ProtoScreens.tsx` `QuestionFlow`).
final class NewCourseFlowTests: XCTestCase {
    func testDayOptions() {
        XCTAssertEqual(CityQuestionFlow.dayOptions, [1, 2, 3, 4])
    }

    func testStartsAtCityWithoutStartCity() {
        let flow = CityQuestionFlow(startCity: nil)
        XCTAssertEqual(flow.step, .city)
        XCTAssertNil(flow.city)
        XCTAssertNil(flow.days)
        XCTAssertEqual(flow.stepNumber, 1)
    }

    /// 홈 도시 칩으로 들어오면 「며칠?」 부터, 도시는 이미 정해져 있다.
    func testStartsAtDaysWithStartCity() {
        let flow = CityQuestionFlow(startCity: "부산")
        XCTAssertEqual(flow.step, .days)
        XCTAssertEqual(flow.city, "부산")
        XCTAssertEqual(flow.startCity, "부산")
        XCTAssertEqual(flow.stepNumber, 2)
    }

    func testForwardToPlan() {
        var flow = CityQuestionFlow(startCity: nil)
        flow.pickCity("경주")
        XCTAssertEqual(flow.step, .days)
        XCTAssertEqual(flow.city, "경주")
        XCTAssertEqual(flow.stepNumber, 2)

        flow.pickDays(3)
        XCTAssertEqual(flow.step, .how)
        XCTAssertEqual(flow.days, 3)
        XCTAssertEqual(flow.stepNumber, 3)

        flow.planForMe()
        XCTAssertEqual(flow.step, .plan)
        XCTAssertNil(flow.stepNumber)
        XCTAssertEqual(flow.city, "경주")
        XCTAssertEqual(flow.days, 3)
    }

    func testForwardToPick() {
        var flow = CityQuestionFlow(startCity: "부산")
        flow.pickDays(1)
        flow.pickMyself()
        XCTAssertEqual(flow.step, .pick)
        XCTAssertNil(flow.stepNumber)
        XCTAssertEqual(flow.days, 1)
    }

    // MARK: 뒤로

    func testBackAtCityCloses() {
        var flow = CityQuestionFlow(startCity: nil)
        XCTAssertTrue(flow.back())
    }

    func testBackAtDaysGoesToCityWithoutStartCity() {
        var flow = CityQuestionFlow(startCity: nil)
        flow.pickCity("부산")
        XCTAssertEqual(flow.step, .days)
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .city)
    }

    /// 도시 칩으로 들어왔으면 「며칠?」 의 뒤로는 닫기다.
    func testBackAtDaysClosesWithStartCity() {
        var flow = CityQuestionFlow(startCity: "부산")
        XCTAssertTrue(flow.back())
    }

    func testBackFromHowGoesToDays() {
        var flow = CityQuestionFlow(startCity: nil)
        flow.pickCity("부산")
        flow.pickDays(2)
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .days)
    }

    func testBackFromPlanGoesToHow() {
        var flow = CityQuestionFlow(startCity: nil)
        flow.pickCity("부산")
        flow.pickDays(2)
        flow.planForMe()
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .how)
    }

    func testBackFromPickGoesToHow() {
        var flow = CityQuestionFlow(startCity: "부산")
        flow.pickDays(2)
        flow.pickMyself()
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .how)
    }

    /// 도시 칩으로 들어와 끝까지 갔다가 뒤로만 누르면: 추천 → 어떻게 → 며칠 → 닫기.
    func testBackAllTheWayWithStartCity() {
        var flow = CityQuestionFlow(startCity: "부산")
        flow.pickDays(2)
        flow.planForMe()
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .how)
        XCTAssertFalse(flow.back())
        XCTAssertEqual(flow.step, .days)
        XCTAssertTrue(flow.back())
    }
}
