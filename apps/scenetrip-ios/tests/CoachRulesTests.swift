import CoreGraphics
@testable import SceneTrip
import XCTest

// 처음 보는 사람을 위한 짧은 안내(코치 마크) 규칙 (MZ2AZ-547 P6, 계획서 `docs/project/plans/ios-web-parity.md` C1–C5·M8).
//
// 웹 `proto/CoachTour.tsx`(구멍·말풍선 자리 계산, 본 것 기억, 한 번에 하나, 4초 기다림)와
// 각 화면의 안내 문구(`ProtoScreens.tsx`·`ProtoFork.tsx`·`RouteEditor.tsx`·`protoPoi.tsx`)를 명세로 삼는다.

// MARK: - 도움

/// `UserDefaults` 대신 쓰는 메모리 저장소.
private final class MemoryCoachStorage: CoachSeenStorage {
    var values: [String: Any] = [:]

    func stringArray(forKey key: String) -> [String]? {
        values[key] as? [String]
    }

    func set(_ value: Any?, forKey key: String) {
        values[key] = value
    }
}

private let iPhone = CGSize(width: 390, height: 844)

// MARK: - 상수 (C1)

final class CoachConstantsTests: XCTestCase {
    func test_웹과_같은_상수() {
        XCTAssertEqual(CoachRules.pad, 6)
        XCTAssertEqual(CoachRules.bubbleWidth, 280)
        XCTAssertEqual(CoachRules.gap, 12)
        XCTAssertEqual(CoachRules.edge, 12)
        XCTAssertEqual(CoachRules.missingTargetWait, 4, accuracy: 0.0001)
        XCTAssertEqual(CoachRules.retryInterval, 0.4, accuracy: 0.0001)
    }
}

// MARK: - 안내 목록과 문구 (C2–C5)

final class CoachTourCatalogTests: XCTestCase {
    func test_홈_여행코스짜기_안내() {
        let tour = CoachTour.homePlanTrip
        XCTAssertEqual(tour.id, "home-plan-trip")
        XCTAssertEqual(tour.delay, 0.9, accuracy: 0.0001)
        XCTAssertEqual(tour.steps, [
            CoachStep(
                target: "plan-trip",
                title: "첫 여행 계획을 짜 보세요",
                text: "여기를 누르면 도시·작품·담은 곳 중 하나로 여행 코스를 만들 수 있어요."
            ),
        ])
    }

    func test_새코스_세갈래_안내() {
        let tour = CoachTour.forkOptions
        XCTAssertEqual(tour.id, "fork-options")
        XCTAssertEqual(tour.delay, 0.55, accuracy: 0.0001)
        XCTAssertEqual(tour.steps, [
            CoachStep(
                target: "fork-city",
                title: "도시로 시작",
                text: "어디로, 며칠 가는지만 고르면 그 도시 인기 촬영지를 동선대로 이어 코스를 바로 만들어 드려요."
            ),
            CoachStep(
                target: "fork-shows",
                title: "작품으로",
                text: "좋아하는 드라마·영화를 고르면 AI 가 그 작품들의 촬영지로 코스를 짜 줘요."
            ),
            CoachStep(
                target: "fork-saved",
                title: "담은 곳으로",
                text: "보관함에 담아 둔 곳을 날마다 나눠 넣어요. 담은 곳이 없으면 빈 코스에서 하나씩 직접 넣어요."
            ),
        ])
    }

    func test_처음만든_계획화면_안내() {
        let tour = CoachTour.editorFirst
        XCTAssertEqual(tour.id, "editor-first")
        XCTAssertEqual(tour.delay, 1.2, accuracy: 0.0001)
        XCTAssertEqual(tour.steps, [
            CoachStep(
                target: "editor-map",
                title: "지도",
                text: "눌러서 코스를 지도로 펼쳐 동선을 확인해요. 다시 누르면 목록만 봐요."
            ),
            CoachStep(
                target: "editor-guide",
                title: "해태 AI",
                text: "「하루 줄여 줘」 「카페 하나 넣어 줘」처럼 말로 부탁하면 코스를 고쳐 줘요."
            ),
            CoachStep(
                target: "editor-add",
                title: "장소 검색해서 담기",
                text: "지금은 편집 중이라 장소를 검색해서 더 넣을 수 있어요. 보관함에 담아 둔 곳도 여기서 넣어요."
            ),
            CoachStep(
                target: "editor-save",
                title: "다 고쳤으면 저장",
                text: "저장해야 코스가 남아요. 저장하지 않고 나가면 한 번 더 물어봐요."
            ),
        ])
    }

