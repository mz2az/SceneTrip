import SwiftUI

/// 앱을 열면 보이는 것 — **곧바로 앱**이다 (MZ2AZ-526, 웹 MZ2AZ-523 과 같다).
///
/// 예전에는 `RootTabs` 위에 스플래시 → (처음이면) 언어 고르기 → 사용법 넉 장을 덮개로 얹었다.
/// 광고로 들어온 방문자 724명 중 언어 고르기를 지난 사람이 7명, 사용법을 끝낸 사람이 1명이라
/// 첫 실행 덮개를 모두 걷었다. 시스템 LaunchScreen 은 OS 몫이라 그대로다.
///
/// - 언어: 저장값이 없으면 영어(`AppLanguage.startingLanguage`). 마이페이지에서 바꾼다.
/// - 사용법: 마이페이지 「사용법 다시 보기」에만 남는다(`OnboardingView`).
/// - ATT: 앱이 활성으로 처음 보인 뒤 0.6초에 묻는다(`TrackingAuthorization.requestWhenSettled`).
struct AppRoot: View {
    @ObservedObject private var language = AppLanguage.shared
    @ObservedObject private var update = AppUpdateChecker.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            // 언어가 바뀌면 화면을 통째로 다시 만든다 — 서버 내용도 그 언어로 다시 받아야 한다.
            RootTabs()
                .id(language.lang)
            // 강제 업데이트 — 앱을 통째로 덮는다(MZ2AZ-546).
            if case let .required(store) = update.decision {
                RequiredUpdateCover(store: store, onUpdate: update.openStore)
                    .transition(.opacity)
            }
        }
        // 권장 업데이트 — 그 최신 버전에 한 번(MZ2AZ-546).
        .alert(
            "새 버전이 나왔어요",
            isPresented: Binding(
                get: {
                    if case .recommended = update.decision {
                        true
                    } else {
                        false
                    }
                },
                set: {
                    if !$0 {
                        update.later()
                    }
                }
            )
        ) {
            Button("나중에", role: .cancel) { update.later() }
            Button("업데이트") {
                if case let .recommended(_, store) = update.decision {
                    update.openStore(store)
                }
            }
        } message: {
            Text("업데이트하면 새 기능을 쓸 수 있어요.")
        }
        // SwiftUI 의 글자는 이 로케일로 번역 표를 찾는다. 시트도 물려받는다.
        .environment(\.locale, language.locale)
        .preferredColorScheme(.light)
        // 구글 로그인이 끝나면 구글이 이 앱으로 되돌려 보낸다 (MZ2AZ-336).
        .onOpenURL { _ = GoogleOAuth.handle($0) }
        // 활성이 될 때마다 새로 기다린다. 백그라운드로 가면 id 가 바뀌어 기다림이 취소된다.
        // 같은 실행에서 두 번 묻지 않는 것은 `TrackingAuthorization` 이 지킨다.
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            // 앞으로 올 때마다 최소·최신 버전을 묻는다(MZ2AZ-546). 못 부르면 아무것도 띄우지 않는다.
            await update.check()
            await TrackingAuthorization.shared.requestWhenSettled {
                scenePhase == .active && UIApplication.shared.applicationState == .active
            }
        }
    }
}
