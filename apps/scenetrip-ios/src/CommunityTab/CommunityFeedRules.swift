import Foundation
import SceneApiClient

/// 커뮤니티 목록의 순수 규칙 — 게시판·검색·끊김 없는 목록·빈 화면·좋아요/댓글 수 (MZ2AZ-547 P8, 웹 `ProtoCommunity.tsx`·`CommunityTab.tsx`).
/// 화면 없이 시험한다.
enum CommunityFeedRules {
    /// 게시판 탭 순서 — 여행후기 · 자유게시판.
    static let boards: [TripPostBoard] = [.review, .free]

    /// 서버가 게시판을 주지 않은 글(옛 서버·기기 옛 글)은 여행후기다.
    static func board(_ raw: TripPostBoard?) -> TripPostBoard {
        raw ?? .review
    }

    /// 게시판 이름의 번역 열쇠(한국어 원문) — 탭·글 화면 머리·글쓰기 칩이 같이 쓴다. 여행후기 / 자유게시판.
    static func boardLabelKey(_ board: TripPostBoard) -> String {
        switch board {
        case .review: "여행후기"
        case .free: "자유게시판"
        }
    }

    /// 검색어는 두 글자부터 보낸다.
    static let searchMinLength = 2
    /// 입력이 이만큼(0.35초) 멈추면 보낸다.
    static let searchDelay: Duration = .milliseconds(350)

    /// 보낼 검색어 — 앞뒤 빈칸을 떼고 두 글자(문자 단위) 미만이면 nil. 검색 중이 아니면(nil) nil.
    static func searchTerm(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let term = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return term.count >= searchMinLength ? term : nil
    }

    /// 조건을 바꾼 뒤 보내기까지 기다릴 시간 — 검색어가 있으면 `searchDelay`, 없으면 바로(.zero).
    static func delay(for term: String?) -> Duration {
        term == nil ? .zero : searchDelay
    }

    /// 다음 쪽을 받는 방법. 첫 쪽이면 커서 없이 0. 더 받기면 서버가 준 커서가 있으면 커서(건너뛰기 0),
    /// 없으면 받은 줄 수만큼 건너뛰기(옛 길).
    static func page(more: Bool, nextCursor: String?, loaded: Int) -> (cursor: String?, offset: Int) {
        guard more else { return (nil, 0) }
        if let nextCursor {
            return (nextCursor, 0)
        }
        return (nil, loaded)
    }

    /// 끝에서 몇 줄 앞에 닿으면 다음 쪽을 받는가.
    static let prefetchDistance = 5

    /// 줄 하나가 화면에 들어왔다 — 다음 쪽을 받을까. 그 줄이 끝에서 `prefetchDistance` 줄 안
    /// (appearedIndex >= loaded - prefetchDistance)이고, 더 있고(loaded < total), 받는 중이 아니고, 실패 문구가 떠 있지 않을 때.
    static func shouldLoadMore(appearedIndex: Int, loaded: Int, total: Int, loading: Bool, failed: Bool) -> Bool {
        !loading && !failed && loaded < total && appearedIndex >= loaded - prefetchDistance
    }

    enum EmptyState: Equatable {
        /// 「「q」 에 맞는 글이 없어요」
        case noMatch(String)
        /// 자유게시판 — 「아직 글이 없어요 / 여행 이야기·질문을 자유롭게 남겨 보세요」
        case freeBoard
        /// 여행후기 — 「아직 후기가 없습니다」
        case reviews
    }

    /// 목록이 비었을 때 보일 것. 받는 중이거나 실패 문구가 있거나 보일 글이 있으면 nil.
    /// 검색 중(term 이 nil 아님)이면 기기 옛 글과 무관하게 서버 글이 0 일 때 noMatch(term).
    /// 검색 중이 아니면 서버 글·옛 글이 모두 0 일 때 게시판에 따라 freeBoard / reviews.
    static func emptyState(board: TripPostBoard, term: String?, posts: Int, legacy: Int, loading: Bool, failed: Bool) -> EmptyState? {
        guard !loading, !failed, posts == 0 else { return nil }
        if let term {
            return .noMatch(term)
        }
        guard legacy == 0 else { return nil }
        return board == .free ? .freeBoard : .reviews
    }

    /// 목록 줄의 「👍 N 💬 M」. 서버가 수를 둘 다 주지 않았으면 그리지 않는다(nil). 하나만 없으면 그쪽은 0.
    static func counts(likeCount: Int?, commentCount: Int?) -> (likes: Int, comments: Int)? {
        guard likeCount != nil || commentCount != nil else { return nil }
        return (likeCount ?? 0, commentCount ?? 0)
    }

    /// 좋아요를 누른 직후(낙관적) 값 — 뒤집고 수를 ±1. 수는 0 밑으로 내려가지 않는다.
    static func toggledLike(liked: Bool, count: Int) -> (liked: Bool, count: Int) {
        let next = !liked
        return (next, max(0, count + (next ? 1 : -1)))
    }

    static let commentLimit = 500

    /// 보낼 댓글 — 앞뒤 빈칸을 떼고, 비면 nil, 길면 500자(문자 단위)로 자른다.
    static func commentBody(_ raw: String) -> String? {
        let body = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return nil }
        return String(body.prefix(commentLimit))
    }

    /// 댓글 입력칸의 안내 문구 열쇠 — 댓글이 없으면 「첫 댓글을 남겨 보세요」, 있으면 「댓글을 남겨 보세요」.
    static func commentPlaceholderKey(total: Int) -> String {
        total == 0 ? "첫 댓글을 남겨 보세요" : "댓글을 남겨 보세요"
    }

    /// 머리줄 「✎ 글쓰기」를 눌렀을 때 할 일.
    enum ComposeTap: Equatable {
        /// 글쓰기 화면을 연다.
        case open
        /// 글쓰기 화면을 열지 않고 로그인 창만 띄운다.
        case signIn
    }

    /// 「글쓰기」를 누르면 — 로그인했으면 글쓰기 화면을 열고, 아니면 로그인 창만 띄운다(웹 `CommunityTab.tsx` `onCompose`).
    /// 로그인을 마쳐도 글쓰기 화면을 저절로 열지 않는다 — 다시 누른다.
    static func composeTap(signedIn: Bool) -> ComposeTap {
        signedIn ? .open : .signIn
    }
}