    func test_지도검색_안내() {
        let tour = CoachTour.mapSearch
        XCTAssertEqual(tour.id, "map-search")
        XCTAssertEqual(tour.delay, 0.7, accuracy: 0.0001)
        XCTAssertEqual(tour.steps, [
            CoachStep(
                target: "map-search",
                title: "지도에서 검색",
                text: "촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요."
            ),
            CoachStep(
                target: "map-chips",
                title: "주변 둘러보기",
                text: "전체·음식·관광지·숙소 칩을 누르면 지금 보이는 지도 안의 장소가 핀으로 떠요."
            ),
        ])
    }

    /// 영어 화면에 한국어 안내가 섞이지 않게 — 지도 검색 첫 안내의 영어 문구.
    func test_지도검색_안내_영어() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: "en"
        ))
        let table = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
        )
        XCTAssertEqual(
            table["촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요."],
            "Find places beyond filming spots — search restaurants, cafés, stays and sights by name, then add them to your course."
        )
    }

    func test_전체목록은_네개() {
        XCTAssertEqual(CoachTour.all.map(\.id), ["home-plan-trip", "fork-options", "editor-first", "map-search"])
        XCTAssertEqual(CoachTour.all, [.homePlanTrip, .forkOptions, .editorFirst, .mapSearch])
    }
}

// MARK: - 본 것 기억 (C1)

final class CoachSeenStoreTests: XCTestCase {
    func test_처음엔_아무것도_안봤다() {
        let store = CoachSeenStore(storage: MemoryCoachStorage())
        XCTAssertFalse(store.isSeen("home-plan-trip"))
    }

    func test_본것으로_표시하면_기억하고_저장소에_문자열배열로_남는다() {
        let storage = MemoryCoachStorage()
        let store = CoachSeenStore(storage: storage)
        store.markSeen("fork-options")
        XCTAssertTrue(store.isSeen("fork-options"))
        XCTAssertEqual(CoachSeenStore.key, "scenetrip.coach.seen")
        XCTAssertEqual(storage.stringArray(forKey: "scenetrip.coach.seen"), ["fork-options"])
    }

    func test_다른_안내는_영향없다() {
        let store = CoachSeenStore(storage: MemoryCoachStorage())
        store.markSeen("fork-options")
        XCTAssertFalse(store.isSeen("map-search"))
    }

    func test_같은_안내를_두번_표시해도_한번만_남는다() {
        let storage = MemoryCoachStorage()
        let store = CoachSeenStore(storage: storage)
        store.markSeen("map-search")
        store.markSeen("map-search")
        XCTAssertEqual(storage.stringArray(forKey: CoachSeenStore.key), ["map-search"])
    }

    func test_여러개를_순서대로_기억한다() {
        let storage = MemoryCoachStorage()
        let store = CoachSeenStore(storage: storage)
        store.markSeen("home-plan-trip")
        store.markSeen("editor-first")
        XCTAssertEqual(storage.stringArray(forKey: CoachSeenStore.key), ["home-plan-trip", "editor-first"])
    }

    func test_저장소에_이미_있던_기록을_읽는다() {
        let storage = MemoryCoachStorage()
        storage.set(["editor-first"], forKey: "scenetrip.coach.seen")
        let store = CoachSeenStore(storage: storage)
        XCTAssertTrue(store.isSeen("editor-first"))
        XCTAssertFalse(store.isSeen("home-plan-trip"))
    }

    func test_새_저장소_객체도_같은_기록을_본다() {
        let storage = MemoryCoachStorage()
        CoachSeenStore(storage: storage).markSeen("home-plan-trip")
        let again = CoachSeenStore(storage: storage)
        XCTAssertTrue(again.isSeen("home-plan-trip"))
        // 표시하지 않은 안내는 여전히 안 본 것이다.
        XCTAssertFalse(again.isSeen("fork-options"))
    }

