import SwiftUI

/// 지도 위에 얹히는 3단 스냅 시트 (계획서 §3-1).
///
/// 스냅 비율 14% / 48% / 최대는 두 앱이 **같은 숫자**를 써야 한다 — 플랫폼 기본값
/// (iOS `UISheetPresentationController`, Android `BottomSheetBehavior`)에 맡기면
/// 미묘하게 갈린다. 최대 높이는 검색바 바로 아래까지이며 기기별로 계산한다.
///
/// 중간 단은 42% 로 시작했다가 48% 로 올렸다 — 42% 에서는 작품 상세의 설명까지만
/// 보이고 촬영지 행이 한 줄도 안 보였다(실측). 48% 면 설명과 촬영지 두 줄이 같이
/// 보인다.
struct BottomSheet<Content: View>: View {
    enum Detent: CGFloat, CaseIterable {
        case collapsed = 0.14
        case medium = 0.48
        case expanded = 1.0
    }

    @Binding var detent: Detent
    /// 검색바가 차지하는 높이. 최대 단계는 이 아래까지만 올라온다.
    let topInset: CGFloat

    /// 중간 단의 비율을 화면이 바꿔 끼울 수 있다. 검색 탭은 48%(기본) 그대로,
    /// 경로 편집은 단추가 많아 60% 를 쓴다(2026-08-28 사용자 요청 — 40:60).
    /// 값을 안 주면 아무것도 달라지지 않는다.
    var mediumFraction: CGFloat?

    /// 지금 덮고 있는 **실제 높이(pt)**. 지도가 로고·축척을 이 위에 올려 두는 데 쓴다.
    /// 끄는 중에도 계속 바뀌므로 손을 떼기 전에도 따라간다.
    var onHeightChange: (CGFloat) -> Void = { _ in }
    @ViewBuilder let content: () -> Content

    @State private var drag: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            // **첫 레이아웃에서 높이가 0 일 수 있다.** 그대로 두면 아래
            // `clamped(to: 80 ... maxH)` 의 범위가 뒤집혀(80 > maxH) 곧바로
            // 죽는다 — 경로 편집 화면이 ZStack 안에서 이 시트를 쓰기 시작하면서
            // 실제로 터졌다(2026-08-27, "Range requires lowerBound <= upperBound").
            // 검색 탭에서는 지오메트리가 0 으로 오는 순간이 없어 드러나지 않았을 뿐,
            // 여기서 막는 것이 맞다.
            let maxH = max(80, geo.size.height - topInset)
            let height = (heightFor(detent, total: geo.size.height, maxH: maxH) - drag)
                .clamped(to: 80 ... maxH)

            VStack(spacing: 0) {
                Capsule()
                    .fill(Color(.systemGray3))
                    .frame(width: 40, height: 5)
                    .padding(.top, 8)
                    .padding(.bottom, 6)
                content()
            }
            .frame(width: geo.size.width, height: height, alignment: .top)
            .background(
                Color(.systemBackground)
                    .clipShape(.rect(topLeadingRadius: 16, topTrailingRadius: 16))
                    .shadow(color: .black.opacity(0.15), radius: 8, y: -2)
            )
            .frame(maxHeight: .infinity, alignment: .bottom)
            .onChange(of: height, initial: true) { _, current in
                onHeightChange(current)
            }
            .gesture(
                DragGesture()
                    .onChanged { drag = $0.translation.height }
                    .onEnded { value in
                        let settled = height - value.predictedEndTranslation.height + drag
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                            detent = nearest(to: settled, total: geo.size.height, maxH: maxH)
                            drag = 0
                        }
                    }
            )
        }
        .ignoresSafeArea(edges: .bottom)
    }

    private func heightFor(_ detent: Detent, total: CGFloat, maxH: CGFloat) -> CGFloat {
        switch detent {
        case .expanded: maxH
        case .medium: total * (mediumFraction ?? Detent.medium.rawValue)
        case .collapsed: total * Detent.collapsed.rawValue
        }
    }

    private func nearest(to height: CGFloat, total: CGFloat, maxH: CGFloat) -> Detent {
        Detent.allCases.min {
            abs(heightFor($0, total: total, maxH: maxH) - height)
                < abs(heightFor($1, total: total, maxH: maxH) - height)
        } ?? .medium
    }
}

extension CGFloat {
    func clamped(to range: ClosedRange<CGFloat>) -> CGFloat {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
