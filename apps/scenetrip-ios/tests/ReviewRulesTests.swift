import SceneApiClient
@testable import SceneTrip
import XCTest

/// 리뷰 화면의 규칙 (MZ2AZ-363).
final class ReviewRulesTests: XCTestCase {
    /// 서버가 별점을 안 실어 주면 줄이 없다. 0건이면 「아직 없어요」, 있으면 평균과 수.
    func testRatingLineHasThreeStates() {
        XCTAssertEqual(ReviewRules.line(for: nil), .hidden)
        XCTAssertEqual(
            ReviewRules.line(for: RatingSummary(average: nil, count: 0, distribution: [0, 0, 0, 0, 0])),
            .empty
        )
        XCTAssertEqual(
            ReviewRules.line(for: RatingSummary(average: 4.56, count: 128, distribution: [2, 3, 10, 40, 73])),
            .rated(average: "4.6", count: 128)
        )
    }

    func testSpokenStarValueDropsTheDecimalForWholeRatings() {
        XCTAssertEqual(ReviewRules.starValue(4), "4")
        XCTAssertEqual(ReviewRules.starValue(4.6), "4.6")
    }

    func testAverageHasOneDecimal() {
        XCTAssertEqual(ReviewRules.average(5), "5.0")
        XCTAssertEqual(ReviewRules.average(4.04), "4.0")
        XCTAssertEqual(ReviewRules.average(nil), "-")
    }

    /// 탈퇴한 사람의 리뷰는 작성자가 비어 온다.
    func testMissingAuthorIsADeletedUser() {
        XCTAssertEqual(ReviewRules.authorName(ReviewAuthor(nickname: "제주러버")), "제주러버")
        XCTAssertEqual(ReviewRules.authorName(nil), tr("탈퇴한 사용자"))
    }

    func testEditedWhenUpdatedLaterThanCreated() {
        let created = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertFalse(ReviewRules.edited(createdAt: created, updatedAt: created))
        XCTAssertFalse(ReviewRules.edited(createdAt: created, updatedAt: created.addingTimeInterval(0.4)))
        XCTAssertTrue(ReviewRules.edited(createdAt: created, updatedAt: created.addingTimeInterval(60)))
    }

    /// 막대는 5점부터 내려가고, 몫은 전체에 대한 비율이다. `distribution` 의 첫 칸이 1점.
    func testBarsRunFromFiveToOne() {
        let bars = ReviewRules.bars([1, 0, 2, 6, 11])
        XCTAssertEqual(bars.map(\.score), [5, 4, 3, 2, 1])
        XCTAssertEqual(bars.map(\.count), [11, 6, 2, 0, 1])
        XCTAssertEqual(bars[0].share, 0.55, accuracy: 0.0001)
        XCTAssertEqual(bars[3].share, 0)
    }

    /// 리뷰가 없거나 길이가 틀린 분포가 와도 나누기에서 죽지 않는다.
    func testBarsSurviveEmptyOrMalformedDistribution() {
        XCTAssertEqual(ReviewRules.bars([0, 0, 0, 0, 0]).map(\.share), [0, 0, 0, 0, 0])
        XCTAssertEqual(ReviewRules.bars([3, 1]).map(\.count), [0, 0, 0, 0, 0])
        XCTAssertEqual(ReviewRules.bars([]).count, 5)
    }

    /// 평균의 별은 반 개 단위다 — 4.6 을 다섯 개로 채우지 않는다.
    func testStarsRoundToTheNearestHalf() {
        XCTAssertEqual(ReviewRules.stars(for: 4.6), [.full, .full, .full, .full, .half])
        XCTAssertEqual(ReviewRules.stars(for: 4.8), [.full, .full, .full, .full, .full])
        XCTAssertEqual(ReviewRules.stars(for: 4.2), [.full, .full, .full, .full, .empty])
        XCTAssertEqual(ReviewRules.stars(for: 3), [.full, .full, .full, .empty, .empty])
        XCTAssertEqual(ReviewRules.stars(for: 0), [.empty, .empty, .empty, .empty, .empty])
    }

    func testCountText() {
        XCTAssertEqual(ReviewRules.countText(128), String(format: tr("리뷰 %d"), 128))
        XCTAssertEqual(ReviewRules.countText(1), String(format: tr("리뷰 %d", at: "하나"), 1))
    }