    func test_reset_은_모두_지운다() {
        let storage = MemoryCoachStorage()
        let store = CoachSeenStore(storage: storage)
        store.markSeen("home-plan-trip")
        store.markSeen("map-search")
        store.reset()
        XCTAssertFalse(store.isSeen("home-plan-trip"))
        XCTAssertFalse(store.isSeen("map-search"))
        XCTAssertEqual(storage.stringArray(forKey: CoachSeenStore.key) ?? [], [])
    }
}

// MARK: - 한 번에 하나 (C1)

final class CoachQueueTests: XCTestCase {
    func test_아무것도_없으면_띄운다() {
        var queue = CoachQueue()
        XCTAssertTrue(queue.start("home-plan-trip", seen: false))
        XCTAssertEqual(queue.active, "home-plan-trip")
    }

    func test_본_안내는_띄우지_않는다() {
        var queue = CoachQueue()
        XCTAssertFalse(queue.start("home-plan-trip", seen: true))
        XCTAssertNil(queue.active)
        // 본 안내가 자리를 차지하지 않으니 다른 안내는 바로 뜬다.
        XCTAssertTrue(queue.start("fork-options", seen: false))
        XCTAssertEqual(queue.active, "fork-options")
    }

    func test_다른_안내가_떠있으면_기다린다() {
        var queue = CoachQueue()
        XCTAssertTrue(queue.start("editor-first", seen: false))
        XCTAssertFalse(queue.start("map-search", seen: false))
        XCTAssertEqual(queue.active, "editor-first")
    }

    func test_같은_안내가_이미_떠있어도_false() {
        var queue = CoachQueue()
        XCTAssertTrue(queue.start("editor-first", seen: false))
        XCTAssertFalse(queue.start("editor-first", seen: false))
        XCTAssertEqual(queue.active, "editor-first")
    }

    func test_끝나면_다음_안내를_띄울_수_있다() {
        var queue = CoachQueue()
        XCTAssertTrue(queue.start("editor-first", seen: false))
        queue.end("editor-first")
        XCTAssertNil(queue.active)
        XCTAssertTrue(queue.start("map-search", seen: false))
        XCTAssertEqual(queue.active, "map-search")
    }

    func test_다른_안내의_끝내기는_지금_안내를_지우지_않는다() {
        var queue = CoachQueue()
        XCTAssertTrue(queue.start("editor-first", seen: false))
        queue.end("map-search")
        XCTAssertEqual(queue.active, "editor-first")
    }
}

// MARK: - 단계 넘기기 (C1)

final class CoachAdvanceTests: XCTestCase {
    func test_다음은_한칸_넘긴다() {
        XCTAssertEqual(CoachRules.advance(index: 0, count: 3, action: .next), .step(1))
        XCTAssertEqual(CoachRules.advance(index: 1, count: 3, action: .next), .step(2))
    }

    func test_마지막에서_다음은_끝나고_본것으로() {
        XCTAssertEqual(CoachRules.advance(index: 2, count: 3, action: .next), .finished(markSeen: true))
        XCTAssertEqual(CoachRules.advance(index: 0, count: 1, action: .next), .finished(markSeen: true))
    }

    func test_덮개를_누르면_다음과_같다() {
        XCTAssertEqual(CoachRules.advance(index: 0, count: 2, action: .tapScrim), .step(1))
        XCTAssertEqual(CoachRules.advance(index: 1, count: 2, action: .tapScrim), .finished(markSeen: true))
    }

    func test_단추가_없으면_그_단계를_건너뛴다() {
        XCTAssertEqual(CoachRules.advance(index: 1, count: 4, action: .targetMissing), .step(2))
        XCTAssertEqual(CoachRules.advance(index: 3, count: 4, action: .targetMissing), .finished(markSeen: true))
    }

    func test_건너뛰기는_어디서든_끝나고_본것으로() {
        XCTAssertEqual(CoachRules.advance(index: 0, count: 4, action: .skip), .finished(markSeen: true))
        XCTAssertEqual(CoachRules.advance(index: 2, count: 4, action: .skip), .finished(markSeen: true))
    }

