import SwiftUI

/// 코스를 담기 전에도 사용할 수 있는 가이드 입구. 열기만 해서는 모델을 호출하지 않는다.
struct RouteGuideStart: View {
    let hasLocation: Bool
    let anchorActionTitle: String
    var onChangeAnchor: (() -> Void)?
    let cartPlaces: [String]
    var onOpenCart: (() -> Void)?
    let canAsk: Bool
    let onAsk: (String) -> Void

    private let prompts = ["주변 촬영지 알려줘", "주변 음식점 알려줘", "주변 카페 알려줘", "주변 명소 알려줘"]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("무엇을 도와드릴까요?")
                .font(.subheadline.weight(.semibold))
            Text("장소를 코스에 담기 전에도 주변을 찾아볼 수 있어요.")
                .font(.caption).foregroundStyle(.secondary)
            if hasLocation, let onChangeAnchor {
                Button(anchorActionTitle, action: onChangeAnchor)
                    .font(.caption).buttonStyle(.bordered)
                    .accessibilityIdentifier("guide-change-anchor")
            }
            ForEach(prompts, id: \.self) { prompt in
                Button { onAsk(tr(prompt)) } label: {
                    Text(tr(prompt))
                        .font(.caption)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Capsule().fill(Color(.systemGray6)))
                }
                .buttonStyle(.plain)
                .disabled(!canAsk)
            }
            Text("추천 장소를 확인하고 원하는 곳을 코스에 담아 보세요.")
                .font(.caption2).foregroundStyle(.secondary)
            if let onOpenCart {
                Divider()
                Text(cartPlaces.isEmpty ? tr("장바구니에 담긴 장소가 없어요.") : cartPlaces.prefix(3).joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                    .lineLimit(3)
                Button("장바구니 장소 보기", action: onOpenCart)
                    .font(.caption).buttonStyle(.bordered)
            }
        }
    }
}
