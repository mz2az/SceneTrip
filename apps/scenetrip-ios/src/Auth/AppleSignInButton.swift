import AuthenticationServices
import SwiftUI

/// Apple 제공 버튼의 글자·로고를 그대로 사용한다(MZ2AZ-415).
struct AppleSignInButton: UIViewRepresentable {
    @Environment(\.colorScheme) private var colorScheme
    let enabled: Bool
    let action: () -> Void

    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .continue, style: colorScheme == .dark ? .white : .black)
        button.cornerRadius = 25
        button.addTarget(context.coordinator, action: #selector(Coordinator.pressed), for: .touchUpInside)
        button.accessibilityIdentifier = "apple-sign-in"
        return button
    }

    func updateUIView(_ button: ASAuthorizationAppleIDButton, context: Context) {
        button.isEnabled = enabled
        button.accessibilityLabel = tr("Apple 계정으로 계속하기")
        context.coordinator.action = action
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(action: action)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ASAuthorizationAppleIDButton, context _: Context) -> CGSize? {
        CGSize(width: proposal.width ?? uiView.intrinsicContentSize.width, height: proposal.height ?? 50)
    }

    final class Coordinator: NSObject {
        var action: () -> Void
        init(action: @escaping () -> Void) {
            self.action = action
        }

        @objc func pressed() {
            action()
        }
    }
}
