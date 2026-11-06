import AppTrackingTransparency
import Foundation

/// 앱이 활성으로 처음 보인 뒤 ATT를 한 번 요청한다(MZ2AZ-402, 시점은 MZ2AZ-526).
/// 첫 실행 사용법이 없어졌으므로 사용법을 봤는지는 보지 않는다 — 예전 조건이었다면 이제 아무도 묻지 않게 된다.
/// 응답의 정본은 시스템이다. 별도 영구 플래그로 미결정 상태를 덮어쓰지 않는다.
@MainActor
final class TrackingAuthorization {
    typealias Status = ATTrackingManager.AuthorizationStatus
    typealias Completion = @MainActor (Status) -> Void

    static let shared = TrackingAuthorization(
        status: { ATTrackingManager.trackingAuthorizationStatus },
        request: { completion in
            ATTrackingManager.requestTrackingAuthorization { status in
                Task { @MainActor in completion(status) }
            }
        },
        activateMeta: { MetaAnalytics.shared.activate() }
    )

    private let status: () -> Status
    private let request: (@escaping Completion) -> Void
    private let activateMeta: () -> Void
    private var requestedThisLaunch = false

    init(
        status: @escaping () -> Status,
        request: @escaping (@escaping Completion) -> Void,
        activateMeta: @escaping () -> Void
    ) {
        self.status = status
        self.request = request
        self.activateMeta = activateMeta
    }

    /// 첫 화면이 자리 잡을 때까지 기다리는 시간. 앱이 뜨자마자 시스템 창을 얹지 않는다.
    static let settleDelay: Duration = .milliseconds(600)

    /// 활성 화면에서 `settleDelay` 를 기다린 뒤 묻는다. 기다리는 사이 백그라운드로 가면(작업 취소 또는
    /// `isActive` 거짓) 묻지 않고, 다음에 활성이 되어 다시 부르면 처음부터 기다린다.
    /// `sleep` 은 시험이 바꿔 끼운다.
    func requestWhenSettled(
        isActive: @MainActor () -> Bool,
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) async {
        do {
            try await sleep(Self.settleDelay)
        } catch { return }
        guard !Task.isCancelled else { return }
        requestIfNeeded(isActive: isActive())
    }

    func requestIfNeeded(isActive: Bool) {
        guard isActive, !requestedThisLaunch, status() == .notDetermined else { return }
        // 요청이 앱을 inactive로 만들었다가 active로 돌려도 중복해서 묻지 않는다.
        requestedThisLaunch = true
        request { [weak self] _ in
            // Meta가 최신 ATT·통계·설정 파일을 다시 검사한다. 거부/제한이면 시작하지 않는다.
            self?.activateMeta()
        }
    }
}