    func test_화면을_떠나면_접되_본것으로_치지_않는다() {
        XCTAssertEqual(CoachRules.advance(index: 0, count: 3, action: .leave), .finished(markSeen: false))
        XCTAssertEqual(CoachRules.advance(index: 2, count: 3, action: .leave), .finished(markSeen: false))
    }

    func test_4초를_넘어야_건너뛴다() {
        XCTAssertFalse(CoachRules.shouldSkipMissing(waited: 0))
        XCTAssertFalse(CoachRules.shouldSkipMissing(waited: 3.9))
        XCTAssertFalse(CoachRules.shouldSkipMissing(waited: 4))
        XCTAssertTrue(CoachRules.shouldSkipMissing(waited: 4.01))
        XCTAssertTrue(CoachRules.shouldSkipMissing(waited: 10))
    }
}

// MARK: - 말풍선 글자·단추 (C1)

final class CoachBubbleTextTests: XCTestCase {
    func test_마지막_단계인가() {
        XCTAssertFalse(CoachRules.isLast(index: 0, count: 3))
        XCTAssertFalse(CoachRules.isLast(index: 1, count: 3))
        XCTAssertTrue(CoachRules.isLast(index: 2, count: 3))
        XCTAssertTrue(CoachRules.isLast(index: 0, count: 1))
    }

    func test_몇번째인지_1부터_센다() {
        XCTAssertEqual(CoachRules.counter(index: 0, count: 3), "1 / 3")
        XCTAssertEqual(CoachRules.counter(index: 3, count: 4), "4 / 4")
        XCTAssertEqual(CoachRules.counter(index: 0, count: 1), "1 / 1")
    }

    func test_건너뛰기는_마지막에만_없다() {
        XCTAssertTrue(CoachRules.showsSkip(index: 0, count: 3))
        XCTAssertTrue(CoachRules.showsSkip(index: 1, count: 3))
        XCTAssertFalse(CoachRules.showsSkip(index: 2, count: 3))
        XCTAssertFalse(CoachRules.showsSkip(index: 0, count: 1))
    }

    func test_오른쪽_단추는_마지막에_알겠어요() {
        XCTAssertEqual(CoachRules.primaryTitle(index: 0, count: 2), "다음")
        XCTAssertEqual(CoachRules.primaryTitle(index: 1, count: 2), "알겠어요")
        XCTAssertEqual(CoachRules.primaryTitle(index: 0, count: 1), "알겠어요")
    }
}

// MARK: - 구멍·말풍선 자리 (C1)

//
// 기대값은 웹 `CoachTour.tsx` 계산을 그대로 따라 손으로(파이썬으로 옮겨) 구한 값이다.
// 390×844, 위 안전영역 47·아래 34 이면 안쪽 = 위 59 · 왼 12 · 오른 378 · 아래 798.

final class CoachLayoutTests: XCTestCase {
    private func assertRect(
        _ actual: CGRect, _ expected: CGRect, file: StaticString = #filePath, line: UInt = #line
    ) {
        XCTAssertEqual(actual.minX, expected.minX, accuracy: 0.001, "x", file: file, line: line)
        XCTAssertEqual(actual.minY, expected.minY, accuracy: 0.001, "y", file: file, line: line)
        XCTAssertEqual(actual.width, expected.width, accuracy: 0.001, "width", file: file, line: line)
        XCTAssertEqual(actual.height, expected.height, accuracy: 0.001, "height", file: file, line: line)
    }

