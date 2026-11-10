import SwiftUI
import UIKit

/// 안내의 그림 — 어두운 덮개에 단추 자리만 뚫고, 그 위나 아래에 말풍선 (MZ2AZ-547 P6 C1, 웹 `proto/CoachTour.tsx`).
///
/// 말풍선 폭 280, 제목 16 · 본문 14 · 「1 / N」 · [건너뛰기](마지막 단계엔 없음) · [다음]/[알겠어요]. 덮개를 눌러도 다음으로.
/// 자리는 `CoachRules.layout` 이 화면 가장자리·안전영역 안으로 정한다. 움직임 줄이기면 나타남·옮김을 움직이지 않는다.
struct CoachOverlay: View {
    @ObservedObject var center: CoachCenter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 지금 단계 단추의 창 좌표 — 못 찾았거나 가려져 있으면 nil(아무것도 그리지 않고 손짓을 흘린다).
    @State private var target: CGRect?
    /// 잰 말풍선 높이. 재기 전에는 웹처럼 150 으로 어림한다.
    @State private var bubbleHeight: CGFloat = 150

    private static let dim = Color(red: 10 / 255, green: 8 / 255, blue: 25 / 255).opacity(0.62)

    var body: some View {
        GeometryReader { safe in
            GeometryReader { full in
                if let run = center.running, let target {
                    content(run: run, layout: CoachRules.layout(
                        target: target, container: full.size,
                        safeTop: safe.safeAreaInsets.top, safeBottom: safe.safeAreaInsets.bottom,
                        bubbleHeight: bubbleHeight
                    ))
                    .transition(.opacity)
                }
            }
            .ignoresSafeArea()
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: target == nil)
        .task(id: center.running.map { "\($0.tour.id)#\($0.index)" }) { await follow() }
    }

    /// 단추 자리를 매 화면 다시 잰다. 안 보인 지 4초가 넘으면 그 단계를 건너뛴다(가려진 동안은 세지 않는다).
    private func follow() async {
        guard let run = center.running else {
            target = nil
            return
        }
        var lastSeen = Date()
        while !Task.isCancelled, center.running == run {
            switch center.locate(run.step.target) {
            case let .found(rect):
                lastSeen = Date()
                if target.map({ Self.moved($0, rect) }) ?? true {
                    target = rect
                }
            case .covered:
                lastSeen = Date()
                target = nil
            case .missing:
                target = nil
                if CoachRules.shouldSkipMissing(waited: Date().timeIntervalSince(lastSeen)) {
                    center.perform(.targetMissing, tourId: run.tour.id)
                    return
                }
            }
            center.setCapturing(target != nil)
            try? await Task.sleep(for: .milliseconds(16))
        }
    }

    private static func moved(_ old: CGRect, _ new: CGRect) -> Bool {
        abs(old.minY - new.minY) + abs(old.minX - new.minX) + abs(old.width - new.width) + abs(old.height - new.height) >= 0.5
    }

