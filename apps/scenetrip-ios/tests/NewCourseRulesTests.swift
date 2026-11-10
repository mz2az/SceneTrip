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

    /// 누를 때 받은 가까운 곳(거리순) 가운데 코스에 아직 없는 첫 곳으로, 그 카드 하나만 바꾼다.
    func testSwappedWithNearbyPicksFirstUnusedAndReplacesOnlyThatCard() {
        let plan = [[B.haeundae, B.gwangalli], [B.jagalchi, B.taejongdae]]
        // 자갈치 자리에서 가까운 순 — 자갈치 자신, 감천, 부산역.
        let nearby = [B.jagalchi, B.gamcheon, B.busanStation]
        let next = CityPlanRules.swapped(plan, day: 1, index: 0, among: nearby)
        XCTAssertEqual(B.ids(next), B.ids([[B.haeundae, B.gwangalli], [B.gamcheon, B.taejongdae]]))
    }

    /// 다른 날에 들어 있는 곳도 「이미 코스에 있는 곳」 이다 — 해운대 자리에서 가까운 순 가운데
    /// 동백섬(첫째 날)·광안리(둘째 날)를 건너뛰고 용궁사.
    func testSwappedWithNearbySkipsPlacesUsedOnOtherDays() {
        let plan = [[B.haeundae, B.dongbaek], [B.gwangalli]]
        let nearby = [B.haeundae, B.dongbaek, B.gwangalli, B.yonggungsa]
        XCTAssertEqual(
            B.ids(CityPlanRules.swapped(plan, day: 0, index: 0, among: nearby)),
            B.ids([[B.yonggungsa, B.dongbaek], [B.gwangalli]])
        )
    }

    /// 「첫 곳」 은 받은 목록의 차례다 — 앱이 거리를 다시 재서 고르지 않는다(서버가 이미 거리순으로 준다).
    func testSwappedWithNearbyTakesListOrderNotRecomputedDistance() {
        let plan = [[B.jagalchi]]
        // 감천이 자갈치에서 더 가깝지만 목록에서는 해운대가 먼저다.
        let nearby = [B.jagalchi, B.haeundae, B.gamcheon]
        XCTAssertEqual(B.ids(CityPlanRules.swapped(plan, day: 0, index: 0, among: nearby)), [B.ids([B.haeundae])])
    }

    /// 가까운 곳이 모두 코스에 있으면(또는 하나도 못 받았으면) 그대로.
    func testSwappedWithNearbyUnchangedWhenAllUsed() {
        let plan = [[B.haeundae, B.gwangalli], [B.dongbaek]]
        let nearby = [B.haeundae, B.dongbaek, B.gwangalli]
        XCTAssertEqual(B.ids(CityPlanRules.swapped(plan, day: 0, index: 1, among: nearby)), B.ids(plan))
        XCTAssertEqual(B.ids(CityPlanRules.swapped(plan, day: 0, index: 1, among: [])), B.ids(plan))
    }

    // MARK: 빼기

    func testRemovedTakesOutOneStop() {
        let plan = [[B.haeundae, B.gwangalli, B.busanStation], [B.jagalchi]]
        XCTAssertEqual(B.ids(CityPlanRules.removed(plan, day: 0, index: 1)), B.ids([[B.haeundae, B.busanStation], [B.jagalchi]]))
    }

    /// 그날이 비어도 날은 남긴다.
    func testRemovedKeepsEmptyDay() {
        let plan = [[B.haeundae, B.gwangalli], [B.jagalchi]]
        XCTAssertEqual(B.ids(CityPlanRules.removed(plan, day: 1, index: 0)), [B.ids([B.haeundae, B.gwangalli]), []])
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

/// 도시로 AI 일정 받기 (MZ2AZ-548, 계획 city-ai-course.md §5) — 요청 만들기와 받은 일정을 카드로 바꾸기.
final class CityPlanAIRulesTests: XCTestCase {
    private typealias B = BusanSpots

    private static func stop(_ order: Int, _ placeId: Int64?, name: String = "곳") -> GuidePlanStop {
        GuidePlanStop(order: order, placeId: placeId, name: name, arriveMinute: 600 + order * 60)
    }

    private static func day(_ number: Int, _ stops: [GuidePlanStop]) -> GuidePlanDay {
        GuidePlanDay(day: number, stops: stops)
    }

    // MARK: 요청

    /// 도시 key·일수·속도를 싣고, 작품(`titles`)은 보내지 않는다.
    func testAIRequestCarriesCityDaysPaceAndNoTitles() {
        let request = CityPlanRules.aiRequest(city: "부산", days: 2, pace: .normal)
        XCTAssertEqual(request.city, "부산")
        XCTAssertEqual(request.days, 2)
        XCTAssertEqual(request.pace, .normal)
        XCTAssertNil(request.titles)
    }

    /// 도시 key 는 그대로 — 「경기 광주」 처럼 띄어 쓴 key 도 손대지 않는다.
    func testAIRequestKeepsCityKeyAsIs() {
        let request = CityPlanRules.aiRequest(city: "경기 광주", days: 1, pace: .normal)
        XCTAssertEqual(request.city, "경기 광주")
        XCTAssertEqual(request.days, 1)
    }

    /// 앱 속도는 같은 이름의 요청 속도로 — relaxed·normal·packed.
    func testAIRequestMapsEachPace() {
        XCTAssertEqual(CityPlanRules.aiRequest(city: "부산", days: 3, pace: .relaxed).pace, .relaxed)
        XCTAssertEqual(CityPlanRules.aiRequest(city: "부산", days: 3, pace: .normal).pace, .normal)
        XCTAssertEqual(CityPlanRules.aiRequest(city: "부산", days: 3, pace: .packed).pace, .packed)
    }

    /// 좌표·꼭 넣을 곳·뺄 곳은 싣지 않는다 — 도시 질문은 그런 걸 묻지 않는다.
    func testAIRequestSendsNothingElse() {
        let request = CityPlanRules.aiRequest(city: "부산", days: 2, pace: .normal)
        XCTAssertNil(request.latitude)
        XCTAssertNil(request.longitude)
        XCTAssertNil(request.must)
        XCTAssertNil(request.avoid)
    }

    // MARK: 받은 일정 → 카드

    /// 서버가 준 정지점 하나 — 좌표·주소·사진·작품·장면 설명까지 정지점에 실려 온다(계약 덧붙임 1.28.0).
    private static func full(
        _ order: Int,
        _ place: PlaceSummary,
        imageUrl: String? = nil,
        contents: [ContentRef]? = nil,
        scene: String? = nil
    ) -> GuidePlanStop {
        GuidePlanStop(
            order: order, placeId: place.id, name: place.name, address: place.address,
            latitude: place.latitude, longitude: place.longitude, arriveMinute: 600 + order * 60,
            sceneDescription: scene, imageUrl: imageUrl, contents: contents
        )
    }

    /// 카드는 정지점만으로 만든다 — 번호는 `placeId`, 이름·주소·좌표·사진·장면 설명은 정지점 그대로.
    func testFromGuideBuildsCardFromStopAlone() {
        let stop = GuidePlanStop(
            order: 1, placeId: 501, name: "흰여울문화마을", address: "부산광역시 영도구 영선동4가",
            latitude: 35.0781, longitude: 129.0453, arriveMinute: 600,
            sceneDescription: "바다를 보며 걷던 골목", imageUrl: "https://img.example/501.jpg",
            contents: [ContentRef(contentId: 77, title: "변호인")]
        )
        let out = CityPlanRules.fromGuide(GuidePlan(days: [Self.day(1, [stop])]))
        XCTAssertEqual(out.count, 1)
        let card = out.first?.first
        XCTAssertEqual(card?.id, 501)
        XCTAssertEqual(card?.name, "흰여울문화마을")
        XCTAssertEqual(card?.address, "부산광역시 영도구 영선동4가")
        XCTAssertEqual(card?.latitude, 35.0781)
        XCTAssertEqual(card?.longitude, 129.0453)
        XCTAssertEqual(card?.imageUrl, "https://img.example/501.jpg")
        XCTAssertEqual(card?.sceneDescription, "바다를 보며 걷던 골목")
        XCTAssertEqual(card?.contents?.map(\.contentId), [77])
        XCTAssertEqual(card?.contents?.map(\.title), ["변호인"])
    }

    /// 작품은 서버가 준 차례 그대로 싣는다.
    func testFromGuideKeepsContentsOrder() {
        let contents = [
            ContentRef(contentId: 3, title: "해운대"),
            ContentRef(contentId: 1, title: "친구"),
            ContentRef(contentId: 2, title: "국제시장"),
        ]
        let out = CityPlanRules.fromGuide(GuidePlan(days: [Self.day(1, [Self.full(1, B.haeundae, contents: contents)])]))
        XCTAssertEqual(out.first?.first?.contents?.map(\.contentId), [3, 1, 2])
        XCTAssertEqual(out.first?.first?.contents?.map(\.title), ["해운대", "친구", "국제시장"])
    }

    /// 사진·작품·장면 설명이 없으면 카드에도 없다.
    func testFromGuideLeavesMissingExtrasEmpty() {
        let out = CityPlanRules.fromGuide(GuidePlan(days: [Self.day(1, [Self.full(1, B.gamcheon)])]))
        let card = out.first?.first
        XCTAssertEqual(card?.id, B.gamcheon.id)
        XCTAssertNil(card?.imageUrl)
        XCTAssertNil(card?.sceneDescription)
        XCTAssertTrue(card?.contents?.isEmpty ?? true)
    }

    /// 도시 촬영지 목록 없이도 그린다 — 처음 보는 번호도 정지점 이름·좌표로 카드가 된다.
    func testFromGuideNeedsNoCityPlaceList() {
        let stop = GuidePlanStop(order: 1, placeId: 9999, name: "처음 보는 곳", latitude: 35.1, longitude: 129.0, arriveMinute: 600)
        let out = CityPlanRules.fromGuide(GuidePlan(days: [Self.day(1, [stop])]))
        XCTAssertEqual(out.first?.first?.id, 9999)
        XCTAssertEqual(out.first?.first?.name, "처음 보는 곳")
    }

    /// 정지점은 받은 차례가 아니라 `order` 순으로.
    func testFromGuideOrdersStopsByOrder() {
        let plan = GuidePlan(days: [
            Self.day(1, [
                Self.full(3, B.jagalchi),
                Self.full(1, B.haeundae),
                Self.full(2, B.gwangalli),
            ]),
        ])
        XCTAssertEqual(B.ids(CityPlanRules.fromGuide(plan)), B.ids([
            [B.haeundae, B.gwangalli, B.jagalchi],
        ]))
    }

    /// 번호(`placeId`)가 없는 곳(편의시설 등)은 뺀다.
    func testFromGuideDropsStopsWithoutPlaceId() {
        let restaurant = GuidePlanStop(order: 2, poiId: 321, name: "식당", latitude: 35.15, longitude: 129.15, arriveMinute: 660)
        let plan = GuidePlan(days: [
            Self.day(1, [Self.full(1, B.haeundae), restaurant, Self.full(3, B.busanStation)]),
        ])
        XCTAssertEqual(B.ids(CityPlanRules.fromGuide(plan)), B.ids([
            [B.haeundae, B.busanStation],
        ]))
    }

    /// 좌표가 없는 곳(위도나 경도 하나라도)은 뺀다 — 지도에 놓을 수 없다.
    func testFromGuideDropsStopsWithoutCoordinates() {
        let noLat = GuidePlanStop(order: 2, placeId: 601, name: "위도 없음", longitude: 129.0, arriveMinute: 660)
        let noLng = GuidePlanStop(order: 3, placeId: 602, name: "경도 없음", latitude: 35.1, arriveMinute: 720)
        let neither = GuidePlanStop(order: 4, placeId: 603, name: "좌표 없음", arriveMinute: 780)
        let plan = GuidePlan(days: [
            Self.day(1, [Self.full(1, B.haeundae), noLat, noLng, neither, Self.full(5, B.gamcheon)]),
        ])
        XCTAssertEqual(B.ids(CityPlanRules.fromGuide(plan)), B.ids([
            [B.haeundae, B.gamcheon],
        ]))
    }

    /// 빼고 나서 빈 날, 처음부터 빈 날은 날째 뺀다.
    func testFromGuideDropsDaysLeftEmpty() {
        let plan = GuidePlan(days: [
            Self.day(1, [Self.full(1, B.haeundae)]),
            Self.day(2, [Self.stop(1, nil), GuidePlanStop(order: 2, placeId: 700, name: "좌표 없음", arriveMinute: 660)]),
            Self.day(3, []),
            Self.day(4, [Self.full(1, B.gamcheon)]),
        ])
        XCTAssertEqual(B.ids(CityPlanRules.fromGuide(plan)), B.ids([
            [B.haeundae],
            [B.gamcheon],
        ]))
    }

    /// 쓸 수 있는 곳이 하나도 없으면 빈 배열 — 화면은 「코스를 못 짰어요」 를 띄운다.
    func testFromGuideEmptyWhenNothingUsable() {
        let plan = GuidePlan(days: [
            Self.day(1, [Self.stop(1, nil), GuidePlanStop(order: 2, placeId: 800, name: "좌표 없음", arriveMinute: 660)]),
            Self.day(2, []),
        ])
        XCTAssertEqual(CityPlanRules.fromGuide(plan).count, 0)
        XCTAssertEqual(CityPlanRules.fromGuide(GuidePlan(days: [])).count, 0)
    }

    /// 여러 날은 날 차례 그대로, 날마다 `order` 순으로.
    func testFromGuideKeepsMultipleDays() {
        let plan = GuidePlan(days: [
            Self.day(1, [Self.full(2, B.gwangalli), Self.full(1, B.haeundae)]),
            Self.day(2, [Self.full(2, B.gamcheon), Self.full(3, B.taejongdae), Self.full(1, B.jagalchi)]),
        ])
        XCTAssertEqual(B.ids(CityPlanRules.fromGuide(plan)), B.ids([
            [B.haeundae, B.gwangalli],
            [B.jagalchi, B.gamcheon, B.taejongdae],
        ]))
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
