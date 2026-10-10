import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class CommunityServerTests: XCTestCase {
    func testSignedPostResponsesNeverUseDiskCache() {
        for path in ["/v1/posts", "/v1/posts/42", "/v1/me/posts"] {
            XCTAssertTrue(AuthRules.carriesSignedUrls(url: "https://example.test" + path))
        }
    }

    func testLimitsTrimAndRejectEmptyBody() {
        XCTAssertFalse(CommunityRules.canPost(title: "hello", body: " \n", photoCount: 0))
        XCTAssertFalse(CommunityRules.canPost(title: String(repeating: "t", count: 101), body: "b", photoCount: 0))
        XCTAssertFalse(CommunityRules.canPost(title: "t", body: "b", photoCount: 9))
        XCTAssertTrue(CommunityRules.canPost(title: " t ", body: " b ", photoCount: 8))
    }

    func testAnonymousServerPostIsNotMineEvenWithMissingAuthor() {
        let summary = TripPostSummary(id: 42, title: "t", excerpt: "e", createdAt: Date(), photoCount: 0, isMine: false)
        let post = CommunityPost(summary: summary)
        XCTAssertFalse(post.isMine)
        XCTAssertEqual(post.authorName(myNickname: "my nickname"), tr("탈퇴한 사용자"))
    }

    func testRefreshFailureKeepsFeedAndSurfacesFailure() async {
        let api = CommunityFakeAPI()
        let store = CommunityStore(client: api, accountEpoch: { 0 })
        await store.refresh()
        XCTAssertEqual(store.posts.count, 1)
        api.failReads = true
        await store.refresh()
        XCTAssertEqual(store.posts.count, 1)
        XCTAssertNotNil(store.message)
    }

    func testUncertainCreateDoesNotResendAndCanReconcile() async throws {
        let api = CommunityFakeAPI()
        let store = CommunityStore(client: api, accountEpoch: { 0 })
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let input = TripPostCreate(title: "new", body: "body", photoKeys: ["uploads/tmp/unique-photo.jpg"])
        api.loseCreateResponse = true
        let first = await draft.submit(input)
        XCTAssertNil(first)
        XCTAssertTrue(draft.uncertain)
        let second = await draft.submit(input)
        XCTAssertNil(second)
        XCTAssertEqual(api.createCount, 1)
        api.mine = try [XCTUnwrap(api.created)]
        let recovered = await draft.check()
        XCTAssertEqual(recovered?.id, 99)
        try store.accept(XCTUnwrap(recovered))
        XCTAssertEqual(store.posts.first?.serverId, 99)
    }

    func testPostPhotoDraftHasEightSlotsAndPostPurpose() {
        let draft = ReviewPhotoDraft(purpose: .post, limit: 8)
        XCTAssertEqual(draft.limit, 8)
        XCTAssertEqual(draft.remaining, 8)
        XCTAssertEqual(draft.purpose, .post)
    }

    func testSuccessPreventsASecondPost() async {
        let api = CommunityFakeAPI()
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let input = TripPostCreate(title: "new", body: "body", photoKeys: [])
        let first = await draft.submit(input)
        let second = await draft.submit(input)
        XCTAssertEqual(first?.id, 99)
        XCTAssertNil(second)
        XCTAssertEqual(api.createCount, 1)
    }

    func testFailedPreflightNeverCreatesAPost() async {
        let api = CommunityFakeAPI()
        api.failReads = true
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let saved = await draft.submit(TripPostCreate(title: "t", body: "b", photoKeys: []))
        XCTAssertNil(saved)
        XCTAssertEqual(api.createCount, 0)
        XCTAssertFalse(draft.uncertain)
        XCTAssertNotNil(draft.message)
    }

    func testDefinitiveRejectedPhotoAllowsRetryAndMarksPhotos() async {
        let api = CommunityFakeAPI()
        api.createError = ErrorResponse.error(400, Data(#"{"code":"POST_PHOTO_INVALID"}"#.utf8), nil, URLError(.badServerResponse))
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let input = TripPostCreate(title: "t", body: "b", photoKeys: [])
        let first = await draft.submit(input)
        XCTAssertNil(first)
        XCTAssertTrue(draft.photoInvalid)
        XCTAssertFalse(draft.uncertain)
        api.createError = nil
        let saved = await draft.submit(input)
        XCTAssertEqual(saved?.id, 99)
        XCTAssertEqual(api.createCount, 2)
    }

    func testAccountChangeDiscardsOldCreateResponse() async {
        var epoch = 0
        let api = CommunityFakeAPI()
        api.onCreate = { epoch += 1 }
        let draft = CommunitySubmission(client: api, accountEpoch: { epoch })
        let saved = await draft.submit(TripPostCreate(title: "t", body: "b", photoKeys: []))
        XCTAssertNil(saved)
        XCTAssertFalse(draft.completed)
    }

    func testLateFeedFromOldAccountIsDiscarded() async {
        var epoch = 0
        let api = CommunityFakeAPI()
        api.onList = { epoch += 1 }
        let store = CommunityStore(client: api, accountEpoch: { epoch })
        await store.refresh()
        XCTAssertTrue(store.posts.isEmpty)
    }

    func testServerDetailKeepsPhotoOrderAndStableIdentity() {
        let detail = TripPostDetail(id: Int64.max, title: "t", body: "full", createdAt: Date(),
                                    photoUrls: ["https://s.test/posts/a.jpg?s=1", "https://s.test/posts/b.jpg?s=2"], isMine: true)
        let post = CommunityPost(detail: detail)
        XCTAssertEqual(post.remotePhotos, detail.photoUrls)
        XCTAssertEqual(post.id, CommunityRules.identity(Int64.max))
        XCTAssertTrue(post.isMine)
        XCTAssertNil(post.photos)
    }

    func testPostMatchingRequiresSameOrderedFilesAndCoursePresence() {
        let detail = TripPostDetail(id: 1, title: "t", body: "b", createdAt: Date(),
                                    photoUrls: ["https://s.test/posts/a.jpg?s=1", "https://s.test/posts/b.jpg?s=2"], isMine: true)
        XCTAssertTrue(CommunityRules.matches(detail, input: TripPostCreate(title: "t", body: "b", photoKeys: ["uploads/tmp/a.jpg", "uploads/tmp/b.jpg"])))
        XCTAssertFalse(CommunityRules.matches(detail, input: TripPostCreate(title: "t", body: "b", photoKeys: ["uploads/tmp/b.jpg", "uploads/tmp/a.jpg"])))
        XCTAssertFalse(CommunityRules.matches(detail, input: TripPostCreate(title: "t", body: "b", photoKeys: ["uploads/tmp/a.jpg", "uploads/tmp/b.jpg"], courseId: 42)))
    }

    func testFailedDeleteRetainsPost() async {
        let api = CommunityFakeAPI()
        let store = CommunityStore(client: api, accountEpoch: { 0 })
        let detail = TripPostDetail(id: 1, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: true)
        store.accept(detail)
        api.deleteError = URLError(.networkConnectionLost)
        await store.remove(store.posts[0])
        XCTAssertEqual(store.posts.count, 1)
        XCTAssertNotNil(store.message)
        api.deleteError = nil
        await store.remove(store.posts[0])
        XCTAssertTrue(store.posts.isEmpty)
    }

    func testAcceptSamePostDoesNotIncreaseCounters() {
        let store = CommunityStore(client: CommunityFakeAPI(), accountEpoch: { 0 })
        let detail = TripPostDetail(id: 1, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: true)
        store.accept(detail)
        store.accept(detail)
        XCTAssertEqual(store.posts.count, 1)
        XCTAssertEqual(store.total, 1)
        XCTAssertEqual(store.mineTotal, 1)
    }

    func testOfflineCanRetryButTimeoutAndServerErrorAreUncertain() {
        XCTAssertFalse(CommunityRules.uncertain(URLError(.notConnectedToInternet)))
        XCTAssertFalse(CommunityRules.uncertain(URLError(.cannotConnectToHost)))
        XCTAssertTrue(CommunityRules.uncertain(URLError(.timedOut)))
        XCTAssertTrue(CommunityRules.uncertain(ErrorResponse.error(503, nil, nil, URLError(.badServerResponse))))
        XCTAssertTrue(CommunityRules.uncertain(ErrorResponse.error(503, nil, nil, URLError(.notConnectedToInternet))))
    }

    func testUnconfirmedSubmissionNeverResendsEvenIfNoPostIsFound() async {
        let api = CommunityFakeAPI()
        api.loseCreateResponse = true
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let input = TripPostCreate(title: "t", body: "b", photoKeys: [])
        _ = await draft.submit(input)
        let checked = await draft.check()
        XCTAssertNil(checked)
        _ = await draft.submit(input)
        XCTAssertEqual(api.createCount, 1)
        XCTAssertNotNil(draft.message)
    }

    func testServerCourseSnapshotCarriesBothPlaceAndPoiMetadata() {
        let place = TripPostCourseStop(source: .place, placeId: 42, name: "Palace", latitude: 37, longitude: 127, dwellMinutes: 30)
        let poi = TripPostCourseStop(source: .poi, poiId: 123, name: "식당", displayName: "Restaurant", nameRoman: "Sikdang",
                                     address: "서울", displayAddress: "Seoul", latitude: 37.1, longitude: 127.1, dwellMinutes: 60)
        let course = PostCourse(TripPostCourse(title: "trip", days: [TripPostCourseDay(dayNumber: 1, stops: [place, poi])]))
        XCTAssertEqual(course.days[0].map(\.placeId), [42, nil])
        XCTAssertEqual(course.days[0].map(\.poiId), [nil, 123])
        XCTAssertEqual(course.days[0][1].displayName, "Restaurant")
        XCTAssertEqual(course.days[0][1].displayAddress, "Seoul")
    }

    func testSameBodyWithDifferentCourseIsNotConfirmed() {
        let stop = PostCourse.Stop(placeId: 42, name: "place", latitude: 37, longitude: 127)
        let selected = PostCourse(title: "selected", days: [[stop]])
        let input = TripPostCreate(title: "same", body: "same", photoKeys: ["uploads/tmp/a.jpg"], courseId: 9)
        let otherStop = TripPostCourseStop(source: .place, placeId: 43, name: "other", latitude: 37, longitude: 127, dwellMinutes: 30)
        let wrong = TripPostCourse(title: "selected", days: [TripPostCourseDay(dayNumber: 1, stops: [otherStop])])
        var detail = TripPostDetail(id: 1, title: "same", body: "same", createdAt: Date(), photoUrls: ["https://s.test/posts/a.jpg"], course: wrong, isMine: true)
        XCTAssertFalse(CommunityRules.matches(detail, input: input, expectedCourse: selected))
        let sameStop = TripPostCourseStop(source: .place, placeId: 42, name: "place", latitude: 37, longitude: 127, dwellMinutes: 30)
        detail.course = TripPostCourse(title: "selected", days: [TripPostCourseDay(dayNumber: 1, stops: [sameStop])])
        XCTAssertTrue(CommunityRules.matches(detail, input: input, expectedCourse: selected))
    }

    func testPhotoLessIdenticalPostCannotConfirmAmbiguousSubmission() async throws {
        let api = CommunityFakeAPI()
        api.loseCreateResponse = true
        let draft = CommunitySubmission(client: api, accountEpoch: { 0 })
        let input = TripPostCreate(title: "same", body: "same", photoKeys: [])
        _ = await draft.submit(input)
        api.mine = try [XCTUnwrap(api.created)]
        let checked = await draft.check()
        XCTAssertNil(checked)
        XCTAssertFalse(draft.completed)
        XCTAssertTrue(draft.uncertain)
        XCTAssertEqual(api.createCount, 1)
    }
}

@MainActor
private final class CommunityFakeAPI: CommunityClient {
    var failReads = false
    var loseCreateResponse = false
    var createCount = 0
    var mine: [TripPostDetail] = []
    var created: TripPostDetail?
    var createError: Error?
    var deleteError: Error?
    var onCreate: (() -> Void)?
    var onList: (() -> Void)?

    func list(mine onlyMine: Bool, limit _: Int, offset _: Int) async throws -> TripPostList {
        if failReads {
            throw URLError(.notConnectedToInternet)
        }
        onList?()
        let posts = onlyMine ? mine : [TripPostDetail(id: 42, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: false)]
        let items = posts.map { TripPostSummary(id: $0.id, title: $0.title, excerpt: $0.body, createdAt: $0.createdAt,
                                                photoCount: $0.photoUrls.count, isMine: $0.isMine) }
        return TripPostList(items: items, total: posts.count)
    }

    func detail(_ id: Int64) async throws -> TripPostDetail {
        if let created, created.id == id {
            return created
        }
        return mine.first { $0.id == id }!
    }

    func create(_ input: TripPostCreate) async throws -> TripPostDetail {
        createCount += 1
        onCreate?()
        if let createError {
            throw createError
        }
        let urls = input.photoKeys.map { "https://s.test/posts/" + ($0 as NSString).lastPathComponent }
        created = TripPostDetail(id: 99, title: input.title, body: input.body, createdAt: Date(), photoUrls: urls, isMine: true)
        if loseCreateResponse {
            throw URLError(.networkConnectionLost)
        }
        return created!
    }

    func delete(_: Int64) async throws {
        if let deleteError {
            throw deleteError
        }
    }

    func saveCourse(_: Int64) async throws -> CourseDetail {
        throw URLError(.unsupportedURL)
    }
}
