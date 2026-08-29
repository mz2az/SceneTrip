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
                // 반짝이 아이콘 대신 **해태 얼굴**(2026-08-28 사용자 요청) —
                // 가이드가 마스코트 자신이라는 것이 단추에서 바로 보인다.
                Image("haetae-face")
                    .resizable()
                    .scaledToFit()
                    .padding(7)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(.white))
                    .overlay(Circle().strokeBorder(
                        LinearGradient(
                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ), lineWidth: 2.5
                    ))
                    .shadow(color: .black.opacity(0.22), radius: 5, y: 3)
            }
            .buttonStyle(.plain)
        }
    }
}

/// **접힌 가이드 창.** 대화창을 닫으면 사라지는 대신 이것으로 줄어들어 오른쪽에
/// 붙어 있다가, 누르면 다시 펼쳐진다(2026-08-28 사용자 요청 — 화면마다 다른
/// 단추 대신, 한 번 연 대화는 어디서든 같은 동그라미로 다시 부른다).
///
/// `RouteChatButton`(56pt·배지)보다 작다 — 지도를 가리지 않는 크기가 요점이다.
struct RouteGuideChip: View {
    var onTap: () -> Void = {}

    var body: some View {
        Button(action: onTap) {
            // 해태 얼굴 + 「내가 도와줄게!」 말풍선(2026-08-28 사용자 요청) —
            // 마스코트가 말을 거는 모양이라 무엇을 하는 단추인지 설명이 필요 없다.
            HStack(spacing: 6) {
                Text("내가 도와줄게!")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color(PinImage.deep))
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Capsule().fill(.white))
                    .overlay(Capsule().strokeBorder(
                        Color(PinImage.light).opacity(0.6), lineWidth: 1
                    ))
                    .shadow(color: .black.opacity(0.12), radius: 3, y: 1)

                Image("haetae-face")
                    .resizable()
                    .scaledToFit()
                    .padding(5)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(.white))
                    .overlay(Circle().strokeBorder(
                        LinearGradient(
                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ), lineWidth: 2
                    ))
                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
            }
        }
        .buttonStyle(.plain)
    }
}
