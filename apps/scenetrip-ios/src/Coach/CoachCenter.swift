import SwiftUI
import UIKit

/// 처음 안내를 **한 곳에서** 띄우고 넘긴다 (MZ2AZ-547 P6 C1, 웹 `proto/CoachTour.tsx`).
///
/// 안내는 앱 창 위에 따로 띄운 **투명한 창**에 그린다. 「새 여행 코스」 시트는 330 높이라 그 안에는 말풍선을 둘 자리가 없고,
/// 웹처럼 시트 위 어두운 홈까지 덮어야 한다 — SwiftUI 의 덮개(`overlay`)는 시트 밖으로 못 나간다.
///
/// 가리킬 단추는 `coachTarget(_:)` 표식이 이름과 함께 여기에 알린다. 안내가 떠 있는 동안 덮개가 매 화면(약 60번/초)
/// 단추의 자리를 다시 재서 따라간다 — 시트가 올라오거나 지도가 움직여도 말풍선이 붙어 있게(웹의 requestAnimationFrame 과 같다).
@MainActor
final class CoachCenter: ObservableObject {
    static let shared = CoachCenter()

    /// 지금 떠 있는 안내와 단계.
    struct Running: Equatable {
        let tour: CoachTour
        var index: Int

        var step: CoachStep {
            tour.steps[min(index, tour.steps.count - 1)]
        }
    }

    @Published private(set) var running: Running?

    private var queue = CoachQueue()
    private let seen: CoachSeenStore
    private var window: CoachWindow?
    /// 표식 이름 → 그 단추 밑에 깔린 보이지 않는 UIKit 조각. 같은 이름이 둘이면 보이는 쪽을 쓴다.
    private var probes: [String: [WeakProbe]] = [:]
    /// 확인용 뒷문 — `-coachStep 2` 면 안내를 둘째 단계부터 띄운다(시뮬레이터에 합성 탭이 닿지 않아 화면 확인용).
    private let firstStep: Int

    init(seen: CoachSeenStore = CoachSeenStore(), defaults: UserDefaults = .standard) {
        self.seen = seen
        // 확인용 뒷문 — `-coachReset 1` 이면 본 기록을 지우고 처음부터 다시 띄운다(웹 `?coach=reset`).
        if defaults.bool(forKey: "coachReset") {
            seen.reset()
        }
        firstStep = max(defaults.integer(forKey: "coachStep") - 1, 0)
    }

    func isSeen(_ tour: CoachTour) -> Bool {
        seen.isSeen(tour.id)
    }

    /// 띄웠으면 true. 이미 봤거나 다른 안내가 떠 있으면 false — 부른 쪽이 잠시 뒤 다시 묻는다.
    func start(_ tour: CoachTour) -> Bool {
        guard !tour.steps.isEmpty, queue.start(tour.id, seen: seen.isSeen(tour.id)) else { return false }
        running = Running(tour: tour, index: min(firstStep, tour.steps.count - 1))
        showWindow()
        return true
    }

    /// 다음·건너뛰기·덮개 누름·단추 없음·화면 떠남. 지금 떠 있는 안내(`tourId`)가 아니면 아무것도 안 한다.
    func perform(_ action: CoachAction, tourId: String) {
        guard let current = running, current.tour.id == tourId else { return }
        switch CoachRules.advance(index: current.index, count: current.tour.steps.count, action: action) {
        case let .step(index):
            running?.index = index
        case let .finished(markSeen):
            if markSeen {
                seen.markSeen(tourId)
            }
            queue.end(tourId)
            running = nil
            hideWindow()
        }
    }

    // MARK: 단추 찾기

    func register(_ id: String, _ probe: UIView) {
        var list = probes[id, default: []].filter { $0.view != nil && $0.view !== probe }
        list.append(WeakProbe(view: probe))
        probes[id] = list
    }

    func unregister(_ id: String, _ probe: UIView) {
        probes[id] = probes[id]?.filter { $0.view != nil && $0.view !== probe }
    }

    enum Lookup: Equatable {
        /// 화면(창) 좌표의 틀.
        case found(CGRect)
        /// 단추는 있지만 그 위에 다른 창(시트·경고창)이 떠 있다 — 안내를 숨기고 기다린다. 「없음」 으로 세지 않는다.
        case covered
        case missing
    }

    func locate(_ id: String) -> Lookup {
        var sawCovered = false
        for probe in (probes[id] ?? []).compactMap(\.view) {
            guard probe.window != nil, Self.isShown(probe) else { continue }
            if Self.isCovered(probe) {
                sawCovered = true
                continue
            }
            let rect = probe.convert(probe.bounds, to: nil)
            if rect.width > 0 || rect.height > 0 {
                return .found(rect)
            }
        }
        return sawCovered ? .covered : .missing
    }

