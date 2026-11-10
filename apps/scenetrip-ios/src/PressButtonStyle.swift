import SwiftUI

/// 시안 단추의 누름 느낌 — 누르는 동안 0.97배, 0.12초 (MZ2AZ-547 M1, 웹 `proto.css` `.proto-press`).
///
/// `.plain` 처럼 꾸밈은 없고 크기만 줄인다. 「동작 줄이기」 설정이면 줄이지 않는다(웹도 전역 규칙이 멈춘다).
struct PressButtonStyle: ButtonStyle {
    static let pressedScale: CGFloat = 0.97
    static let duration: Double = 0.12

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? Self.pressedScale : 1)
            .animation(.easeOut(duration: Self.duration), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressButtonStyle {
    /// 시안 단추 — 누르는 동안 살짝 작아진다.
    static var press: PressButtonStyle {
        PressButtonStyle()
    }
}
