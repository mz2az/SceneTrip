import SwiftUI

/// 첫 질문·첫 AI 코스 만들기·설정에서 공통으로 쓰는 안내.
struct ConsentSheet: View {
    let title: LocalizedStringKey
    let disclosure: String
    let linkTitle: LocalizedStringKey
    let link: URL
    let allowTitle: LocalizedStringKey
    let identifier: String
    let allow: () -> Void
    let cancel: () -> Void

    @State private var contentHeight: CGFloat = 0

    private static let minimumHeight: CGFloat = 240
    private static let maximumHeight: CGFloat = 480

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(title)
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text(disclosure)
                Link(linkTitle, destination: link)
                actions
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(24)
            .onGeometryChange(for: CGFloat.self) { geometry in
                ceil(geometry.size.height)
            } action: { height in
                contentHeight = height
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([contentDetent, .large])
        .presentationDragIndicator(.visible)
    }

    private var contentDetent: PresentationDetent {
        guard contentHeight > 0 else { return .medium }
        return .height(min(max(contentHeight, Self.minimumHeight), Self.maximumHeight))
    }

    private var actions: some View {
        VStack(spacing: 6) {
            Button(action: allow) {
                Text(allowTitle)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 28)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .accessibilityIdentifier("\(identifier)-allow")
            Button(role: .cancel, action: cancel) {
                Text("취소")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("\(identifier)-cancel")
        }
    }
}

struct AIConsentSheet: View {
    let allow: () -> Void
    let cancel: () -> Void

    var body: some View {
        ConsentSheet(
            title: "AI 기능 사용 안내", disclosure: disclosure,
            linkTitle: "개인정보 처리방침", link: LegalLinks.privacy,
            allowTitle: "허용하고 계속", identifier: "ai-consent",
            allow: allow, cancel: cancel
        )
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
