import SceneApiClient
@testable import SceneTrip
import XCTest

/// 시험에 쓰는 부산 촬영지 — 실제 좌표라 「가장 가까운 곳」 이 헷갈리지 않는다.
///
/// 하버사인 거리(km)로 잰 이음 순서(해운대에서 시작): 해운대 → 광안리(3.8) → 부산역(8.1) → 자갈치(2.3) → 감천(1.8) → 태종대(8.6) → 용궁사(19.6).
/// 동백섬은 해운대 바로 옆(약 0.8km)이다.
enum BusanSpots {
    static let haeundae = spot(1, "해운대", 35.1587, 129.1604, "부산광역시 해운대구 우동")
    static let gamcheon = spot(2, "감천문화마을", 35.0975, 129.0106, "부산광역시 사하구 감내2로")
    static let gwangalli = spot(3, "광안리", 35.1532, 129.1186, "부산광역시 수영구 광안해변로")
    static let taejongdae = spot(4, "태종대", 35.0518, 129.0874, "부산광역시 영도구 전망로")
    static let busanStation = spot(5, "부산역", 35.1151, 129.0422, "부산광역시 동구 중앙대로")
    static let jagalchi = spot(6, "자갈치시장", 35.0966, 129.0306, "부산광역시 중구 자갈치해안로")
    static let yonggungsa = spot(7, "해동용궁사", 35.1884, 129.2233, "부산광역시 기장군 기장읍")
    static let dongbaek = spot(8, "동백섬", 35.1525, 129.1520, "부산광역시 해운대구 우동")

    /// 인기순(서버 `?city=` 순서)이라고 치는 목록.
    static let popular = [haeundae, gamcheon, gwangalli, taejongdae, busanStation, jagalchi, yonggungsa]

    static func spot(_ id: Int64, _ name: String, _ lat: Double, _ lng: Double, _ address: String? = nil) -> PlaceSummary {
        PlaceSummary(id: id, name: name, address: address, latitude: lat, longitude: lng)
    }

    static func ids(_ places: [PlaceSummary]) -> [Int64] {
        places.map(\.id)
    }

    static func ids(_ plan: [[PlaceSummary]]) -> [[Int64]] {
        plan.map { $0.map(\.id) }
    }
}

/// 추천 코스(R1–R5) — 앱 안 계산 (MZ2AZ-547 P3, 웹 `mockPlan.ts`·`protoState.ts`·`protoParts.tsx`).
final class CityPlanRulesTests: XCTestCase {
    private typealias B = BusanSpots

    // MARK: 하루 몇 곳

    func testStopsPerDayByPace() {
        XCTAssertEqual(CityPlanRules.stopsPerDay(.relaxed), 3)
        XCTAssertEqual(CityPlanRules.stopsPerDay(.normal), 5)
        XCTAssertEqual(CityPlanRules.stopsPerDay(.packed), 7)
    }

    // MARK: 가까운 곳 잇기

    func testChainOfOneIsItselfAndOfNothingIsEmpty() {
        XCTAssertEqual(B.ids(CityPlanRules.chain([B.gamcheon])), [B.gamcheon.id])
        XCTAssertEqual(CityPlanRules.chain([]), [])
    }

    /// 첫 곳은 목록 맨 앞(가장 인기)이고, 그다음부터는 바로 앞 곳에서 가장 가까운 곳.
    func testChainStartsAtMostPopularAndFollowsNearest() {
        XCTAssertEqual(B.ids(CityPlanRules.chain(B.popular)), B.ids([
            B.haeundae, B.gwangalli, B.busanStation, B.jagalchi, B.gamcheon, B.taejongdae, B.yonggungsa,
        ]))
    }

    /// 첫 곳은 거리로 고르지 않는다 — 목록 맨 앞이 외딴 곳이어도 거기서 시작한다.
    func testChainKeepsFirstEvenIfRemote() {
        let chained = CityPlanRules.chain([B.yonggungsa, B.jagalchi, B.haeundae])
        XCTAssertEqual(B.ids(chained), B.ids([B.yonggungsa, B.haeundae, B.jagalchi]))
    }

    // MARK: 계획

