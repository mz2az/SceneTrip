import AppTrackingTransparency
import Foundation

/// 온보딩 뒤 활성 화면에서만 ATT를 한 번 요청한다(MZ2AZ-402).
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

    func requestIfNeeded(onboardingComplete: Bool, isActive: Bool) {
        guard onboardingComplete, isActive, !requestedThisLaunch, status() == .notDetermined else { return }
        // 요청이 앱을 inactive로 만들었다가 active로 돌려도 중복해서 묻지 않는다.
        requestedThisLaunch = true
        request { [weak self] _ in
            // Meta가 최신 ATT·통계·설정 파일을 다시 검사한다. 거부/제한이면 시작하지 않는다.
            self?.activateMeta()
        }
    }
}
