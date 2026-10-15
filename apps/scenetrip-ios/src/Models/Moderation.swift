import Foundation
import SceneApiClient

/// 신고·차단할 글 (MZ2AZ-461, 서버 MZ2AZ-438). 계약은 **글을 기준으로** 받는다 — 앱에는 사용자 번호가 없고
/// 닉네임은 바뀐다. 차단도 「이 글의 작성자」 를 차단한다.
enum ModerationTarget: Hashable, Identifiable {
    case review(Int64)
    case post(Int64)

    var id: String {
        switch self {
        case let .review(id): "review-\(id)"
        case let .post(id): "post-\(id)"
        }
    }

    var type: ReportTargetType {
        switch self {
        case .review: .review
        case .post: .post
        }
    }

    var targetId: Int64 {
        switch self {
        case let .review(id), let .post(id): id
        }
    }
}

/// 신고·차단·금칙어의 규칙 — 값만 보는 순수 함수다(App Store 심사 지침 1.2).
///
/// 오류는 **서버 문장이 아니라 code 로 가른다**(MZ2AZ-345) — 계약이 `ApiError.message` 를 화면 문구가 아니라고 못 박았다.
enum ModerationRules {
    /// 신고 이유 — 화면에 이 순서로 보인다. 계약 `ReportReason` 의 순서 그대로다.
    static let reasons: [ReportReason] = [.spam, .abuse, .sexual, .personalInfo, .inappropriateNickname, .other]

    /// 덧붙이는 설명의 최대 길이(계약 `ReportCreate.detail` maxLength).
    static let detailLimit = 500

    static func label(_ reason: ReportReason) -> String {
        switch reason {
        case .spam: tr("스팸·광고")
        case .abuse: tr("욕설·혐오·괴롭힘")
        case .sexual: tr("성적인 내용")
        case .personalInfo: tr("개인정보 노출")
        case .inappropriateNickname: tr("닉네임이 부적절함")
        case .other: tr("기타")
        }
    }

    /// 보낼 설명 — 앞뒤 빈칸을 지우고, 비었으면 보내지 않고, 길면 자른다.
    static func detail(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(detailLimit))
    }

    /// 「⋯」 메뉴를 둘 글인가 — 자기 글에는 두지 않는다(보내면 `CANNOT_REPORT_OWN`). 서버에 없는 기기 옛 글도 아니다.
    static func showsMenu(isMine: Bool, onServer: Bool = true) -> Bool {
        !isMine && onServer
    }

    /// 차단을 둘 글인가 — 자기 글이 아니고 작성자가 있어야 한다(탈퇴한 작성자는 `BLOCK_TARGET_HAS_NO_AUTHOR`).
    static func canBlock(isMine: Bool, hasAuthor: Bool) -> Bool {
        !isMine && hasAuthor
    }

    // MARK: 신고 결과

    enum ReportOutcome: Equatable {
        /// 접수됐다 — 같은 글을 다시 신고해도 이것이다(서버 204).
        case received
        /// 자기 글이다(`CANNOT_REPORT_OWN`).
        case own
        /// 글이 없거나 이미 내려갔다(`REPORT_TARGET_NOT_FOUND`).
        case gone
        case rateLimited
        case failed

        /// 시트를 닫아도 되는가 — 다시 보내도 같은 결과면 닫는다. 실패·한도는 남겨 다시 보내게 한다.
        var finishes: Bool {
            switch self {
            case .received, .own, .gone: true
            case .rateLimited, .failed: false
            }
        }
    }

    static func reportOutcome(_ error: Error?) -> ReportOutcome {
        guard let error else { return .received }
        guard case let ErrorResponse.error(status, data, _, _) = error else { return .failed }
        switch AuthRules.apiCode(from: data) {
        case "CANNOT_REPORT_OWN": return .own
        case "REPORT_TARGET_NOT_FOUND": return .gone
        case "RATE_LIMITED": return .rateLimited
        default: return status == 429 ? .rateLimited : .failed
        }
    }

    static func message(_ outcome: ReportOutcome) -> String {
        switch outcome {
        case .received: tr("신고가 접수됐어요. 24시간 안에 확인할게요.")
        case .own: tr("내 글은 신고할 수 없어요")
        case .gone: tr("이미 내려갔거나 없는 글이에요")
        case .rateLimited: tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
        case .failed: tr("신고를 보내지 못했어요. 잠시 뒤 다시 해 주세요")
        }
    }

    // MARK: 차단 결과

    enum BlockOutcome: Equatable {
        /// 차단했다 — 이미 차단한 사람이어도 이것이다(서버 200).
        case blocked
        /// 비회원이다(`401 SIGN_IN_REQUIRED`) — 로그인 화면으로.
        case signInRequired
        /// 자기 글이다(`CANNOT_BLOCK_SELF`).
        case own
        /// 작성자가 탈퇴했다(`BLOCK_TARGET_HAS_NO_AUTHOR`).
        case noAuthor
        /// 글이 없거나 이미 내려갔다(`BLOCK_TARGET_NOT_FOUND`).
        case gone
        case failed
    }

    static func blockOutcome(_ error: Error?) -> BlockOutcome {
        guard let error else { return .blocked }
        guard case let ErrorResponse.error(status, data, _, _) = error else { return .failed }
        let code = AuthRules.apiCode(from: data)
        if AuthRules.action(status: status, code: code) == .promptSignIn {
            return .signInRequired
        }
        switch code {
        case "CANNOT_BLOCK_SELF": return .own
        case "BLOCK_TARGET_HAS_NO_AUTHOR": return .noAuthor
        case "BLOCK_TARGET_NOT_FOUND": return .gone
        default: return .failed
        }
    }

    /// 차단하지 못했을 때의 안내. 차단했으면 글이 바로 사라지는 것이 안내다 — 문구를 따로 띄우지 않는다.
    static func message(_ outcome: BlockOutcome) -> String? {
        switch outcome {
        case .blocked, .signInRequired: nil
        case .own: tr("내 글의 작성자는 차단할 수 없어요")
        case .noAuthor: tr("탈퇴한 사용자는 차단할 수 없어요")
        case .gone: tr("이미 내려갔거나 없는 글이에요")
        case .failed: tr("차단하지 못했어요. 잠시 뒤 다시 해 주세요")
        }
    }

    // MARK: 금칙어

    /// 리뷰·여행후기·닉네임·코스 이름 저장이 금칙어에 걸렸다(`400 TEXT_NOT_ALLOWED`).
    static func isTextNotAllowed(_ error: Error) -> Bool {
        guard case let ErrorResponse.error(_, data, _, _) = error else { return false }
        return isTextNotAllowed(code: AuthRules.apiCode(from: data))
    }

    static func isTextNotAllowed(code: String?) -> Bool {
        code == "TEXT_NOT_ALLOWED"
    }

    /// 어떤 말이 걸렸는지는 서버가 알려 주지 않는다 — 고쳐 달라고만 한다.
    static var textNotAllowedMessage: String {
        tr("쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.")
    }
}

