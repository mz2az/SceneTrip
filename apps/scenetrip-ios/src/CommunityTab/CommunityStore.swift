import Foundation
import SceneApiClient
import UIKit

/// 글에 붙인 코스 — **붙인 순간의 사본**이다 (MZ2AZ-351).
///
/// 코스 id 만 들고 있으면 글쓴이가 코스를 고치거나 지웠을 때 글이 달라지거나 빈다.
/// 후기는 「내가 다녀온 그 코스」를 말하는 글이므로 일차·장소까지 그대로 박아 둔다.
/// 읽는 사람은 이것을 보고, 「내 코스로 담기」로 자기 코스를 하나 만든다.
struct PostCourse: Codable, Hashable {
    struct Stop: Codable, Hashable {
        /// 촬영지 id. 편의시설·지도에 직접 찍은 핀이면 없다.
        var placeId: Int64?
        var name: String
        var address: String?
        var type: String?
        var latitude: Double
        var longitude: Double
        var imageUrl: String?
        /// 편의시설 id(MZ2AZ-380). 편의시설로 담긴 곳에만 있다 — **이 칸이 생기기 전에 쓴 글에는 없고**,
        /// 그 글의 편의시설은 그때 저장되던 대로 개인 핀이다(`placeId` 도 없다).
        var poiId: Int64?
        var displayName: String?
        var nameRoman: String?
        var displayAddress: String?
        var categoryLabel: String?

        var shownName: String {
            AppLanguage.current == .ko ? name : displayName ?? name
        }

        var shownAddress: String? {
            AppLanguage.current == .ko ? address : displayAddress ?? address
        }
    }

    var title: String
    var days: [[Stop]]
    var serverId: Int64?
    var excludedPins: Int?

    var placeCount: Int {
        days.reduce(0) { $0 + $1.count }
    }

    init(title: String, days: [[Stop]]) {
        self.title = title
        self.days = days
    }

    init(from course: RouteCourse) {
        title = course.title
        serverId = course.serverId
        excludedPins = course.days.flatMap(\.stops).filter { $0.kind == .pin }.count
        days = course.days.map { day in
            day.stops.map { stop in
                Stop(
                    placeId: stop.placeId,
                    name: stop.place.name, address: stop.place.address, type: stop.place.type,
                    latitude: stop.place.latitude, longitude: stop.place.longitude,
                    imageUrl: stop.place.imageUrl, poiId: stop.poiId
                )
            }
        }
    }

    /// 내 코스로 담을 **새 코스**. 서버 id 가 없으므로 저장하면 내 것이 하나 생긴다.
    func asNewCourse() -> RouteCourse {
        RouteCourse(
            title: title,
            days: days.map { stops in
                RouteDay(stops: stops.enumerated().map { index, stop in
                    // 촬영지 id 가 있으면 촬영지, 편의시설 id 가 있으면 편의시설, 둘 다 없으면 개인 핀.
                    let kind: RouteStop.Kind = stop.placeId != nil ? .place : stop.poiId.map(RouteStop.Kind.poi) ?? .pin
                    let placeId: Int64 = switch kind {
                    case .place: stop.placeId ?? RouteStop.noPlaceId
                    case .poi: RouteStop.noPlaceId
                    case .pin: -Int64(index + 1)
                    }
                    return RouteStop(
                        place: PlaceSummary(
                            id: placeId, name: stop.name, type: stop.type,
                            address: stop.address, latitude: stop.latitude, longitude: stop.longitude,
                            imageUrl: stop.imageUrl
                        ),
                        kind: kind
                    )
                })
            }
        )
    }
}

/// 서버 글과 기기에 남은 옛 글. 서버 사진 주소는 메모리에서만 사용한다(MZ2AZ-396).
struct CommunityPost: Identifiable, Codable {
    /// 옛 말머리. **지금은 여행후기 하나다**(2026-10-03 팀 회의) — 화면에서 고르지 않는다.
    /// 옛 글을 읽기 위해 갈래는 남겨 둔다.
    enum Board: String, Codable, CaseIterable, Identifiable {
        case course = "코스 추천"
        case review = "장소 후기"
        case photo = "인증샷"
        case chat = "자유"

        var id: String {
            rawValue
        }
    }

    let id: UUID
    let board: Board
    let title: String
    let body: String
    let createdAt: Date

    /// 옛 글의 첨부 — 코스 이름만 있었다. 새 글은 `course` 를 쓴다.
    var courseTitle: String?

    /// 사진 파일 이름들(`CommunityStore.photoURL`). 첫 장이 대표 사진이다.
    var photos: [String]?

    /// 붙인 코스의 사본.
    var course: PostCourse?

    /// 서버의 글쓴이 이름. 탈퇴하면 nil이며 소유 여부는 serverIsMine으로 판정한다.
    var author: String?
    var serverId: Int64?
    var serverIsMine: Bool?
    var remotePhotos: [String]?
    var detailLoaded: Bool?

    var isMine: Bool {
        serverIsMine ?? (author == nil)
    }
}

extension CommunityPost {
    /// 화면에 보일 글쓴이 이름 — **이름이 나오는 자리는 모두 이것을 쓴다.**
    ///
    /// 남의 글은 저장된 이름 그대로다. 내 글은 **지금 로그인한 계정의 닉네임**이고, 로그인하지
    /// 않았거나 닉네임을 아직 못 읽었으면 「나」다. 닉네임을 글에 저장하지 않고 그릴 때마다
    /// 받는 이유: 기기의 글은 계정이 아니라 **기기에 묶여** 있다(`isMine`). 저장해 두면 닉네임을
    /// 바꿔도 옛 이름이 남고, 로그아웃한 뒤에도 그 계정 이름이 붙어 있게 된다.
    func authorName(myNickname: String?) -> String {
        if let author {
            return author
        }
        if serverId != nil {
            return tr("탈퇴한 사용자")
        }
        let nickname = myNickname?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return nickname.isEmpty ? tr("나") : nickname
    }

