import SceneApiClient
@testable import SceneTrip
import XCTest

/// 추천 코스 카드 뒤집기 — 뒷면 장면 그림 고르기·처음 한 번 살짝 돌기
/// (계획 docs/project/plans/motion-works-drawer-parity.md §6).
final class SceneFlipRulesTests: XCTestCase {
    private func scene(_ contentId: Int64, _ urls: [String]) -> SceneApiClient.Scene {
        SceneApiClient.Scene(contentId: contentId, contentTitle: "작품\(contentId)", sceneImageUrls: urls)
    }

    private func detail(_ scenes: [SceneApiClient.Scene]?) -> PlaceDetail {
        PlaceDetail(id: 1, name: "촬영지", latitude: 37.5, longitude: 127, scenes: scenes)
    }

    // MARK: 숫자

    func testConstantsMatchSpec() {
        XCTAssertEqual(SceneFlipRules.flipDuration, 0.45, accuracy: 0.0001)
        XCTAssertEqual(SceneFlipRules.peekDelay, 0.6, accuracy: 0.0001)
        XCTAssertEqual(SceneFlipRules.peekAngle, 25, accuracy: 0.0001)
        XCTAssertEqual(SceneFlipRules.maxConcurrentDetailFetches, 4)
    }

    // MARK: 장면 그림 고르기

