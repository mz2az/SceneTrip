import Foundation
import SceneApiClient

/// 멱등 키 없는 POST의 응답을 잃으면 같은 초안을 다시 보내지 않는다.
@MainActor
final class CommunitySubmission: ObservableObject {
    @Published private(set) var working = false
    @Published private(set) var uncertain = false
    @Published private(set) var message: String?
    @Published private(set) var photoInvalid = false
    @Published private(set) var completed = false
    private let client: CommunityClient
    private let accountEpoch: @MainActor () -> Int
    private var submitted: TripPostCreate?
    private var expectedCourse: PostCourse?
    private var baseline: Set<Int64> = []
    private var submittedEpoch = 0
    private var submittedAt = Date.distantPast

    init(client: CommunityClient? = nil, accountEpoch: (@MainActor () -> Int)? = nil) {
        self.client = client ?? CommunityServerClient()
        self.accountEpoch = accountEpoch ?? { AuthStore.shared.epoch }
    }

    func submit(_ input: TripPostCreate, expectedCourse: PostCourse? = nil) async -> TripPostDetail? {
        guard !working, !uncertain, !completed, CommunityRules.canPost(title: input.title, body: input.body, photoCount: input.photoKeys.count) else { return nil }
        working = true
        message = nil
        photoInvalid = false
        defer { working = false }
        submittedEpoch = accountEpoch()
        // 옛 글을 이번 게시 성공으로 잘못 판정하지 않도록 전송 전 목록을 받는다.
        do {
            baseline = try await Set(client.list(mine: true, limit: 100, offset: 0).items.map(\.id))
        } catch {
            message = CommunityRules.failureText(error)
            return nil
        }
        guard accountEpoch() == submittedEpoch else { return nil }
        submitted = input
        self.expectedCourse = expectedCourse
        submittedAt = Date()
        do {
            let saved = try await client.create(input)
            guard accountEpoch() == submittedEpoch else { return nil }
            completed = true
            return saved
        } catch {
            guard accountEpoch() == submittedEpoch else { return nil }
            uncertain = CommunityRules.uncertain(error)
            if case let ErrorResponse.error(_, data, _, _) = error {
                photoInvalid = AuthRules.apiCode(from: data) == "POST_PHOTO_INVALID"
            }
            message = uncertain ? tr("게시 응답을 받지 못했어요. 중복 게시를 막기 위해 게시 여부를 확인해 주세요") : CommunityRules.failureText(error)
            return nil
        }
    }

    func check() async -> TripPostDetail? {
        guard uncertain, !working, !completed, let submitted, accountEpoch() == submittedEpoch else { return nil }
        guard !submitted.photoKeys.isEmpty else {
            message = tr("사진 없는 글은 게시 여부를 확정할 수 없어요. 내 글 목록에서 확인해 주세요. 중복을 막기 위해 다시 보내지 않습니다")
            return nil
        }
        working = true
        defer { working = false }
        do {
            let page = try await client.list(mine: true, limit: 100, offset: 0)
            let candidates = page.items.filter { !baseline.contains($0.id) && $0.createdAt >= submittedAt.addingTimeInterval(-5) && $0.title == submitted.title }
            var matches: [TripPostDetail] = []
            for candidate in candidates {
                let detail = try await client.detail(candidate.id)
                if CommunityRules.matches(detail, input: submitted, expectedCourse: expectedCourse) {
                    matches.append(detail)
                }
            }
            guard accountEpoch() == submittedEpoch else { return nil }
            if matches.count == 1 {
                completed = true; return matches[0]
            }
            message = tr("게시 여부를 아직 확인하지 못했어요. 잠시 뒤 다시 확인해 주세요. 초안은 그대로 있습니다")
        } catch {
            message = CommunityRules.failureText(error)
        }
        return nil
    }
}