    /// 글쓴이 동그라미에 넣는 한 글자 — 보이는 이름의 첫 글자다.
    func authorInitial(myNickname: String?) -> String {
        String(authorName(myNickname: myNickname).prefix(1))
    }
}

@MainActor
final class CommunityStore: ObservableObject {
    /// **앱에 하나뿐이다** — 커뮤니티에서 쓴 글이 마이페이지의 「내가 쓴 글」에
    /// 바로 보여야 한다(`LikeStore.shared` 와 같은 이유).
    static let shared = CommunityStore()

    @Published private(set) var posts: [CommunityPost] = []
    @Published private(set) var myPosts: [CommunityPost] = []
    @Published private(set) var legacyPosts: [CommunityPost] = []
    @Published private(set) var loading = false
    @Published private(set) var mineLoading = false
    @Published private(set) var message: String?
    @Published private(set) var total = 0
    @Published private(set) var mineTotal = 0
    private let client: CommunityClient
    private let accountEpoch: @MainActor () -> Int
    private var loadedEpoch: Int?
    private var revision = 0

    init(client: CommunityClient? = nil, accountEpoch: (@MainActor () -> Int)? = nil) {
        self.client = client ?? CommunityServerClient()
        self.accountEpoch = accountEpoch ?? { AuthStore.shared.epoch }
        if let data = UserDefaults.standard.data(forKey: "scenetrip.communityPosts"),
           let saved = try? JSONDecoder().decode([CommunityPost].self, from: data)
        {
            legacyPosts = saved
        }
    }

    /// 내가 쓴 글만 — 마이페이지의 「내가 쓴 글」.
    var mine: [CommunityPost] {
        myPosts
    }

    /// 목록은 서버 최신순으로 받는다. 실패한 새로고침에서 기존 글을 지우지 않는다.
    func refresh(mine: Bool = false, more: Bool = false) async {
        let epoch = accountEpoch()
        if loadedEpoch != epoch {
            posts = []
            myPosts = []
            total = 0
            mineTotal = 0
            loadedEpoch = epoch
            revision += 1
            loading = false
            mineLoading = false
        }
        guard !(mine ? mineLoading : loading) else { return }
        if mine {
            mineLoading = true
        } else {
            loading = true
        }
        message = nil
        let generation = revision
        defer {
            if generation == revision {
                if mine {
                    mineLoading = false
                } else {
                    loading = false
                }
            }
        }
        let old = mine ? myPosts : posts
        do {
            let page = try await client.list(mine: mine, limit: CommunityRules.pageSize, offset: more ? old.count : 0)
            guard accountEpoch() == epoch, generation == revision else { return }
            var merged = more ? old : []
            for post in page.items.map(CommunityPost.init(summary:)) {
                if let index = merged.firstIndex(where: { $0.id == post.id }) {
                    merged[index] = post
                } else {
                    merged.append(post)
                }
            }
            if mine {
                myPosts = merged; mineTotal = page.total
            } else {
                posts = merged; total = page.total
            }
        } catch {
            guard accountEpoch() == epoch, generation == revision else { return }
            message = CommunityRules.failureText(error)
        }
    }

    func accept(_ detail: TripPostDetail) {
        let post = CommunityPost(detail: detail)
        let known = posts.contains { $0.id == post.id }
        let knownMine = myPosts.contains { $0.id == post.id }
        posts.removeAll { $0.id == post.id }
        posts.insert(post, at: 0)
        if post.isMine {
            myPosts.removeAll { $0.id == post.id }; myPosts.insert(post, at: 0)
        }
        if !known {
            total += 1
        }
        if post.isMine, !knownMine {
            mineTotal += 1
        }
    }

    func clearMyPosts() {
        myPosts = []; mineTotal = 0
    }

    func detail(_ post: CommunityPost) async throws -> CommunityPost {
        guard let id = post.serverId else { return post }
        let epoch = accountEpoch()
        let detail = try await client.detail(id)
        guard epoch == accountEpoch() else { throw CancellationError() }
        return CommunityPost(detail: detail)
    }

    func remove(_ post: CommunityPost) async {
        guard post.isMine, let id = post.serverId else { return }
        let epoch = accountEpoch()
        do {
            try await client.delete(id)
            guard epoch == accountEpoch() else { return }
            posts.removeAll { $0.serverId == id }
            myPosts.removeAll { $0.serverId == id }
            total = max(0, total - 1)
            mineTotal = max(0, mineTotal - 1)
        } catch {
            message = CommunityRules.failureText(error)
        }
    }

    func saveCourse(_ post: CommunityPost) async throws -> Int64 {
        guard let id = post.serverId else { throw URLError(.unsupportedURL) }
        let epoch = accountEpoch()
        let course = try await client.saveCourse(id)
        guard epoch == accountEpoch() else { throw CancellationError() }
        return course.id
    }

    // MARK: 사진 파일

    /// 사진을 두는 폴더 — 앱 폴더 안의 `SceneTrip/community`.
    static var photoFolder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("SceneTrip/community", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func photoURL(_ name: String) -> URL {
        photoFolder.appendingPathComponent(name)
    }

    static func photo(_ name: String) -> UIImage? {
        UIImage(contentsOfFile: photoURL(name).path)
    }
}