    func testSpokenSummaryIsOneSentence() {
        let none = RatingSummary(average: nil, count: 0, distribution: [0, 0, 0, 0, 0])
        XCTAssertEqual(ReviewRules.spoken(none), tr("아직 리뷰가 없어요"))
        let some = RatingSummary(average: 4.6, count: 23, distribution: [1, 0, 2, 6, 14])
        XCTAssertTrue(ReviewRules.spoken(some).contains("4.6"))
        XCTAssertTrue(ReviewRules.spoken(some).contains("23"))
    }

    /// 글은 앞뒤 공백을 떼고, 비면 nil 이다(별점만 남긴 리뷰).
    func testBodyIsTrimmedAndEmptyBecomesNil() {
        XCTAssertEqual(ReviewRules.normalizedBody("  좋았어요\n"), "좋았어요")
        XCTAssertNil(ReviewRules.normalizedBody("   \n "))
    }

    /// 별점은 필수, 글은 2,000자까지.
    func testCanSaveNeedsARatingAndRespectsTheBodyLimit() {
        XCTAssertFalse(ReviewRules.canSave(rating: 0, body: "좋았어요"))
        XCTAssertTrue(ReviewRules.canSave(rating: 3, body: ""))
        XCTAssertTrue(ReviewRules.canSave(rating: 5, body: String(repeating: "가", count: 2000)))
        XCTAssertFalse(ReviewRules.canSave(rating: 5, body: String(repeating: "가", count: 2001)))
        XCTAssertFalse(ReviewRules.canSave(rating: 6, body: ""))
    }

    /// 길이는 서버처럼 UTF-16 단위로 센다 — 이모지 한 개는 2 다.
    func testBodyLengthCountsUtf16Units() {
        XCTAssertEqual(ReviewRules.bodyLength(" 가나다 "), 3)
        XCTAssertEqual(ReviewRules.bodyLength("😀"), 2)
        XCTAssertTrue(ReviewRules.canSave(rating: 5, body: String(repeating: "😀", count: 1000)))
        XCTAssertFalse(ReviewRules.canSave(rating: 5, body: String(repeating: "😀", count: 1001)))
    }

    /// 이미 쓴 리뷰와 같으면 고칠 것이 없다. 새 리뷰는 언제나 「바뀜」.
    func testChangedComparesRatingAndTrimmedBody() {
        let now = Date()
        let mine = Review(
            id: 1, rating: 4, body: "좋았어요", photos: [], author: nil,
            createdAt: now, updatedAt: now, isMine: true
        )
        XCTAssertTrue(ReviewRules.changed(from: nil, rating: 4, body: "좋았어요"))
        XCTAssertFalse(ReviewRules.changed(from: mine, rating: 4, body: " 좋았어요 "))
        XCTAssertTrue(ReviewRules.changed(from: mine, rating: 5, body: "좋았어요"))
        XCTAssertTrue(ReviewRules.changed(from: mine, rating: 4, body: ""))
        // 글 없이 쓴 리뷰에 공백만 넣은 것은 바뀐 것이 아니다.
        let starsOnly = Review(
            id: 2, rating: 3, body: nil, photos: [], author: nil,
            createdAt: now, updatedAt: now, isMine: true
        )
        XCTAssertFalse(ReviewRules.changed(from: starsOnly, rating: 3, body: "  \n"))
    }

    func testHasMore() {
        XCTAssertTrue(ReviewRules.hasMore(loaded: 20, total: 23))
        XCTAssertFalse(ReviewRules.hasMore(loaded: 23, total: 23))
        XCTAssertFalse(ReviewRules.hasMore(loaded: 0, total: 0))
    }

    /// 대상의 종류가 분석 이벤트와 시트의 열쇠로 쓰인다 — 촬영지 7 과 편의시설 7 은 다른 것이다.
    func testSubjectsWithTheSameNumberDiffer() {
        XCTAssertNotEqual(ReviewSubject.place(7), ReviewSubject.poi(7))
        XCTAssertNotEqual(ReviewSubject.place(7).id, ReviewSubject.poi(7).id)
        XCTAssertEqual(ReviewSubject.poi(7).kind, "poi")
    }
}
