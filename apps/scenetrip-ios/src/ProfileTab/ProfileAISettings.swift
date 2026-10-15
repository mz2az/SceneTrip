import SwiftUI

/// 이용 통계 아래의 AI·위치 동의 설정. 두 동의는 독립적으로 철회한다.
struct ProfileAISettings: View {
    @AppStorage(AIConsentStore.key) private var consentVersion = 0
    @StateObject private var gate = AIConsentGate()

    @AppStorage(LocationConsentStore.key) private var locationVersion = 0
    @StateObject private var locationGate = AIConsentGate(store: LocationConsentStore.shared)

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
        Section {
            Toggle(isOn: Binding(
                get: { locationVersion == LocationConsentStore.currentVersion },
                set: { locationGate.setEnabled($0) }
            )) {
                Label("위치기반서비스 이용", systemImage: "location")
                    .font(.subheadline)
            }
            .accessibilityIdentifier("location-services-toggle")
            .locationConsentSheet(locationGate)
        } footer: {
            Text("끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.")
        }
    }
}
