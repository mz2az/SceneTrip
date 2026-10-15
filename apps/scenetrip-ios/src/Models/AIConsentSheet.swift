import SwiftUI

/// 첫 질문·첫 AI 코스 만들기·설정에서 공통으로 쓰는 안내.
struct AIConsentSheet: View {
    let allow: () -> Void
    let cancel: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("AI 기능 사용 안내")
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text(disclosure)
                        .fixedSize(horizontal: false, vertical: true)
                    Link("개인정보 처리방침", destination: LegalLinks.privacy)
                    Button("허용하고 계속", action: allow)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .accessibilityIdentifier("ai-consent-allow")
                    Button("취소", role: .cancel, action: cancel)
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .accessibilityIdentifier("ai-consent-cancel")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var disclosure: String {
        tr("""
        AI 가이드와 AI 코스 만들기는 입력한 내용, 대화, 관심 작품·여행 조건·코스 맥락을 \
        미국 오리건에 있는 Amazon Bedrock(AWS)으로 보내 답변과 일정을 만듭니다. \
        이름·이메일은 보내지 않아요. AI 답변은 틀릴 수 있어요.
        """)
    }
}

private struct AIConsentPresentation: ViewModifier {
    @ObservedObject var gate: AIConsentGate

    func body(content: Content) -> some View {
        content.sheet(isPresented: $gate.isPresented, onDismiss: gate.didDismiss) {
            AIConsentSheet(allow: gate.allow, cancel: gate.cancel)
        }
    }
}

extension View {
    func aiConsentSheet(_ gate: AIConsentGate) -> some View {
        modifier(AIConsentPresentation(gate: gate))
    }
}
