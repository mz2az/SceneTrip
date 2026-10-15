import SwiftUI

/// 코스를 담기 전에도 사용할 수 있는 가이드 입구. 열기만 해서는 모델을 호출하지 않는다.
struct RouteGuideStart: View {
    let hasLocation: Bool
    let anchorActionTitle: String
    var onChangeAnchor: (() -> Void)?
    let cartPlaces: [String]
    var onOpenCart: (() -> Void)?
    var onOpenNearby: (() -> Void)?
    var onOpenPlanner: (() -> Void)?
    var showQuestions = true
    let canAsk: Bool
    let onAsk: (String) -> Void

    private let prompts = ["주변 촬영지 알려줘", "주변 음식점 알려줘", "주변 카페 알려줘", "주변 명소 알려줘"]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("무엇을 도와드릴까요?")
                .font(.subheadline.weight(.semibold))
            Text("장소를 코스에 담기 전에도 주변을 찾아볼 수 있어요.")
                .font(.caption).foregroundStyle(.secondary)
            if let onOpenNearby {
                Button("지도 주변 촬영지 보기", action: onOpenNearby)
                    .font(.subheadline.weight(.semibold)).buttonStyle(.bordered)
                    .accessibilityIdentifier("guide-nearby-places")
            }
            if hasLocation, let onChangeAnchor {
                Button(anchorActionTitle, action: onChangeAnchor)
                    .font(.caption).buttonStyle(.bordered)
                    .accessibilityIdentifier("guide-change-anchor")
            }
            Divider()
            if let onOpenPlanner {
                Button("코스 추천받기 (AI)", action: onOpenPlanner)
                    .font(.subheadline.weight(.semibold)).buttonStyle(.bordered)
                    .accessibilityIdentifier("guide-ai-planner")
                Text("AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            if showQuestions {
                Text("AI에게 주변 장소 물어보기")
                    .font(.caption.weight(.semibold))
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
                Text("추천 장소는 골라 담으세요. 일정 변경 요청은 초안에 반영돼요.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
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
