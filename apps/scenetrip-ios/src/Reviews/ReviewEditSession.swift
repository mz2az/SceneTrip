import SceneApiClient
import SwiftUI

struct ReviewEditClient {
    let load: () async throws -> Review?
    let save: (Int, String?, [String]) async throws -> Review
    let remove: () async throws -> Void

    static func live(_ subject: ReviewSubject) -> Self {
        Self(load: { try await subject.myReview() },
             save: { try await subject.save(rating: $0, body: $1, photoKeys: $2) },
             remove: { try await subject.deleteMine() })
    }
}

/// MZ2AZ-363: 작성 화면을 연 계정과 요청이 끝날 때의 계정을 함께 확인한다.
@MainActor
final class ReviewEditSession: ObservableObject {
    enum Failure: Error { case accountChanged }

    private let auth: AuthStore
    private let openedEpoch: Int
    private let owner: UUID?
    private let client: ReviewEditClient

    init(subject: ReviewSubject, auth: AuthStore? = nil, client: ReviewEditClient? = nil) {
        let auth = auth ?? .shared
        self.auth = auth
        openedEpoch = auth.epoch
        owner = auth.me?.id
        self.client = client ?? .live(subject)
    }

    var isCurrent: Bool {
        auth.signedIn && auth.epoch == openedEpoch && (owner == nil || auth.me?.id == owner)
    }

    func load() async throws -> Review? {
        try await perform { try await self.client.load() }
    }

    func save(rating: Int, body: String?, photoKeys: [String]) async throws -> Review {
        try await perform { try await self.client.save(rating, body, photoKeys) }
    }

    func remove() async throws {
        try await perform { try await self.client.remove() }
    }

    private func perform<Value>(_ request: () async throws -> Value) async throws -> Value {
        try requireCurrent()
        do {
            let value = try await request()
            try requireCurrent()
            return value
        } catch {
            try requireCurrent()
            throw error
        }
    }

    private func requireCurrent() throws {
        try Task.checkCancellation()
        guard isCurrent else { throw Failure.accountChanged }
    }
}