    /// 널널하게 이틀 — 하루 3곳 × 2일 = 6곳을 이은 순서대로 3곳씩.
    func testPlanSlicesChainPerDay() {
        let plan = CityPlanRules.plan(places: B.popular, days: 2, pace: .relaxed)
        XCTAssertEqual(B.ids(plan), B.ids([
            [B.haeundae, B.gwangalli, B.busanStation],
            [B.jagalchi, B.gamcheon, B.taejongdae],
        ]))
    }

    /// 후보는 인기순 앞에서 `want * 2` 개만 — 일곱째인 동백섬은 해운대 바로 옆이어도 후보가 아니다(당일·널널 → want 3, 후보 6).
    func testPlanOnlyChainsFirstTwiceWantCandidates() {
        let places = Array(B.popular.prefix(6)) + [B.dongbaek]
        let plan = CityPlanRules.plan(places: places, days: 1, pace: .relaxed)
        XCTAssertEqual(B.ids(plan), [B.ids([B.haeundae, B.gwangalli, B.busanStation])])
    }

    /// 후보 안에 있으면 가까운 곳이 먼저 붙는다(같은 동백섬, 하루 5곳 → 후보 10).
    func testPlanUsesNearCandidateWhenWithinLimit() {
        let places = Array(B.popular.prefix(6)) + [B.dongbaek]
        let plan = CityPlanRules.plan(places: places, days: 1, pace: .normal)
        XCTAssertEqual(plan.count, 1)
        let first = plan.first ?? []
        XCTAssertEqual(Array(B.ids(first).prefix(3)), [B.haeundae.id, B.dongbaek.id, B.gwangalli.id])
        XCTAssertEqual(first.count, 5)
    }

    func testPlanPackedTakesSevenADay() {
        let plan = CityPlanRules.plan(places: B.popular, days: 1, pace: .packed)
        XCTAssertEqual(plan.map(\.count), [7])
    }

    /// 촬영지가 모자라면 뒷날이 덜 차고, 빈 날은 뺀다.
    func testPlanDropsEmptyDaysWhenPlacesRunOut() {
        let places = [B.haeundae, B.gamcheon, B.gwangalli, B.busanStation]
        let plan = CityPlanRules.plan(places: places, days: 3, pace: .relaxed)
        XCTAssertEqual(B.ids(plan), B.ids([
            [B.haeundae, B.gwangalli, B.busanStation],
            [B.gamcheon],
        ]))
        XCTAssertEqual(CityPlanRules.plan(places: [], days: 2, pace: .normal).count, 0)
    }

    // MARK: 다른 곳으로 바꾸기

    func testNearestUnusedSkipsUsed() {
        let next = CityPlanRules.nearestUnused(to: B.jagalchi, among: B.popular, used: [B.jagalchi.id, B.gamcheon.id])
        XCTAssertEqual(next?.id, B.busanStation.id)
        // 모두 쓰였으면 없다.
        XCTAssertNil(CityPlanRules.nearestUnused(to: B.haeundae, among: B.popular, used: Set(B.ids(B.popular))))
    }

    func testSwappedReplacesWithNearestUnused() {
        let plan = [[B.haeundae, B.gwangalli], [B.jagalchi]]
        let next = CityPlanRules.swapped(plan, day: 1, index: 0, among: B.popular)
        XCTAssertEqual(B.ids(next), B.ids([[B.haeundae, B.gwangalli], [B.gamcheon]]))
    }

    /// 다른 날에 들어 있는 곳도 「이미 코스에 있는 곳」 이다 — 해운대 옆 광안리(둘째 날)를 건너뛰고 용궁사.
    func testSwappedTreatsOtherDaysAsUsed() {
        let plan = [[B.haeundae], [B.gwangalli]]
        let next = CityPlanRules.swapped(plan, day: 0, index: 0, among: B.popular)
        XCTAssertEqual(B.ids(next), B.ids([[B.yonggungsa], [B.gwangalli]]))
    }

    func testSwappedUnchangedWhenNothingLeft() {
        let plan = [[B.haeundae, B.gwangalli]]
        let next = CityPlanRules.swapped(plan, day: 0, index: 1, among: [B.haeundae, B.gwangalli])
        XCTAssertEqual(B.ids(next), B.ids(plan))
    }

    // MARK: 빼기·옮기기

    func testRemovedTakesOutOneStop() {
        let plan = [[B.haeundae, B.gwangalli, B.busanStation], [B.jagalchi]]
        XCTAssertEqual(B.ids(CityPlanRules.removed(plan, day: 0, index: 1)), B.ids([[B.haeundae, B.busanStation], [B.jagalchi]]))
    }