/// 신고·차단 창구 — 생성 클라이언트(`ModerationAPI`)만 부른다. 시험에서 가짜로 바꾼다.
@MainActor
protocol ModerationClient {
    func report(_ target: ModerationTarget, reason: ReportReason, detail: String?) async throws
    func block(_ target: ModerationTarget) async throws -> BlockedUser
    func blocks() async throws -> [BlockedUser]
    func unblock(_ blockId: Int64) async throws
}

@MainActor
struct ModerationServerClient: ModerationClient {
    /// 비회원도 신고한다 — 설치 id 로 찾은 계정이 신고한 사람이 된다. 로그인했으면 공통 장치가 토큰을 싣는다.
    func report(_ target: ModerationTarget, reason: ReportReason, detail: String?) async throws {
        try await ModerationAPI.createReport(
            xInstallId: InstallIdentity.current,
            reportCreate: ReportCreate(
                targetType: target.type, targetId: target.targetId, reason: reason, detail: detail
            )
        )
    }

    func block(_ target: ModerationTarget) async throws -> BlockedUser {
        try await ModerationAPI.createBlock(
            blockCreate: BlockCreate(targetType: target.type, targetId: target.targetId)
        )
    }

    func blocks() async throws -> [BlockedUser] {
        try await ModerationAPI.listMyBlocks().items
    }

    func unblock(_ blockId: Int64) async throws {
        try await ModerationAPI.deleteBlock(blockId: blockId)
    }
}

/// 차단·풀기가 일어났다는 앱 전체 신호 (MZ2AZ-461). 화면마다 받아 둔 리뷰·방문자 사진에 그 사람의 것이 남아 있을 수
/// 있다(검색 탭의 촬영지 상세는 탭을 옮겨도 살아 있다). 리뷰·사진을 쥔 화면은 `onModerationChange` 로 이것을 보고
/// 방문자 사진을 비운 뒤 다시 받는다.
@MainActor
final class ModerationEvents: ObservableObject {
    static let shared = ModerationEvents()

    /// 바뀔 때마다 오른다.
    @Published private(set) var version = 0

    func changed() {
        version += 1
    }
}

/// 신고 시트가 떠 있는가 (MZ2AZ-461). 떠 있는 동안 그 밑의 화면은 로그인 화면을 올리지 않는다 — 덮개 밑에서는
/// 시트가 뜨지 않는다(`signInSheet` 규칙). 신고 중에 세션이 풀리면 신고 시트가 스스로 닫히고, 닫힌 뒤 밑의 화면이 올린다.
@MainActor
final class ReportSheetPresence: ObservableObject {
    static let shared = ReportSheetPresence()

    @Published private(set) var open = false

    func appeared(signInRequested: Bool, dismiss: () -> Void) {
        open = true
        signInChanged(signInRequested, dismiss: dismiss)
    }

    func signInChanged(_ requested: Bool, dismiss: () -> Void) {
        if requested {
            dismiss()
        }
    }

    func disappeared() {
        open = false
    }
}

