import Foundation

/// 커뮤니티 게시글 — **임시판의 자료 모양** (2026-08-28).
///
/// 게시판 서버는 아직 없다(백엔드 티켓도 없다). 그래서 글은 **기기에만** 저장한다 —
/// `LikeStore` 와 같은 선택이고, 같은 이유로 `UserDefaults` 다(다시 켜도 남아야
/// 한다). 서버가 서면 이 저장소를 API 클라이언트로 갈아 끼우고 모양은 그대로 간다.
struct CommunityPost: Identifiable, Codable {
    /// 글의 갈래. 디시인사이드·카페의 말머리에 해당한다 — 갈래가 없으면
    /// 코스 추천과 맛집 후기가 한 줄에 섞여 둘 다 못 찾는다.
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

    /// 첨부한 내 코스 이름. 코스 추천 글이 코스 없이 올라가는 것을 막지는 않되,
    /// 있으면 배지로 보여 준다.
    var courseTitle: String?
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

    func add(board: CommunityPost.Board, title: String, body: String, courseTitle: String?) {
        posts.insert(
            CommunityPost(
                id: UUID(), board: board, title: title, body: body,
                createdAt: Date(), courseTitle: courseTitle
            ),
            at: 0
        )
        persist()
    }

    func remove(_ post: CommunityPost) {
        posts.removeAll { $0.id == post.id }
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(posts) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}
