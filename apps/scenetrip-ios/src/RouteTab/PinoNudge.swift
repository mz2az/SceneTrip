import SwiftUI

/// **피노 색으로 숨 쉬는 강조.** 눌러 달라는 단추에 씌운다 (2026-08-28).
///
/// 회색 단추 위의 옅은 파란 테두리로는 아무도 못 알아봤다(1차 시도, 사용자
/// 확인) — 켜지면 AI 가이드 단추처럼 **피노 그라데이션으로 통째로 물들고**,
/// 그 위에서 밝기가 오르내린다. 꺼지면 원래 모습 그대로다.
struct PinoNudge: ViewModifier {
    let on: Bool
    var cornerRadius: CGFloat = 10

    @State private var glow = false

    func body(content: Content) -> some View {
        content
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(
                        LinearGradient(
                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                            startPoint: .topLeading, endPoint: .bottomTrailing
                        )
                    )
                    .opacity(on ? (glow ? 0.95 : 0.55) : 0)
                    .allowsHitTesting(false)
            )
            .overlay(
                // 글자·아이콘이 그라데이션 위에서도 읽히게 흰색으로 다시 얹는다.
                content
                    .foregroundStyle(.white)
                    .opacity(on ? 1 : 0)
                    .allowsHitTesting(false)
            )
            .onChange(of: on, initial: true) { _, now in
                guard now else {
                    glow = false
                    return
                }
                glow = false
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                    glow = true
                }
            }
    }
}
