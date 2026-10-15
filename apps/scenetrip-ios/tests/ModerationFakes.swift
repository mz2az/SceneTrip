import SceneApiClient
@testable import SceneTrip
import XCTest

// 신고·차단 시험의 가짜와 표본 (MZ2AZ-461). `ModerationTests` 가 쓴다.

// MARK: 가짜

func moderationError(_ status: Int, _ code: String) -> Error {
    let body = Data(#"{"code":"\#(code)","message":"시험"}"#.utf8)
    return ErrorResponse.error(status, body, nil, URLError(.badServerResponse))
}

func sampleBlocked(_ id: Int64, _ nickname: String) -> BlockedUser {
    BlockedUser(id: id, nickname: nickname, blockedAt: Date(timeIntervalSince1970: 1))
}

func sampleModeratedReview(_ id: Int64) -> Review {
    Review(id: id, rating: 4, body: "시험 리뷰", photos: [], author: ReviewAuthor(nickname: "글쓴이\(id)"),
           createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 1), isMine: false)
}

@MainActor
final class FakeModerationClient: ModerationClient {
    struct Sent: Equatable {
        let target: ModerationTarget
        let reason: ReportReason
        let detail: String?
    }

    var reports: [Sent] = []
    var blocked: [ModerationTarget] = []
    var unblocked: [Int64] = []
    var list: [BlockedUser] = []
    var reportError: Error?
    var blockError: Error?
    var listError: Error?
    /// 목록 응답을 붙잡아 둔다 — 늦게 오는 응답을 흉내 낸다.
    var blocksGate: (() async -> Void)?
    var unblockError: Error?

    func report(_ target: ModerationTarget, reason: ReportReason, detail: String?) async throws {
        if let reportError {
            throw reportError
        }
        reports.append(Sent(target: target, reason: reason, detail: detail))
    }

    func block(_ target: ModerationTarget) async throws -> BlockedUser {
        if let blockError {
            throw blockError
        }
        blocked.append(target)
        return sampleBlocked(Int64(blocked.count), "차단됨")
    }

    func blocks() async throws -> [BlockedUser] {
        let snapshot = list
        if let gate = blocksGate {
            blocksGate = nil
            await gate()
        }
        if let listError {
            throw listError
        }
        return snapshot
    }

    func unblock(_ blockId: Int64) async throws {
        if let unblockError {
            throw unblockError
        }
        unblocked.append(blockId)
        list.removeAll { $0.id == blockId }
    }
}

@MainActor
final class FeedFake: CommunityClient {
    func list(mine _: Bool, limit _: Int, offset _: Int) async throws -> TripPostList {
        let items = [42, 43].map {
            TripPostSummary(id: Int64($0), title: "t", excerpt: "e", createdAt: Date(), photoCount: 0, isMine: false)
        }
        return TripPostList(items: items, total: items.count)
    }

    func detail(_: Int64) async throws -> TripPostDetail {
        throw URLError(.unsupportedURL)
    }

    func create(_: TripPostCreate) async throws -> TripPostDetail {
        throw URLError(.unsupportedURL)
    }

    func delete(_: Int64) async throws {}

    func saveCourse(_: Int64) async throws -> CourseDetail {
        throw URLError(.unsupportedURL)
    }
}

/// 첫 목록 받기는 붙잡아 두고(차단 전의 응답 — 42 가 있다), 그 뒤 받기는 바로 답한다(서버가 42 를 뺐다).
@MainActor
final class DelayedFeed: CommunityClient {
    var calls = 0
    var offsets: [Int] = []
    var onFirst: (() -> Void)?
    private var held: CheckedContinuation<Void, Never>?

    func release() {
        held?.resume()
        held = nil
    }

    func list(mine _: Bool, limit _: Int, offset: Int) async throws -> TripPostList {
        offsets.append(offset)
        calls += 1
        let ids: [Int64]
        if calls == 1 {
            ids = [42, 43]
            await withCheckedContinuation { held = $0; onFirst?() }
        } else {
            ids = [43]
        }
        let items = ids.map {
            TripPostSummary(id: $0, title: "t", excerpt: "e", createdAt: Date(), photoCount: 0, isMine: false)
        }
        return TripPostList(items: items, total: items.count)
    }

    func detail(_: Int64) async throws -> TripPostDetail {
        throw URLError(.unsupportedURL)
    }

    func create(_: TripPostCreate) async throws -> TripPostDetail {
        throw URLError(.unsupportedURL)
    }

    func delete(_: Int64) async throws {}

    func saveCourse(_: Int64) async throws -> CourseDetail {
        throw URLError(.unsupportedURL)
    }
}
