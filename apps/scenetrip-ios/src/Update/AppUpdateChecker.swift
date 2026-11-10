import SceneApiClient
import SwiftUI

/// 앱이 앞으로 올 때마다 서버에 최소·최신 버전을 묻고 띄울 것을 정한다 (MZ2AZ-546).
///
/// 서버를 못 부르면(오프라인·옛 서버) 아무것도 띄우지 않는다 — 업데이트 확인 때문에 앱을 막지 않는다.
/// 「나중에」를 누른 최신 버전은 기기에 기억해 그 버전에는 다시 묻지 않는다. 강제는 기억과 상관없이 뜬다.
@MainActor
final class AppUpdateChecker: ObservableObject {
    static let shared = AppUpdateChecker()

    @Published private(set) var decision: AppUpdateRules.Decision = .none

    private static let dismissedKey = "appUpdate.dismissedLatest"
    /// 서버가 스토어 주소를 주지 않을 때 — App Store 의 이 앱 페이지(번호 6823022207). 서버 설정의 `store-url` 이 있으면 그것이 먼저다.
    static let fallbackStore = URL(string: "itms-apps://apps.apple.com/app/id6823022207")!

    private var checking = false

    /// 설치된 버전 — Info.plist 의 `CFBundleShortVersionString`(출시 빌드는 패키징 때 넣는다).
    var installed: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// 개발용 빌드의 표시 버전 — 출시 빌드만 패키징 때 진짜 버전을 넣는다(deploy/README.md). 이 값이면 확인하지 않는다 —
    /// 그러지 않으면 시뮬레이터·개발 기기가 서버 minimum(1.0.0 이상)에 모두 막힌다.
    static let developmentVersion = "0.1.0"

    func check() async {
        guard installed != Self.developmentVersion else { return }
        guard !checking else { return }
        checking = true
        defer { checking = false }
        guard let versions = try? await AppAPI.getAppVersions() else { return }
        let rule = AppUpdateRules.Rule(
            minimum: versions.ios.minimum,
            latest: versions.ios.latest,
            storeURL: versions.ios.storeUrl.flatMap(URL.init(string:))
        )
        decision = AppUpdateRules.decide(
            installed: installed,
            rule: rule,
            dismissedLatest: UserDefaults.standard.string(forKey: Self.dismissedKey),
            fallbackStore: Self.fallbackStore
        )
    }

    /// 권장 창의 「나중에」 — 그 최신 버전은 다시 묻지 않는다.
    func later() {
        if case let .recommended(latest, _) = decision {
            UserDefaults.standard.set(latest, forKey: Self.dismissedKey)
        }
        decision = .none
    }

    func openStore(_ url: URL) {
        UIApplication.shared.open(url)
    }
}

/// 강제 업데이트 — 앱 전체를 덮고 닫을 수 없다. 스토어에서 돌아와도 여전히 낮으면 그대로 남는다.
struct RequiredUpdateCover: View {
    let store: URL
    let onUpdate: (URL) -> Void

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "arrow.down.app.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                Text("업데이트가 필요해요")
                    .font(.title2.bold())
                Text("새 버전으로 업데이트해야 계속 쓸 수 있어요.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button {
                    onUpdate(store)
                } label: {
                    Text("업데이트")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
            }
            .padding(.horizontal, 32)
        }
        .accessibilityAddTraits(.isModal)
    }
}
