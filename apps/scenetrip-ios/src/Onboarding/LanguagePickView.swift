import SceneApiClient
import SwiftUI

/// 첫 실행의 언어 고르기 (MZ2AZ-343). 사용법보다 먼저 나온다 — 사용법부터 그 언어로 읽어야 한다.
///
/// 이 화면만은 **두 언어를 같이 적는다.** 아직 무엇을 읽을 수 있는지 모르기 때문이다.
struct LanguagePickView: View {
    let onDone: () -> Void

    @ObservedObject private var language = AppLanguage.shared

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            PinoMascot(width: 150)
            Text(verbatim: "언어를 선택하세요")
                .font(.title2.weight(.bold))
                .padding(.top, 28)
            Text(verbatim: "Choose your language")
                .font(.title3)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            Spacer()
            VStack(spacing: 12) {
                ForEach(AppLanguage.choices, id: \.self) { lang in
                    Button {
                        language.choose(lang)
                        onDone()
                    } label: {
                        Text(verbatim: AppLanguage.name(of: lang))
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .foregroundStyle(lang == language.lang ? Color.white : Color.primary)
                            .background(
                                Capsule().fill(lang == language.lang ? Color.accentColor : Color(.systemGray6))
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