    /// 그날이 비어도 날은 남긴다.
    func testRemovedKeepsEmptyDay() {
        let plan = [[B.haeundae, B.gwangalli], [B.jagalchi]]
        XCTAssertEqual(B.ids(CityPlanRules.removed(plan, day: 1, index: 0)), [B.ids([B.haeundae, B.gwangalli]), []])
    }

    /// 꾹 끌기는 이웃과 한 칸씩 바꾼다.
    func testMovedSwapsNeighbours() {
        let plan = [[B.haeundae, B.gwangalli, B.busanStation], [B.jagalchi, B.gamcheon]]
        XCTAssertEqual(B.ids(CityPlanRules.moved(plan, day: 0, from: 0, to: 1)), B.ids([
            [B.gwangalli, B.haeundae, B.busanStation], [B.jagalchi, B.gamcheon],
        ]))
        XCTAssertEqual(B.ids(CityPlanRules.moved(plan, day: 1, from: 1, to: 0)), B.ids([
            [B.haeundae, B.gwangalli, B.busanStation], [B.gamcheon, B.jagalchi],
        ]))
    }

    /// 멀리 옮기기 — 웹 `moveTo` 처럼 `from` 에서 빼서 `to` 자리에 끼운다.
    func testMovedTakesOutAndInsertsAtTo() {
        let plan = [[B.haeundae, B.gwangalli, B.busanStation]]
        XCTAssertEqual(B.ids(CityPlanRules.moved(plan, day: 0, from: 0, to: 2)), [B.ids([B.gwangalli, B.busanStation, B.haeundae])])
        XCTAssertEqual(B.ids(CityPlanRules.moved(plan, day: 0, from: 2, to: 0)), [B.ids([B.busanStation, B.haeundae, B.gwangalli])])
    }

    // MARK: 직접 고르기 — 며칠로 나누기 (웹 `splitDays`)

    func testSplitDaysCeilChunks() {
        XCTAssertEqual(CityPlanRules.splitDays([1, 2, 3, 4, 5], days: 2), [[1, 2, 3], [4, 5]])
        XCTAssertEqual(CityPlanRules.splitDays([1, 2, 3, 4, 5, 6], days: 3), [[1, 2], [3, 4], [5, 6]])
    }

    /// 하루 `ceil(n / days)` 곳씩이라 뒷날이 빌 수 있다 — 고르게 나누기(`splitEven`)와 다르다.
    func testSplitDaysCanLeaveTrailingDaysEmpty() {
        XCTAssertEqual(CityPlanRules.splitDays([1, 2, 3, 4], days: 3), [[1, 2], [3, 4], []])
        XCTAssertEqual(CityPlanRules.splitDays([1], days: 3), [[1], [], []])
    }

    func testSplitDaysAlwaysHasDaysLength() {
        XCTAssertEqual(CityPlanRules.splitDays([Int](), days: 2), [[], []])
    }

    // MARK: 저장할 코스

    func testRoutePaceOnlyPackedIsTight() {
        XCTAssertEqual(CityPlanRules.routePace(.packed), .tight)
        XCTAssertEqual(CityPlanRules.routePace(.normal), .loose)
        XCTAssertEqual(CityPlanRules.routePace(.relaxed), .loose)
    }

    func testCourseHasDaysAndStopsInOrder() {
        let plan = [[B.haeundae, B.gwangalli], [B.jagalchi]]
        let course = CityPlanRules.course(title: "부산 1박 2일", plan: plan, pace: .normal, madeByAI: true)
        XCTAssertEqual(course.title, "부산 1박 2일")
        XCTAssertEqual(course.days.map { $0.stops.map(\.place.id) }, B.ids(plan))
        XCTAssertEqual(course.pace, .loose)
        XCTAssertTrue(course.madeByAI)
        XCTAssertNil(course.serverId)
    }

    func testCoursePackedNotByAI() {
        let course = CityPlanRules.course(title: "부산 여행", plan: [[B.haeundae]], pace: .packed, madeByAI: false)
        XCTAssertEqual(course.title, "부산 여행")
        XCTAssertEqual(course.pace, .tight)
        XCTAssertFalse(course.madeByAI)
        XCTAssertEqual(course.days.count, 1)
    }
}

