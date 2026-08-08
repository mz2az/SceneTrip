import SwiftUI

/// 지도 위에 얹히는 3단 스냅 시트 (계획서 §3-1).
///
/// 스냅 비율 14% / 42% / 최대는 두 앱이 **같은 숫자**를 써야 한다 — 플랫폼 기본값
/// (iOS `UISheetPresentationController`, Android `BottomSheetBehavior`)에 맡기면
/// 미묘하게 갈린다. 최대 높이는 검색바 바로 아래까지이며 기기별로 계산한다.
struct BottomSheet<Content: View>: View {
    enum Detent: CGFloat, CaseIterable {
        case collapsed = 0.14
        case medium = 0.42
        case expanded = 1.0
    }

    @Binding var detent: Detent
    /// 검색바가 차지하는 높이. 최대 단계는 이 아래까지만 올라온다.
    let topInset: CGFloat
    @ViewBuilder let content: () -> Content

    @State private var drag: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let maxH = geo.size.height - topInset
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
        detent == .expanded ? maxH : total * detent.rawValue
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
