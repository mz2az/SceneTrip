import Foundation
import SceneApiClient

/// 생성 API를 화면·실패 시험에서 같은 모양으로 쓴다. HTTP는 직접 만들지 않는다.
@MainActor
protocol CommunityClient {
    func list(mine: Bool, limit: Int, offset: Int) async throws -> TripPostList
    func detail(_ id: Int64) async throws -> TripPostDetail
    func create(_ input: TripPostCreate) async throws -> TripPostDetail
    func delete(_ id: Int64) async throws
    func saveCourse(_ id: Int64) async throws -> CourseDetail
}

@MainActor
struct CommunityServerClient: CommunityClient {
    func list(mine: Bool, limit: Int, offset: Int) async throws -> TripPostList {
        await AuthRefresher.refreshIfStale()
        if mine {
            return try await PostsAPI.listMyPosts(acceptLanguage: AppLanguage.current, limit: limit, offset: offset)
        }
        return try await PostsAPI.listPosts(acceptLanguage: AppLanguage.current, limit: limit, offset: offset)
    }

    func detail(_ id: Int64) async throws -> TripPostDetail {
        await AuthRefresher.refreshIfStale()
        return try await PostsAPI.getPost(postId: id, acceptLanguage: AppLanguage.current)
    }

    func create(_ input: TripPostCreate) async throws -> TripPostDetail {
        try await PostsAPI.createPost(tripPostCreate: input, acceptLanguage: AppLanguage.current)
    }

    func delete(_ id: Int64) async throws {
        try await PostsAPI.deletePost(postId: id)
    }

    func saveCourse(_ id: Int64) async throws -> CourseDetail {
        try await PostsAPI.savePostCourse(xInstallId: InstallIdentity.current, postId: id, acceptLanguage: AppLanguage.current)
    }
}

enum CommunityRules {
    static let photoLimit = 8
    static let pageSize = 20
    static let titleLimit = 100
    static let bodyLimit = 5000

    static func normalized(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func canPost(title: String, body: String, photoCount: Int) -> Bool {
        let title = normalized(title), body = normalized(body)
        return !title.isEmpty && title.count <= titleLimit && !body.isEmpty && body.count <= bodyLimit
            && (0 ... photoLimit).contains(photoCount)
    }

    static func identity(_ id: Int64) -> UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-%04llx-%012llx", id >> 48, id & 0xFFFF_FFFF_FFFF))!
    }

    static func failureText(_ error: Error) -> String {
        if case let ErrorResponse.error(status, data, _, _) = error {
            let code = AuthRules.apiCode(from: data)
            if status == 401 {
                return tr("로그인한 뒤에 쓸 수 있어요")
            }
            switch code {
            case "POST_PHOTO_INVALID": return tr("사진을 다시 올려 주세요")
            case "POST_COURSE_INVALID": return tr("붙인 코스를 다시 골라 주세요")
            case "RATE_LIMITED": return tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
            case "POST_NOT_FOUND": return tr("이 후기는 더 이상 볼 수 없어요")
            default: break
            }
        }
        return tr("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요")
    }

    /// 4xx는 저장하지 않았다는 응답이다. 그 밖의 실패는 응답만 잃었을 수 있다.
    static func uncertain(_ error: Error) -> Bool {
        if case let ErrorResponse.error(status, _, _, underlying) = error {
            if (400 ..< 500).contains(status) {
                return false
            }
            if status >= 500 {
                return true
            }
            return uncertain(underlying)
        }
        if let urlError = error as? URLError {
            return ![.cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet,
                     .dataNotAllowed, .internationalRoamingOff].contains(urlError.code)
        }
        return true
    }

    static func matches(_ detail: TripPostDetail, input: TripPostCreate, expectedCourse: PostCourse? = nil) -> Bool {
        // 소비되는 업로드 UUID가 없으면 다른 기기의 같은 글과 구별할 근거가 없다.
        guard !input.photoKeys.isEmpty else { return false }
        guard detail.title == input.title, detail.body == input.body,
              (detail.course != nil) == (input.courseId != nil) else { return false }
        let names = detail.photoUrls.compactMap { URL(string: $0)?.lastPathComponent }
        guard names == input.photoKeys.map({ ($0 as NSString).lastPathComponent }) else { return false }
        guard input.courseId != nil else { return true }
        guard let expectedCourse, let course = detail.course else { return false }
        let expected = expectedCourse.days.map { $0.compactMap(stopIdentity) }
        let received = course.days.sorted { $0.dayNumber < $1.dayNumber }.map { day in
            day.stops.map { "\($0.source.rawValue):\($0.placeId ?? $0.poiId ?? -1)" }
        }
        return expectedCourse.title == course.title && expected == received
    }

    private static func stopIdentity(_ stop: PostCourse.Stop) -> String? {
        if let id = stop.placeId {
            return "place:\(id)"
        }
        return stop.poiId.map { "poi:\($0)" }
    }
}