/// 목록 화면 하나가 들고, 줄의 「⋯」 가 부른다 — 신고 시트·차단 확인 창·안내를 화면 한 곳에 단다
/// (`moderationSheets`). 지연 목록의 줄마다 시트를 달면 줄이 사라질 때 같이 사라진다.
@MainActor
final class ModerationController: ObservableObject {
    /// 신고 시트에 띄운 글.
    @Published var reporting: ModerationTarget?
    /// 차단 확인 창에 띄운 글.
    @Published var confirmingBlock: ModerationTarget?
    /// 차단하지 못한 까닭.
    @Published var notice: String?
    @Published private(set) var sending = false

    private let client: ModerationClient
    private let signedIn: @MainActor () -> Bool
    private let promptSignIn: @MainActor () -> Void
    private let events: ModerationEvents

    init(
        client: ModerationClient? = nil,
        signedIn: (@MainActor () -> Bool)? = nil,
        promptSignIn: (@MainActor () -> Void)? = nil,
        events: ModerationEvents? = nil
    ) {
        self.client = client ?? ModerationServerClient()
        self.signedIn = signedIn ?? { AuthStore.shared.signedIn }
        self.promptSignIn = promptSignIn ?? { AuthStore.shared.promptSignIn() }
        self.events = events ?? .shared
    }

    func askReport(_ target: ModerationTarget) {
        reporting = target
    }

    /// 차단은 가입한 사람만 — 비회원이면 확인 창 대신 로그인 화면을 올린다.
    func askBlock(_ target: ModerationTarget) {
        guard signedIn() else {
            promptSignIn()
            return
        }
        confirmingBlock = target
    }

    func report(
        _ target: ModerationTarget, reason: ReportReason, detail: String
    ) async -> ModerationRules.ReportOutcome {
        guard !sending else { return .failed }
        sending = true
        defer { sending = false }
        do {
            try await client.report(target, reason: reason, detail: ModerationRules.detail(detail))
            return .received
        } catch {
            return ModerationRules.reportOutcome(error)
        }
    }

    /// 차단했으면 `true` — 부른 화면이 그 글을 지우고 목록을 다시 받는다.
    @discardableResult
    func block(_ target: ModerationTarget) async -> Bool {
        confirmingBlock = nil
        guard signedIn() else {
            promptSignIn()
            return false
        }
        guard !sending else { return false }
        sending = true
        defer { sending = false }
        let outcome: ModerationRules.BlockOutcome
        do {
            _ = try await client.block(target)
            outcome = .blocked
        } catch {
            outcome = ModerationRules.blockOutcome(error)
        }
        if outcome == .signInRequired {
            promptSignIn()
        }
        notice = ModerationRules.message(outcome)
        if outcome == .blocked {
            events.changed()
        }
        return outcome == .blocked
    }
}

/// 마이페이지 「차단한 사용자」 — 목록 받기와 풀기.
@MainActor
final class BlockedUsersStore: ObservableObject {
    enum Phase { case loading, loaded, failed }

    @Published private(set) var items: [BlockedUser] = []
    @Published private(set) var phase: Phase = .loading
    /// 풀고 있는 줄 — 단추를 잠근다.
    @Published private(set) var unblocking: Set<Int64> = []
    @Published var message: String?

    private let client: ModerationClient
    private let events: ModerationEvents
    /// 이 화면에서 푼 줄을 푼 차례대로 — 풀기 전에 떠난 목록 응답이 늦게 와서 푼 줄을 되살리지 않게 한다.
    private var unblockedLog: [Int64] = []

    init(client: ModerationClient? = nil, events: ModerationEvents? = nil) {
        self.client = client ?? ModerationServerClient()
        self.events = events ?? .shared
    }

    func reload() async {
        if items.isEmpty {
            phase = .loading
        }
        let mark = unblockedLog.count
        do {
            let fresh = try await client.blocks()
            guard !Task.isCancelled else { return }
            // 받는 사이 푼 줄은 늦은 응답에 남아 있어도 빼 둔다.
            let gone = Set(unblockedLog[mark...])
            items = fresh.filter { !gone.contains($0.id) }
            phase = .loaded
        } catch {
            guard !Task.isCancelled else { return }
            // 받아 둔 목록이 있으면 그대로 둔다.
            if phase != .loaded {
                phase = .failed
            }
        }
    }

    /// 풀었으면 `true`. 이미 없는 줄(`BLOCK_NOT_FOUND`)도 풀린 것이다 — 목록에서 뺀다.
    @discardableResult
    func unblock(_ user: BlockedUser) async -> Bool {
        guard !unblocking.contains(user.id) else { return false }
        unblocking.insert(user.id)
        defer { unblocking.remove(user.id) }
        do {
            try await client.unblock(user.id)
        } catch let ErrorResponse.error(_, data, _, _) where AuthRules.apiCode(from: data) == "BLOCK_NOT_FOUND" {
            // 다른 기기에서 이미 풀었다.
        } catch {
            message = tr("차단을 풀지 못했어요. 잠시 뒤 다시 해 주세요")
            return false
        }
        unblockedLog.append(user.id)
        items.removeAll { $0.id == user.id }
        events.changed()
        return true
    }
}