    func testPicksFirstPictureOfCardWorkScene() {
        let place = detail([scene(1, ["a1"]), scene(2, ["b1", "b2"]), scene(3, ["c1"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: 2), "b1")
    }

    func testCardWorkSceneWinsEvenWhenNotFirst() {
        let place = detail([scene(1, ["a1", "a2"]), scene(9, ["z1"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: 9), "z1")
    }

    func testMultiplePicturesTakesFirst() {
        let place = detail([scene(5, ["first", "second", "third"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: 5), "first")
    }

    func testCardWorkSceneWithoutPicturesFallsBackToFirstSceneWithPictures() {
        let place = detail([scene(1, []), scene(2, []), scene(3, ["c1", "c2"]), scene(4, ["d1"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: 2), "c1")
    }

    func testMissingWorkIdFallsBackToFirstSceneWithPictures() {
        let place = detail([scene(1, []), scene(2, ["b1"]), scene(3, ["c1"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: nil), "b1")
    }

    func testWorkNotAmongScenesFallsBackToFirstSceneWithPictures() {
        let place = detail([scene(1, ["a1"]), scene(2, ["b1"])])
        XCTAssertEqual(SceneFlipRules.sceneImageUrl(detail: place, workId: 77), "a1")
    }

    func testNoPicturesAnywhereIsNil() {
        XCTAssertNil(SceneFlipRules.sceneImageUrl(detail: detail([scene(1, []), scene(2, [])]), workId: 1))
        XCTAssertNil(SceneFlipRules.sceneImageUrl(detail: detail([scene(1, []), scene(2, [])]), workId: nil))
    }

    func testEmptyOrMissingScenesIsNil() {
        XCTAssertNil(SceneFlipRules.sceneImageUrl(detail: detail([]), workId: 1))
        XCTAssertNil(SceneFlipRules.sceneImageUrl(detail: detail(nil), workId: 1))
        XCTAssertNil(SceneFlipRules.sceneImageUrl(detail: detail(nil), workId: nil))
    }

    // MARK: 처음 한 번 살짝 돌기

    func testPeeksFirstFlippableCard() {
        XCTAssertEqual(SceneFlipRules.peekIndex(flippable: [true, true, false], hasFlipped: false, reduceMotion: false), 0)
    }

    func testPeeksFirstFlippableEvenWhenNotAtStart() {
        XCTAssertEqual(SceneFlipRules.peekIndex(flippable: [false, false, true, true], hasFlipped: false, reduceMotion: false), 2)
        XCTAssertEqual(SceneFlipRules.peekIndex(flippable: [false, true], hasFlipped: false, reduceMotion: false), 1)
    }

    func testNoPeekAfterUserHasFlipped() {
        XCTAssertNil(SceneFlipRules.peekIndex(flippable: [true, true], hasFlipped: true, reduceMotion: false))
    }

    func testNoPeekWithReduceMotion() {
        XCTAssertNil(SceneFlipRules.peekIndex(flippable: [true, true], hasFlipped: false, reduceMotion: true))
        XCTAssertNil(SceneFlipRules.peekIndex(flippable: [true], hasFlipped: true, reduceMotion: true))
    }

    func testNoPeekWithoutFlippableCards() {
        XCTAssertNil(SceneFlipRules.peekIndex(flippable: [false, false, false], hasFlipped: false, reduceMotion: false))
        XCTAssertNil(SceneFlipRules.peekIndex(flippable: [], hasFlipped: false, reduceMotion: false))
    }

    // MARK: 카드 사진 칸의 두 면 — 모든 카드가 뒤집히고, 없는 쪽은 「없음」 면(§6 표)

    private let photo = "https://img.example.com/place.jpg"
    private let sceneArt = "https://img.example.com/scene.png"

    func testPhotoAndSceneFrontPhotoBackScene() {
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: photo, sceneUrl: sceneArt),
            SceneFlipFaces(front: photo, back: .scene(sceneArt), frontIsScene: false)
        )
    }

    func testSceneOnlyFrontSceneBackNoPhoto() {
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: nil, sceneUrl: sceneArt),
            SceneFlipFaces(front: sceneArt, back: .noPhoto, frontIsScene: true)
        )
    }

    func testPhotoOnlyFrontPhotoBackNoScene() {
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: photo, sceneUrl: nil),
            SceneFlipFaces(front: photo, back: .noScene, frontIsScene: false)
        )
    }

    func testNeitherEmptyFrontBackNoScene() {
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: nil, sceneUrl: nil),
            SceneFlipFaces(front: nil, back: .noScene, frontIsScene: false)
        )
    }

    func testHttpPhotoCountsAsPhoto() {
        let plain = "http://img.example.com/place.jpg"
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: plain, sceneUrl: sceneArt),
            SceneFlipFaces(front: plain, back: .scene(sceneArt), frontIsScene: false)
        )
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: plain, sceneUrl: nil),
            SceneFlipFaces(front: plain, back: .noScene, frontIsScene: false)
        )
    }

    func testEmptyPhotoTreatedAsMissing() {
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: "", sceneUrl: sceneArt),
            SceneFlipFaces(front: sceneArt, back: .noPhoto, frontIsScene: true)
        )
        XCTAssertEqual(
            SceneFlipRules.faces(photoUrl: "", sceneUrl: nil),
            SceneFlipFaces(front: nil, back: .noScene, frontIsScene: false)
        )
    }

    func testNonHttpPhotoTreatedAsMissing() {
        // http(s) 가 아닌 주소는 쓸 수 없는 사진 — 없는 것과 같다.
        for bad in ["ftp://img.example.com/place.jpg", "file:///tmp/place.jpg", "place.jpg", "not a url"] {
            XCTAssertEqual(
                SceneFlipRules.faces(photoUrl: bad, sceneUrl: sceneArt),
                SceneFlipFaces(front: sceneArt, back: .noPhoto, frontIsScene: true),
                "사진 주소 \(bad)"
            )
            XCTAssertEqual(
                SceneFlipRules.faces(photoUrl: bad, sceneUrl: nil),
                SceneFlipFaces(front: nil, back: .noScene, frontIsScene: false),
                "사진 주소 \(bad)"
            )
        }
    }

    func testPhotoWithoutHostTreatedAsMissing() {
        // 주인(host)이 없는 http(s) 주소도 쓸 수 없는 사진.
        for bad in ["https://", "http://", "https:///place.jpg"] {
            XCTAssertEqual(
                SceneFlipRules.faces(photoUrl: bad, sceneUrl: sceneArt),
                SceneFlipFaces(front: sceneArt, back: .noPhoto, frontIsScene: true),
                "사진 주소 \(bad)"
            )
            XCTAssertEqual(
                SceneFlipRules.faces(photoUrl: bad, sceneUrl: nil),
                SceneFlipFaces(front: nil, back: .noScene, frontIsScene: false),
                "사진 주소 \(bad)"
            )
        }
    }
}
