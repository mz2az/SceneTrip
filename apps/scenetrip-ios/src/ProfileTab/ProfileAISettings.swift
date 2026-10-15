import SwiftUI

/// 이용 통계 설정 옆에 두되 AI 동의만 바꾼다.
struct ProfileAISettings: View {
    @AppStorage(AIConsentStore.key) private var consentVersion = 0
    @StateObject private var gate = AIConsentGate()

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { consentVersion == AIConsentStore.currentVersion },
                set: { gate.setEnabled($0) }
            )) {
                HStack(spacing: 12) {
                    Image(systemName: "sparkles")
                        .foregroundStyle(.purple)
                        .frame(width: 26)
                    Text("AI 기능 사용").font(.subheadline)
                }
            }
            .accessibilityIdentifier("ai-features-toggle")
            .aiConsentSheet(gate)
        } footer: {
            Text("끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요.")
        }
    }
}
