import SwiftUI
import UIKit

/// 표시할 때 기록한다. 닫기·시간 만료·화면 재진입도 같은 한 번이다(MZ2AZ-444).
@MainActor
final class RouteCartHintStore {
    private static let key = "scenetrip.manualCourseCartHintSeen"
    private let storage: AIConsentStorage

    init(storage: AIConsentStorage = UserDefaults.standard) {
        self.storage = storage
    }

    func claim(isNew: Bool, madeByAI: Bool, isEmpty: Bool) -> Bool {
        guard isNew, !madeByAI, isEmpty, storage.integer(forKey: Self.key) == 0 else { return false }
        storage.set(1, forKey: Self.key)
        return true
    }

    static func messageKey(cartIsEmpty: Bool) -> String {
        cartIsEmpty ? "장소를 담으면 여기서 코스에 넣을 수 있어요" : "담아둔 장소로 코스를 시작해 보세요"
    }
}

extension RouteEditorView {
    var cartHintNotice: some View {
        VStack(spacing: 0) {
            if cartHintVisible {
                HStack(alignment: .top, spacing: 8) {
                    Text(cartHintText)
                        .font(.caption).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button { cartHintVisible = false } label: {
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
        .task(id: cartHintReady) {
            guard cartHintReady, !Task.isCancelled,
                  RouteCartHintStore().claim(
                      isNew: isNew, madeByAI: course.madeByAI,
                      isEmpty: course.days.allSatisfy(\.stops.isEmpty)
                  ) else { return }
            cartHintText = tr(RouteCartHintStore.messageKey(cartIsEmpty: cart.items.isEmpty))
            cartHintVisible = true
            UIAccessibility.post(notification: .announcement, argument: cartHintText)
            do {
                try await Task.sleep(for: RouteCartHintRing.duration)
            } catch {
                cartHintVisible = false
                return
            }
            cartHintVisible = false
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
