import SwiftUI
import UIKit

/// 표시할 때 기록한다. 닫기·시간 만료·화면 재진입도 같은 한 번이다(MZ2AZ-444).
@MainActor
final class RouteCartHintStore: ObservableObject {
    @Published private(set) var isHighlighted = false
    @Published private(set) var message: String?
    private static let key = "scenetrip.manualCourseCartHintSeen"
    private let storage: AIConsentStorage

    init(storage: AIConsentStorage = UserDefaults.standard) {
        self.storage = storage
    }

    private func canPresent(course: RouteCourse) -> Bool {
        // 새 초안 여부는 화면의 표시 방식(isNew)이 아니라 실제 저장 여부로 판단한다.
        course.serverId == nil && !course.madeByAI && course.stops.isEmpty
            && storage.integer(forKey: Self.key) == 0
    }

    func claim(course: RouteCourse) -> Bool {
        guard canPresent(course: course) else { return false }
        storage.set(1, forKey: Self.key)
        return true
    }

    func dismiss() {
        isHighlighted = false
        message = nil
    }

    /// 강조는 즉시 시작하되 문구·낭독·seen·노출 시간은 조회가 끝난 뒤 함께 시작한다.
    func present(
        course: RouteCourse, cart: CartStore,
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        announce: (String) -> Void
    ) async {
        guard !Task.isCancelled, !isHighlighted, canPresent(course: course) else { return }
        isHighlighted = true
        defer { dismiss() }
        var state = cart.loadState
        if state == .notLoaded || state == .loading {
            for await next in cart.$loadState.values {
                state = next
                if state == .loaded || state == .failed {
                    break
                }
            }
        }
        guard !Task.isCancelled, isHighlighted, claim(course: course) else { return }
        // 실패 시 빈 배열은 비어 있다는 증거가 아니므로 일반 안내를 쓴다.
        let text = tr(Self.messageKey(cartIsEmpty: state == .loaded && cart.items.isEmpty))
        message = text
        announce(text)
        do {
            try await sleep(RouteCartHintRing.duration)
        } catch {
            return
        }
    }

    static func messageKey(cartIsEmpty: Bool) -> String {
        cartIsEmpty ? "장소를 담으면 여기서 코스에 넣을 수 있어요" : "담아둔 장소로 코스를 시작해 보세요"
    }
}

extension RouteEditorView {
    var cartHintNotice: some View {
        VStack(spacing: 0) {
            if let text = cartHint.message {
                HStack(alignment: .top, spacing: 8) {
                    Text(text)
                        .font(.caption).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button { cartHint.dismiss() } label: {
                        Image(systemName: "xmark").frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("닫기")
                }
                .padding(.leading, 12)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color.accentColor.opacity(0.1)))
                .padding(.horizontal, 16).padding(.bottom, 8)
                .accessibilityIdentifier("manual-course-cart-hint")
            }
        }
    }

    /// 목록 행 안의 버튼 대신 편집 화면의 생명주기에서 시작한다.
    /// 장바구니 조회·재시도가 늦어도 버튼 강조는 바로 시작한다.
    func presentCartHint() async {
        await cartHint.present(course: course, cart: cart) { text in
            UIAccessibility.post(notification: .announcement, argument: text)
        }
    }
}

/// 한 번 커지는 테두리. 동작 줄이기에서는 같은 시간 동안 고정 테두리만 보인다.
struct RouteCartHintRing: View {
    static let duration: Duration = .milliseconds(2500)
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false

    var body: some View {
        RoundedRectangle(cornerRadius: 10)
            .strokeBorder(Color.accentColor, lineWidth: 3)
            .scaleEffect(!reduceMotion && expanded ? 1.12 : 1)
            .opacity(!reduceMotion && expanded ? 0.3 : 1)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeOut(duration: 2.5)) { expanded = true }
            }
    }
}
