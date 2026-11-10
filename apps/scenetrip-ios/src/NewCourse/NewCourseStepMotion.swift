import SwiftUI

/// 질문 단계 넘김 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §5).
///
/// 앞으로: 새 단계가 오른쪽 24pt 에서 밀려 들어오며 나타나고, 이전 단계는 왼쪽으로 조금 밀리며 사라진다. 뒤로는 반대.
/// 0.28초, 서랍과 같은 곡선 `cubic-bezier(.32,.72,0,1)`. 동작 줄이기면 밀림 없이 0.2초 서서히만.
/// 위쪽 단계 점은 화면과 함께 밀리지 않게 흐름 쪽이 한 번만 그린다(`StepDotsOverlay`) — 같은 0.28초로 늘거나 준다.
enum StepMotion {
    static let duration: Double = 0.28
    static let reducedFade: Double = 0.2
    static let shift: CGFloat = 24

    static func animation(reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: reducedFade) : .timingCurve(0.32, 0.72, 0, 1, duration: duration)
    }

    static func transition(forward: Bool, reduceMotion: Bool) -> AnyTransition {
        if reduceMotion {
            return .opacity
        }
        return .asymmetric(
            insertion: .offset(x: forward ? shift : -shift).combined(with: .opacity),
            removal: .offset(x: forward ? -shift : shift).combined(with: .opacity)
        )
    }

    /// 단계를 바꾼다. 방향이 바뀌었으면 먼저 방향만 반영하고 다음 차례에 바꾼다 —
    /// 사라지는 화면은 마지막으로 그려진 때의 넘김(transition)을 쓰므로, 같은 갱신에서 바꾸면 이전 방향으로 사라진다.
    @MainActor
    static func go(forward next: Bool, direction: Binding<Bool>, reduceMotion: Bool, _ change: @escaping () -> Void) {
        let animation = animation(reduceMotion: reduceMotion)
        guard direction.wrappedValue != next else {
            withAnimation(animation, change)
            return
        }
        direction.wrappedValue = next
        DispatchQueue.main.async { withAnimation(animation, change) }
    }
}

private struct StepDotsOutsideKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// 흐름이 단계 점을 화면 밖(위)에 따로 그린다 — `NewCourseScreen` 은 제 단계 점을 그리지 않는다.
    var stepDotsOutside: Bool {
        get { self[StepDotsOutsideKey.self] }
        set { self[StepDotsOutsideKey.self] = newValue }
    }
}

/// 흐름 위에 한 번만 그리는 단계 점 — `NewCourseScreen` 의 위 줄과 같은 자리(위 4pt, 높이 44, 가운데).
struct StepDotsOverlay: View {
    let step: (at: Int, of: Int)?
    var onPhoto = false

    var body: some View {
        ZStack {
            if let step {
                StepDots(at: step.at, of: step.of, onPhoto: onPhoto)
                    .transition(.opacity)
            }
            Color.clear.frame(height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .frame(minHeight: 44)
        .allowsHitTesting(false)
    }
}