    func test_아래쪽_단추면_말풍선은_위() {
        let layout = CoachRules.layout(
            target: CGRect(x: 150, y: 740, width: 90, height: 50), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        assertRect(layout.hole, CGRect(x: 144, y: 734, width: 102, height: 62))
        XCTAssertEqual(layout.holeRadius, 22, accuracy: 0.001)
        XCTAssertFalse(layout.below)
        // 구멍 위 734 - 12 - 150 = 572, 가운데 맞춤 195 - 140 = 55.
        assertRect(layout.bubble, CGRect(x: 55, y: 572, width: 280, height: 150))
        XCTAssertEqual(layout.arrowX, 132, accuracy: 0.001)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_위쪽_단추면_말풍선은_아래() {
        let layout = CoachRules.layout(
            target: CGRect(x: 20, y: 70, width: 44, height: 44), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        assertRect(layout.hole, CGRect(x: 14, y: 64, width: 56, height: 56))
        XCTAssertTrue(layout.below)
        // 구멍 아래 120 + 12 = 132, 왼쪽 가장자리 12 로 밀려남.
        assertRect(layout.bubble, CGRect(x: 12, y: 132, width: 280, height: 150))
        XCTAssertEqual(layout.arrowX, 22, accuracy: 0.001)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_오른쪽_끝_단추면_말풍선이_안쪽으로_밀린다() {
        let layout = CoachRules.layout(
            target: CGRect(x: 340, y: 300, width: 44, height: 44), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        // 구멍 오른쪽은 화면 폭 - 4 = 386 에서 멈춘다.
        assertRect(layout.hole, CGRect(x: 334, y: 294, width: 52, height: 56))
        XCTAssertTrue(layout.below)
        // 말풍선 오른쪽 끝이 378(= 390 - 12) 에 맞는다.
        assertRect(layout.bubble, CGRect(x: 98, y: 362, width: 280, height: 150))
        XCTAssertEqual(layout.bubble.maxX, 378, accuracy: 0.001)
        // 화살표는 폭 - 34 = 246 에서 멈춘다.
        XCTAssertEqual(layout.arrowX, 246, accuracy: 0.001)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_왼쪽_끝_단추면_구멍은_4_말풍선은_12_화살표는_18() {
        let layout = CoachRules.layout(
            target: CGRect(x: 0, y: 300, width: 40, height: 40), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        assertRect(layout.hole, CGRect(x: 4, y: 294, width: 42, height: 52))
        assertRect(layout.bubble, CGRect(x: 12, y: 358, width: 280, height: 150))
        XCTAssertEqual(layout.arrowX, 18, accuracy: 0.001)
        XCTAssertTrue(layout.below)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_폭_320_화면에서도_말풍선은_280() {
        let layout = CoachRules.layout(
            target: CGRect(x: 140, y: 400, width: 40, height: 40), container: CGSize(width: 320, height: 568),
            safeTop: 20, safeBottom: 0, bubbleHeight: 120
        )
        assertRect(layout.hole, CGRect(x: 134, y: 394, width: 52, height: 52))
        // 아래 446 + 12 + 120 = 578 > 556 이라 위로: 394 - 12 - 120 = 262.
        XCTAssertFalse(layout.below)
        assertRect(layout.bubble, CGRect(x: 20, y: 262, width: 280, height: 120))
        XCTAssertEqual(layout.arrowX, 132, accuracy: 0.001)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_폭_290_화면이면_말풍선이_안쪽_폭으로_줄어든다() {
        let layout = CoachRules.layout(
            target: CGRect(x: 125, y: 400, width: 40, height: 40), container: CGSize(width: 290, height: 568),
            safeTop: 20, safeBottom: 0, bubbleHeight: 120
        )
        // 안쪽 폭 = 290 - 24 = 266.
        assertRect(layout.bubble, CGRect(x: 12, y: 262, width: 266, height: 120))
        XCTAssertFalse(layout.below)
        XCTAssertEqual(layout.arrowX, 125, accuracy: 0.001)
    }

    func test_어느쪽에도_안맞는_큰_말풍선은_위로_가두고_화살표를_숨긴다() {
        let layout = CoachRules.layout(
            target: CGRect(x: 150, y: 400, width: 90, height: 40), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 800
        )
        assertRect(layout.hole, CGRect(x: 144, y: 394, width: 102, height: 52))
        // 위로도 자리가 없으니 아래를 고르고, 안쪽 위(59)로 밀어 넣는다 — 구멍과 겹친다.
        XCTAssertTrue(layout.below)
        assertRect(layout.bubble, CGRect(x: 55, y: 59, width: 280, height: 800))
        XCTAssertFalse(layout.showsArrow)
    }

    func test_상태줄에_걸친_단추는_구멍이_안전영역_아래로_가둬진다() {
        let layout = CoachRules.layout(
            target: CGRect(x: 100, y: 40, width: 60, height: 40), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        // 구멍 위 = max(40 - 6, 59 - 6) = 53.
        assertRect(layout.hole, CGRect(x: 94, y: 53, width: 72, height: 33))
        XCTAssertEqual(layout.holeRadius, 16.5, accuracy: 0.001)
        XCTAssertTrue(layout.below)
        assertRect(layout.bubble, CGRect(x: 12, y: 98, width: 280, height: 150))
        XCTAssertEqual(layout.arrowX, 110, accuracy: 0.001)
        XCTAssertTrue(layout.showsArrow)
    }

    func test_홈막대에_걸친_단추는_구멍이_아래_안전영역에서_멈춘다() {
        let layout = CoachRules.layout(
            target: CGRect(x: 100, y: 780, width: 60, height: 40), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        // 구멍 아래 = min(820 + 6, 798 + 6) = 804.
        assertRect(layout.hole, CGRect(x: 94, y: 774, width: 72, height: 30))
        XCTAssertEqual(layout.holeRadius, 15, accuracy: 0.001)
        XCTAssertFalse(layout.below)
        assertRect(layout.bubble, CGRect(x: 12, y: 612, width: 280, height: 150))
        XCTAssertTrue(layout.showsArrow)
    }

    func test_구멍_모서리는_높이의_반_최대_22() {
        let small = CoachRules.layout(
            target: CGRect(x: 100, y: 300, width: 60, height: 20), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        XCTAssertEqual(small.hole.height, 32, accuracy: 0.001)
        XCTAssertEqual(small.holeRadius, 16, accuracy: 0.001)

        let big = CoachRules.layout(
            target: CGRect(x: 100, y: 300, width: 60, height: 100), container: iPhone,
            safeTop: 47, safeBottom: 34, bubbleHeight: 150
        )
        XCTAssertEqual(big.holeRadius, 22, accuracy: 0.001)
    }
}

// MARK: - 언제 띄우나 (C2·C4·C5)

final class CoachActivationTests: XCTestCase {
    func test_홈은_여행중_아니고_줄접힘_가려지지_않았을때만() {
        XCTAssertTrue(CoachRules.homeActive(running: false, floatOpen: false, covered: false))
        XCTAssertFalse(CoachRules.homeActive(running: true, floatOpen: false, covered: false))
        XCTAssertFalse(CoachRules.homeActive(running: false, floatOpen: true, covered: false))
        XCTAssertFalse(CoachRules.homeActive(running: false, floatOpen: false, covered: true))
    }

    func test_계획화면은_편집중이고_새코스나_방금만든코스이며_묻는창이_없을때() {
        XCTAssertTrue(CoachRules.editorFirstActive(editing: true, isNew: true, fresh: false, confirming: false))
        XCTAssertTrue(CoachRules.editorFirstActive(editing: true, isNew: false, fresh: true, confirming: false))
        XCTAssertTrue(CoachRules.editorFirstActive(editing: true, isNew: true, fresh: true, confirming: false))
        XCTAssertFalse(CoachRules.editorFirstActive(editing: true, isNew: false, fresh: false, confirming: false))
        XCTAssertFalse(CoachRules.editorFirstActive(editing: false, isNew: true, fresh: true, confirming: false))
        XCTAssertFalse(CoachRules.editorFirstActive(editing: true, isNew: true, fresh: true, confirming: true))
    }

    func test_지도검색은_칩줄이_보이고_검색칸과_결과가_닫혀있을때() {
        XCTAssertTrue(CoachRules.mapSearchActive(barShown: true, searching: false, resultsOpen: false))
        XCTAssertFalse(CoachRules.mapSearchActive(barShown: false, searching: false, resultsOpen: false))
        XCTAssertFalse(CoachRules.mapSearchActive(barShown: true, searching: true, resultsOpen: false))
        XCTAssertFalse(CoachRules.mapSearchActive(barShown: true, searching: false, resultsOpen: true))
    }
}
