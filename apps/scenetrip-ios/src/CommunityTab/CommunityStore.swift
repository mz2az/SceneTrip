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
        /// 촬영지 id. 지도에 직접 찍은 핀이면 없다.
        var placeId: Int64?
        var name: String
        var address: String?
        var type: String?
        var latitude: Double
        var longitude: Double
        var imageUrl: String?
    }

    var title: String
    var days: [[Stop]]

    var placeCount: Int {
        days.reduce(0) { $0 + $1.count }
    }

    init(title: String, days: [[Stop]]) {
        self.title = title
        self.days = days
    }

    init(from course: RouteCourse) {
        title = course.title
        days = course.days.map { day in
            day.stops.map { stop in
                Stop(
                    placeId: stop.isPinned ? nil : stop.place.id,
                    name: stop.place.name, address: stop.place.address, type: stop.place.type,
                    latitude: stop.place.latitude, longitude: stop.place.longitude,
                    imageUrl: stop.place.imageUrl
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
                    RouteStop(
                        place: PlaceSummary(
                            id: stop.placeId ?? -Int64(index + 1), name: stop.name, type: stop.type,
                            address: stop.address, latitude: stop.latitude, longitude: stop.longitude,
                            imageUrl: stop.imageUrl
                        ),
                        isPinned: stop.placeId == nil
                    )
                })
            }
        )
    }
}

/// 커뮤니티 게시글 — **임시판의 자료 모양** (2026-08-28, 여행후기로 재편 2026-10-05).
///
/// 게시판 서버는 아직 없다. 그래서 글과 사진은 **기기에만** 저장한다(글은 `UserDefaults`,
/// 사진은 앱 폴더의 파일). 서버가 서면 이 저장소를 API 클라이언트로 갈아 끼우고 모양은 그대로 간다.
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

    /// 글쓴이 이름. **없으면 내가 쓴 글이다.** 게시판 서버가 없어 남의 글은 시험용으로만 들어온다.
    var author: String?

    var isMine: Bool {
        author == nil
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

    private let key = "scenetrip.communityPosts"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([CommunityPost].self, from: data)
        {
            posts = saved
        }
    }

    /// 내가 쓴 글만 — 마이페이지의 「내가 쓴 글」.
    var mine: [CommunityPost] {
        posts.filter(\.isMine)
    }

    func add(title: String, body: String, photos: [UIImage], course: PostCourse?) {
        let names = photos.compactMap(Self.store(photo:))
        AppAnalytics.log(.postReview(photoCount: names.count, hasCourse: course != nil))
        posts.insert(
            CommunityPost(
                id: UUID(), board: .review, title: title, body: body, createdAt: Date(),
                courseTitle: course?.title, photos: names.isEmpty ? nil : names, course: course
            ),
            at: 0
        )
        persist()
    }

    func remove(_ post: CommunityPost) {
        for name in post.photos ?? [] {
            try? FileManager.default.removeItem(at: Self.photoURL(name))
        }
        posts.removeAll { $0.id == post.id }
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(posts) {
            UserDefaults.standard.set(data, forKey: key)
        }
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

    /// 긴 변 1600 으로 줄여 JPEG 로 둔다 — 폰 사진 원본은 장당 수 MB 다.
    private static func store(photo: UIImage) -> String? {
        let longest = max(photo.size.width, photo.size.height)
        let scale = min(1, 1600 / max(longest, 1))
        let size = CGSize(width: photo.size.width * scale, height: photo.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            photo.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let data = resized.jpegData(compressionQuality: 0.82) else { return nil }
        let name = UUID().uuidString + ".jpg"
        do {
            try data.write(to: photoURL(name), options: .atomic)
            return name
        } catch {
            return nil
        }
    }
}