    /// 조상 중 숨겨지거나 투명한 것이 없나 — 다른 탭에 남아 있는 단추를 가리키지 않게.
    private static func isShown(_ view: UIView) -> Bool {
        var node: UIView? = view
        while let current = node {
            if current.isHidden || current.alpha < 0.01 {
                return false
            }
            node = current.superview
        }
        return true
    }

    /// 단추가 든 화면 위에 다른 화면(시트·덮개·경고창)이 떠 있나.
    private static func isCovered(_ view: UIView) -> Bool {
        var responder: UIResponder? = view
        while let current = responder, !(current is UIViewController) {
            responder = current.next
        }
        var controller = responder as? UIViewController
        while let vc = controller {
            if let presented = vc.presentedViewController, !presented.isBeingDismissed {
                return true
            }
            controller = vc.parent
        }
        return false
    }

    // MARK: 창

    /// 단추를 찾는 동안은 손짓을 아래 앱으로 흘린다 — 안내가 보일 때만 붙잡는다.
    func setCapturing(_ on: Bool) {
        window?.passesTouches = !on
    }

    private func showWindow() {
        guard window == nil else { return }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else { return }
        let window = CoachWindow(windowScene: scene)
        // 시트·전체 화면 덮개는 앱 창 안에 뜬다 — 창 하나 위면 그 모두 위다.
        window.windowLevel = .normal + 1
        let host = UIHostingController(rootView: CoachOverlay(center: self))
        host.view.backgroundColor = .clear
        // 앱 전체가 밝은 모양이다(`AppRoot` 의 `.preferredColorScheme(.light)`). 따로 띄운 창은 그것을 물려받지 못한다.
        host.overrideUserInterfaceStyle = .light
        window.rootViewController = host
        window.isHidden = false
        self.window = window
    }

    private func hideWindow() {
        window?.isHidden = true
        window = nil
    }
}

private struct WeakProbe {
    weak var view: UIView?
}

/// 안내 창 — 단추를 찾는 중(덮개가 안 보일 때)에는 손짓을 아래 앱으로 흘린다.
final class CoachWindow: UIWindow {
    var passesTouches = true

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        passesTouches ? nil : super.hitTest(point, with: event)
    }
}

// MARK: - 표식

/// 단추 밑에 깔리는 보이지 않는 UIKit 조각 — 이것의 창 좌표가 곧 단추의 자리다.
private struct CoachProbe: UIViewRepresentable {
    let id: String

    final class Coordinator {
        var id: String

        init(id: String) {
            self.id = id
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(id: id)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        CoachCenter.shared.register(context.coordinator.id, view)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        if context.coordinator.id != id {
            CoachCenter.shared.unregister(context.coordinator.id, view)
            context.coordinator.id = id
        }
        CoachCenter.shared.register(id, view)
    }

    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        CoachCenter.shared.unregister(coordinator.id, view)
    }
}

/// 조건이 맞으면 안내를 띄운다 — 화면이 떠나거나 조건이 풀리면 접는다(본 것으로 치지 않는다).
private struct CoachTourModifier: ViewModifier {
    let tour: CoachTour
    let active: Bool

    func body(content: Content) -> some View {
        content
            .task(id: active) {
                let center = CoachCenter.shared
                guard active else {
                    center.perform(.leave, tourId: tour.id)
                    return
                }
                try? await Task.sleep(for: .seconds(tour.delay))
                // 한 번에 하나 — 다른 안내가 떠 있으면 그것이 끝날 때까지 기다린다.
                while !Task.isCancelled, !center.isSeen(tour) {
                    if center.start(tour) || center.running?.tour.id == tour.id {
                        return
                    }
                    try? await Task.sleep(for: .seconds(CoachRules.retryInterval))
                }
            }
            .onDisappear { CoachCenter.shared.perform(.leave, tourId: tour.id) }
    }
}

extension View {
    /// 안내가 가리킬 단추라고 표시한다. 이름은 `CoachStep.target`.
    func coachTarget(_ id: String) -> some View {
        background(CoachProbe(id: id).allowsHitTesting(false).accessibilityHidden(true))
    }

    /// `active` 가 되면(그리고 아직 안 봤으면) `tour.delay` 뒤에 띄운다.
    func coachTour(_ tour: CoachTour, active: Bool) -> some View {
        modifier(CoachTourModifier(tour: tour, active: active))
    }
}
