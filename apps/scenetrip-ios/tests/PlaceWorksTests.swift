import SceneApiClient
@testable import SceneTrip
import XCTest

/// 촬영지가 어느 작품에 나왔는지를 응답에서 읽는 규칙 (MZ2AZ-372).
///
/// 상세(`GET /places/{id}`)의 작품은 `scenes` 에 있다 — `contents` 는 서버가 빈 배열로 준다. 그 칸을 읽었다가
/// 인기 200곳 밖 정지점의 작품 줄이 계속 비었다(화홍마트, id 49).
final class PlaceWorksTests: XCTestCase {
    private func scene(_ id: Int64, _ title: String) -> SceneItem {
        SceneItem(contentId: id, contentTitle: title)
    }

    /// 서버가 실제로 주는 꼴 — `contents` 는 비고 `scenes` 에 작품이 있다.
    func testDetailTitlesComeFromScenes() {
        XCTAssertEqual(
            PlaceWorks.titles(scenes: [scene(7, "Lovely Runner")], contents: []),
            ["Lovely Runner"]
        )
    }

    /// 서버가 정한 순서(인기순)를 지키고, 같은 작품이 장면 둘로 와도 한 번만.
    func testKeepsOrderAndDropsRepeats() {
        let scenes = [scene(8, "도깨비"), scene(7, "선재 업고 튀어"), scene(8, "도깨비"), scene(3, "더 글로리")]
        XCTAssertEqual(PlaceWorks.titles(scenes: scenes, contents: nil), ["도깨비", "선재 업고 튀어", "더 글로리"])
        XCTAssertEqual(PlaceWorks.refs(scenes: scenes, contents: nil).map(\.contentId), [8, 7, 3])
    }

    /// 장면이 없으면 `contents` 로 물러선다 — 목록 응답이거나, 서버가 상세의 그 칸을 채우기 시작했을 때.
    func testFallsBackToContentsWhenThereAreNoScenes() {
        let contents = [ContentRef(contentId: 1, title: "폭싹 속았수다")]
        XCTAssertEqual(PlaceWorks.titles(scenes: [], contents: contents), ["폭싹 속았수다"])
        XCTAssertEqual(PlaceWorks.titles(scenes: nil, contents: contents), ["폭싹 속았수다"])
    }

    /// 어느 작품에도 안 나온 곳 — 빈 답이다(오류가 아니다).
    func testNoWorksIsAnEmptyAnswer() {
        XCTAssertEqual(PlaceWorks.titles(scenes: [], contents: []), [])
        XCTAssertEqual(PlaceWorks.titles(scenes: nil, contents: nil), [])
        XCTAssertEqual(PlaceWorks.titles(scenes: [scene(1, "")], contents: nil), [])
    }

    // MARK: 무엇을 물을 것인가

    /// 받아 둔 목록에 없는 촬영지만 묻는다. 직접 찍은 핀(음수 id)은 묻지 않는다.
    func testAsksOnlyPlacesOutsideTheLoadedList() {
        XCTAssertEqual(
            PlaceWorks.toAsk([12, 49, -3, 551], listed: [12], answered: [], asking: []),
            [49, 551]
        )
    }

    /// **빈 답도 답이다** — 작품이 정말 없는 곳을 화면을 열 때마다 다시 묻지 않는다.
    func testAnsweredPlacesAreNotAskedAgainEvenIfEmpty() {
        let answers: [Int64: [String]] = [49: ["Lovely Runner"], 551: []]
        XCTAssertEqual(PlaceWorks.toAsk([49, 551, 600], listed: [], answered: Set(answers.keys), asking: []), [600])
    }

    /// 못 받은 곳은 답이 적히지 않으므로 다음에 다시 묻는다.
    func testFailedPlacesAreAskedAgain() {
        // 49 는 받았고 551 은 끊겨서 못 받았다 — `answered` 에는 49 뿐이다.
        XCTAssertEqual(PlaceWorks.toAsk([49, 551], listed: [], answered: [49], asking: []), [551])
    }

    /// 지금 묻고 있는 곳과 같은 코스에 두 번 든 곳은 한 번만.
    func testDoesNotAskTwice() {
        XCTAssertEqual(PlaceWorks.toAsk([49, 49, 551], listed: [], answered: [], asking: [551]), [49])
    }
}