/// 꾹 눌러 끌어 순서 바꾸기 (R4, 웹 `useHoldReorder.ts`).
final class HoldReorderRulesTests: XCTestCase {
    func testHoldAndSlop() {
        XCTAssertEqual(HoldReorderRules.holdDuration, 0.3, accuracy: 0.0001)
        XCTAssertEqual(HoldReorderRules.slop, 8, accuracy: 0.0001)
    }

    func testDownPastHalfOfBelowSwapsAndSubtractsHeight() {
        XCTAssertEqual(HoldReorderRules.step(index: 0, count: 3, offset: 60, below: 100, above: nil), HoldReorderStep(to: 1, offset: -40))
    }

    func testDownExactlyHalfStays() {
        XCTAssertEqual(HoldReorderRules.step(index: 0, count: 3, offset: 50, below: 100, above: nil), HoldReorderStep(to: 0, offset: 50))
    }

    func testUpPastHalfOfAboveSwapsAndAddsHeight() {
        XCTAssertEqual(HoldReorderRules.step(index: 2, count: 3, offset: -70, below: nil, above: 120), HoldReorderStep(to: 1, offset: 50))
    }

    func testUpNotPastHalfStays() {
        XCTAssertEqual(HoldReorderRules.step(index: 2, count: 3, offset: -50, below: nil, above: 120), HoldReorderStep(to: 2, offset: -50))
    }

    /// 맨 끝 카드는 더 내려가지 않고, 맨 앞 카드는 더 올라가지 않는다.
    func testEdgesDoNotMove() {
        XCTAssertEqual(HoldReorderRules.step(index: 2, count: 3, offset: 200, below: 100, above: 100), HoldReorderStep(to: 2, offset: 200))
        XCTAssertEqual(HoldReorderRules.step(index: 0, count: 3, offset: -200, below: 100, above: 100), HoldReorderStep(to: 0, offset: -200))
    }

    /// 이웃 높이를 모르면(nil 또는 0) 바꾸지 않는다.
    func testUnknownNeighbourHeightStays() {
        XCTAssertEqual(HoldReorderRules.step(index: 1, count: 3, offset: 80, below: nil, above: 100), HoldReorderStep(to: 1, offset: 80))
        XCTAssertEqual(HoldReorderRules.step(index: 1, count: 3, offset: 80, below: 0, above: 100), HoldReorderStep(to: 1, offset: 80))
    }

    /// 아래로 끄는 중에는 위 이웃을 보지 않는다.
    func testDirectionPicksNeighbour() {
        XCTAssertEqual(HoldReorderRules.step(index: 1, count: 3, offset: 30, below: 200, above: 40), HoldReorderStep(to: 1, offset: 30))
    }
}

/// 작품으로(A2·A3) — 미리 고르기·고르기 한도·짜는 중 세 줄 (웹 `ProtoPlanFlows.tsx`).
final class WorkPickRulesTests: XCTestCase {
    private func work(_ id: Int64) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품\(id)", placeCount: 3)
    }

    func testMaxWorksIsFive() {
        XCTAssertEqual(WorkPickRules.maxWorks, 5)
    }

    func testPreselectLikedWorksOnly() {
        let works = [1, 2, 3, 4].map { work($0) }
        XCTAssertEqual(WorkPickRules.preselect(works: works, liked: [2, 4, 99]), [2, 4])
        XCTAssertEqual(WorkPickRules.preselect(works: works, liked: []), [])
    }

    /// 찜이 다섯보다 많으면 작품 목록 순서로 앞의 다섯.
    func testPreselectTakesFirstFiveInWorksOrder() {
        let works = [10, 9, 8, 7, 6, 5, 4].map { work($0) }
        XCTAssertEqual(WorkPickRules.preselect(works: works, liked: Set(4 ... 10)), [10, 9, 8, 7, 6])
    }

    func testToggleAddsAndRemoves() {
        XCTAssertEqual(WorkPickRules.toggle([1], 2), WorkToggle(selected: [1, 2], tooMany: false))
        XCTAssertEqual(WorkPickRules.toggle([1, 2], 1), WorkToggle(selected: [2], tooMany: false))
    }

    func testToggleAtLimitWarnsAndKeeps() {
        let five: Set<Int64> = [1, 2, 3, 4, 5]
        XCTAssertEqual(WorkPickRules.toggle(five, 6), WorkToggle(selected: five, tooMany: true))
    }

    /// 다섯을 고른 상태에서도 빼기는 된다.
    func testToggleAtLimitStillRemoves() {
        XCTAssertEqual(WorkPickRules.toggle([1, 2, 3, 4, 5], 3), WorkToggle(selected: [1, 2, 4, 5], tooMany: false))
    }

    func testPlanningStageStarts() {
        XCTAssertEqual(PlanningStages.starts, [0, 3.5, 8])
    }

    func testPlanningStageCurrent() {
        XCTAssertEqual(PlanningStages.current(elapsed: 0), 0)
        XCTAssertEqual(PlanningStages.current(elapsed: 3.4), 0)
        XCTAssertEqual(PlanningStages.current(elapsed: 3.5), 1)
        XCTAssertEqual(PlanningStages.current(elapsed: 7.9), 1)
        XCTAssertEqual(PlanningStages.current(elapsed: 8), 2)
        XCTAssertEqual(PlanningStages.current(elapsed: 120), 2)
    }
}

