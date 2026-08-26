import SwiftUI

/// 지도 오른쪽 아래에 늘 떠 있는 AI 챗봇 (MZ2AZ-223).
///
/// **구독자만 쓴다.** 무료 사용자는 횟수로 막고 남은 횟수를 배지로 보여 준다 —
/// 「막혀 있다」가 아니라 「몇 번 남았다」로 보여야 구독을 권하는 자리가 된다.
///
/// > 남은 횟수를 서버가 어디로 내려주는지는 **계약에 아직 없다.** 이 카운터를
/// > 다루는 티켓도 없어서(2026-08-21 확인) 지금은 지어낸 값이다.
struct RouteChatButton: View {
    var remaining: Int? = 7

    /// 누르면 대화창이 열린다. 프로토타입 v6 의 「떠 있는 단추」와 같은 자리·같은 동작이다.
    var onTap: () -> Void = {}

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            if let remaining {
                Text("무료 \(remaining)회 남음")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Color.black.opacity(0.62)))
            }
            Button(action: onTap) {
                Image(systemName: "sparkles")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
                    .background(
                        Circle().fill(
                            LinearGradient(
                                colors: [Color(PinImage.light), Color(PinImage.deep)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    )
                    .shadow(color: .black.opacity(0.22), radius: 5, y: 3)
            }
            .buttonStyle(.plain)
        }
    }
}