    private func content(run: CoachCenter.Running, layout: CoachLayout) -> some View {
        let tourId = run.tour.id
        return ZStack(alignment: .topLeading) {
            // 덮개 — 구멍 밖을 어둡게. 누르면 다음으로. 구멍은 다음 단계 자리로 0.2초에 옮겨 간다(M10, 웹 `.proto-coach-hole`) —
            // `Path` 는 움직임을 모르는 모양이라 순간 이동했다. 구멍 틀을 움직일 수 있는 값으로 가진 모양을 쓴다.
            CoachScrimShape(hole: layout.hole, radius: layout.holeRadius)
                .fill(Self.dim, style: FillStyle(eoFill: true))
                .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: layout.hole)
                .contentShape(Rectangle())
                .onTapGesture { center.perform(.tapScrim, tourId: tourId) }
                .accessibilityHidden(true)
            bubble(run: run, layout: layout)
                // 단계마다 6pt 아래에서 떠오르며 0.22초에 나타난다(M10, 웹 `.proto-coach-bubble`).
                .modifier(CoachRise(step: run.index, enabled: !reduceMotion))
                .offset(x: layout.bubble.minX, y: layout.bubble.minY)
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: layout)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    private func bubble(run: CoachCenter.Running, layout: CoachLayout) -> some View {
        let step = run.step
        let count = run.tour.steps.count
        let tourId = run.tour.id
        return VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: tr(step.title))
                .font(.system(size: 16, weight: .heavy))
                .foregroundStyle(.primary)
                .padding(.bottom, 4)
                .accessibilityAddTraits(.isHeader)
            Text(verbatim: tr(step.text))
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Text(verbatim: CoachRules.counter(index: run.index, count: count))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(.tertiary)
                Spacer(minLength: 0)
                if CoachRules.showsSkip(index: run.index, count: count) {
                    Button { center.perform(.skip, tourId: tourId) } label: {
                        Text(verbatim: tr("건너뛰기"))
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    center.perform(.next, tourId: tourId)
                } label: {
                    Text(verbatim: tr(CoachRules.primaryTitle(index: run.index, count: count)))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(TabBar.homePurple))
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 2)
        .frame(width: layout.bubble.width, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bubbleHeight = $0 }
        .background(alignment: .topLeading) {
            ZStack(alignment: .topLeading) {
                if layout.showsArrow {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color(.systemBackground))
                        .frame(width: 16, height: 16)
                        .rotationEffect(.degrees(45))
                        .offset(x: layout.arrowX, y: layout.below ? -7 : layout.bubble.height - 9)
                }
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color(.systemBackground))
                    .shadow(color: .black.opacity(0.25), radius: 15, y: 10)
            }
        }
        .accessibilityElement(children: .contain)
        .onAppear { UIAccessibility.post(notification: .screenChanged, argument: nil) }
        .onChange(of: run.index) { _, _ in UIAccessibility.post(notification: .screenChanged, argument: nil) }
    }
}

/// 덮개 모양 — 큰 사각형에 둥근 구멍 하나(짝홀 채우기). 구멍의 자리·크기·모서리가 움직일 수 있는 값이라 단계 사이를 옮겨 간다.
struct CoachScrimShape: Shape {
    var hole: CGRect
    var radius: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<AnimatablePair<CGFloat, CGFloat>, CGFloat>> {
        get {
            AnimatablePair(
                AnimatablePair(hole.minX, hole.minY),
                AnimatablePair(AnimatablePair(hole.width, hole.height), radius)
            )
        }
        set {
            hole = CGRect(
                x: newValue.first.first, y: newValue.first.second,
                width: newValue.second.first.first, height: newValue.second.first.second
            )
            radius = newValue.second.second
        }
    }

    func path(in _: CGRect) -> Path {
        var path = Path()
        path.addRect(CGRect(origin: .zero, size: CGSize(width: 10000, height: 10000)))
        path.addRoundedRect(in: hole, cornerSize: CGSize(width: radius, height: radius))
        return path
    }
}

/// 말풍선이 단계마다 6pt 아래에서 떠오르며 나타난다(0.22초). 움직임 줄이기면 그냥 보인다.
private struct CoachRise: ViewModifier {
    let step: Int
    let enabled: Bool

    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown || !enabled ? 1 : 0)
            .offset(y: shown || !enabled ? 0 : 6)
            .onAppear(perform: rise)
            .onChange(of: step) { _, _ in
                var hide = Transaction()
                hide.disablesAnimations = true
                withTransaction(hide) { shown = false }
                rise()
            }
    }

    private func rise() {
        // 숨긴 그림이 한 번 그려진 다음 떠오르게 한 박자 뒤에.
        DispatchQueue.main.async {
            withAnimation(.easeOut(duration: 0.22)) { shown = true }
        }
    }
}
