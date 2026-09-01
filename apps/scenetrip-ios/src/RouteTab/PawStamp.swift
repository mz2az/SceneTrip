import SwiftUI

/// 발바닥 — 큰 패드 하나 + 발가락 셋. 지도 점에서 걷어낸 모양(2026-09-02)을 **스탬프**로
/// 살렸다. 「해태가 밟고 간 자리」라는 원래 뜻과 맞고, 화면 가운데 쾅 찍히는 연출은
/// 원보다 발바닥이 낫다.
struct PawShape: Shape {
    func path(in rect: CGRect) -> Path {
        // 20×20 기준 좌표를 rect 에 맞춰 늘린다(원래 `guideDot` 의 수치).
        let scaleX = rect.width / 20, scaleY = rect.height / 20
        func oval(_ left: CGFloat, _ top: CGFloat, _ width: CGFloat, _ height: CGFloat) -> Path {
            Path(ellipseIn: CGRect(
                x: rect.minX + left * scaleX, y: rect.minY + top * scaleY,
                width: width * scaleX, height: height * scaleY
            ))
        }
        var path = oval(5.2, 10.2, 9.6, 7.6)
        path.addPath(oval(3.4, 5.4, 4.4, 4.6))
        path.addPath(oval(7.9, 3.2, 4.4, 4.6))
        path.addPath(oval(12.4, 5.4, 4.4, 4.6))
        return path
    }
}

/// 도착 스탬프 연출 — 크게 나타나 **쾅** 하고 자리 잡고, 잠시 뒤 사라진다.
///
/// 도장을 찍는 손짓을 흉내 낸다: 크고 흐리게 시작해(공중) 빠르게 줄어들며 진해진다
/// (종이에 닿음). 살짝 기울인 채 찍혀 손으로 찍은 느낌을 남긴다. 햅틱 한 번.
struct PawStampOverlay: View {
    let title: String
    let subtitle: String
    /// 연출이 끝나면 부른다 — 부르는 쪽이 다음 목적지로 넘어간다.
    let onDone: () -> Void

    @State private var landed = false

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(.white)
                    .frame(width: 148, height: 148)
                    .shadow(color: Color(PinImage.deep).opacity(0.35), radius: 16, y: 8)
                PawShape()
                    .fill(LinearGradient(
                        colors: [Color(PinImage.light), Color(PinImage.deep)],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    ))
                    .frame(width: 96, height: 96)
                    .rotationEffect(.degrees(-12))
            }
            .scaleEffect(landed ? 1 : 2.2)
            .opacity(landed ? 1 : 0)

            VStack(spacing: 4) {
                Text(title).font(.system(size: 20, weight: .heavy))
                Text(subtitle).font(.system(size: 14)).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(Capsule().fill(Color(.systemBackground)))
            .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
            .opacity(landed ? 1 : 0)
        }
        .onAppear {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.55)) { landed = true }
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        }
        .task {
            try? await Task.sleep(for: .seconds(1.6))
            onDone()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) \(subtitle)")
    }
}