/// 정한 여행(Q4) — 기기 기억과 서버 `/me/trip` 맞추기 (웹 `reconcileTrip`).
final class TripMemoryRulesTests: XCTestCase {
    private let local = TripMemo(city: "부산", days: 2, pace: .normal, courseId: nil)
    private let server = TripMemo(city: "경주", days: 1, pace: .packed, courseId: 42)

    func testServerWins() {
        XCTAssertEqual(TripMemoryRules.reconcile(local: local, server: .found(server)), TripReconcile(use: server, push: false))
        XCTAssertEqual(TripMemoryRules.reconcile(local: nil, server: .found(server)), TripReconcile(use: server, push: false))
    }

    func testMissingOnServerPushesLocal() {
        XCTAssertEqual(TripMemoryRules.reconcile(local: local, server: .missing), TripReconcile(use: local, push: true))
    }

    func testMissingEverywhereDoesNothing() {
        XCTAssertEqual(TripMemoryRules.reconcile(local: nil, server: .missing), TripReconcile(use: nil, push: false))
    }

    /// 서버에 묻지 못했으면 기기 것 그대로, 올리지 않는다.
    func testFailedKeepsLocalWithoutPush() {
        XCTAssertEqual(TripMemoryRules.reconcile(local: local, server: .failed), TripReconcile(use: local, push: false))
        XCTAssertEqual(TripMemoryRules.reconcile(local: nil, server: .failed), TripReconcile(use: nil, push: false))
    }
}

/// 새 코스 만들기 문구 (한국어, 웹 `daysLabel`·`ProtoScreens.tsx`·`ProtoFork.tsx`).
final class NewCourseTextTests: XCTestCase {
    private var savedLanguage = AppLanguage.current

    override func setUp() {
        savedLanguage = AppLanguage.current
        AppLanguage.current = .ko
    }

    override func tearDown() {
        AppLanguage.current = savedLanguage
    }

    func testDaysLabel() {
        XCTAssertEqual(NewCourseText.days(1), "당일치기")
        XCTAssertEqual(NewCourseText.days(2), "1박 2일")
        XCTAssertEqual(NewCourseText.days(3), "2박 3일")
        XCTAssertEqual(NewCourseText.days(4), "3박 4일")
    }

    func testTitles() {
        XCTAssertEqual(NewCourseText.planTitle(city: "부산", days: 2), "부산 1박 2일 추천 코스")
        XCTAssertEqual(NewCourseText.planTitle(city: "경주", days: 1), "경주 당일치기 추천 코스")
        XCTAssertEqual(NewCourseText.courseTitle(city: "부산", days: 2), "부산 1박 2일")
        XCTAssertEqual(NewCourseText.courseTitle(city: "경주", days: 1), "경주 당일치기")
        XCTAssertEqual(NewCourseText.pickedTitle(city: "부산"), "부산 여행")
    }

    func testCreatedBanner() {
        XCTAssertEqual(
            NewCourseText.createdBanner(city: "부산", days: 2),
            "부산 1박 2일 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요"
        )
        XCTAssertEqual(NewCourseText.bannerSeconds, 6, accuracy: 0.0001)
    }

    func testSavedCaption() {
        XCTAssertEqual(NewCourseText.savedCaption(count: 3), "담아 둔 3곳을 날마다 나눠 넣어요")
        XCTAssertEqual(NewCourseText.savedCaption(count: 0), "빈 코스에서 하나씩 넣어요")
    }
}
