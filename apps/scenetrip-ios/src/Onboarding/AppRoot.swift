import SwiftUI

/// 앱을 열었을 때의 순서를 정하는 자리.
///
/// ```
/// 스플래시 ─┬─ (처음이면) 사용법 넉 장 ─┐
///           └───────────────────────────┴─ 앱
/// ```
///
/// ## 진짜 앱은 처음부터 아래에 깔려 있다
///
/// 스플래시를 **덮개로** 얹는다. `RootTabs` 를 나중에 만들면 스플래시 1.6초가 로딩에
/// 더해지지만, 밑에 깔아 두면 그 1.6초 **동안** 지도 인증과 인기 촬영지 호출이 끝난다.
/// 덮개가 걷힐 때 이미 그려져 있는 화면이 나오는 것과, 그때부터 회색 지도가 뜨는 것은
/// 체감이 다르다.
struct AppRoot: View {
    private enum Stage {
        case splash
        /// 언어를 고른 적이 없으면 사용법보다 먼저 묻는다 (MZ2AZ-343).
        case language
        case lessons
        case app
    }

    @State private var stage: Stage = .splash
    @ObservedObject private var language = AppLanguage.shared

    var body: some View {
        ZStack {
            // 언어가 바뀌면 화면을 통째로 다시 만든다 — 서버 내용도 그 언어로 다시 받아야 한다.
            RootTabs()
                .id(language.lang)

            switch stage {
            case .splash:
                SplashView {
                    stage = language.hasChosen ? afterLanguage : .language
                }
                // **들어올 때는 애니메이션이 없어야 한다.** 그냥 `.opacity` 로 두면
                // 앱을 연 첫 0.32초 동안 스플래시가 서서히 나타나면서 밑에 깔린
                // 흰 화면이 비친다(실측). 스플래시는 처음부터 꽉 차 있어야 한다.
                .transition(.asymmetric(insertion: .identity, removal: .opacity))

            case .language:
                LanguagePickView { stage = afterLanguage }
                    .transition(.asymmetric(insertion: .identity, removal: .opacity))

            case .lessons:
                OnboardingView { stage = .app }
                    .transition(.asymmetric(insertion: .identity, removal: .opacity))

            case .app:
                EmptyView()
            }
        }
        .animation(.easeInOut(duration: 0.32), value: stage)
        // SwiftUI 의 글자는 이 로케일로 번역 표를 찾는다. 덮개·시트도 물려받는다.
        .environment(\.locale, language.locale)
        // 구글 로그인이 끝나면 구글이 이 앱으로 되돌려 보낸다 (MZ2AZ-336).
        .onOpenURL { _ = GoogleOAuth.handle($0) }
    }

    private var afterLanguage: Stage {
        OnboardingFlag.hasSeen ? .app : .lessons
    }
}

/// 사용법을 본 적이 있는지.
///
/// ## 판(version)으로 두는 이유
///
/// `true`/`false` 로 두면 튜토리얼 내용을 크게 바꿔도 **이미 깔린 사람은 영영 못 본다.**
/// 판 번호를 올리면 그 사람들에게 한 번 더 보인다. 문구를 다듬는 정도로는 올리지 않고,
/// 장이 늘거나 기능이 바뀌었을 때만 올린다.
enum OnboardingFlag {
    /// `InstallIdentity` 와 같은 `scenetrip.` 접두를 쓴다.
    private static let key = "scenetrip.onboarding.seenVersion"

    /// 1 = 첫 판 (넉 장: 검색 / AI 코스 / 길찾기 / 반경 POI·챗봇).
    static let version = 1

    static var hasSeen: Bool {
        UserDefaults.standard.integer(forKey: key) >= version
    }

    static func markSeen() {
        UserDefaults.standard.set(version, forKey: key)
    }

    /// 마이페이지에서 다시 보기. 시뮬레이터에서 앱을 지웠다 깔지 않고 확인하는 길이기도 하다.
    static func reset() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
