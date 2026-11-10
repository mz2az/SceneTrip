import SwiftUI

/// 작품 검색칸 — 🔍 · 「작품 이름으로 찾기」 · ✕ 지우기 (웹 `ProtoWorkStep`).
struct WorksSearchField: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").font(.system(size: 16)).foregroundStyle(.secondary)
            TextField(tr("작품 이름으로 찾기"), text: $text)
                .font(.system(size: 16))
                .submitLabel(.search)
                .autocorrectionDisabled()
                .accessibilityLabel(Text("작품 이름으로 찾기"))
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(Color(.tertiaryLabel))
                        .frame(width: 32, height: 32).contentShape(.rect)
                }
                .buttonStyle(.press)
                .accessibilityLabel(Text("검색어 지우기"))
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 40)
        .background(Capsule().fill(Color(.systemBackground)))
        .overlay(Capsule().strokeBorder(Color(.separator).opacity(0.5), lineWidth: 1))
    }
}

/// 짜는 중 포스터가 천천히 8pt 떠올랐다 내려오기를 되풀이한다 — 2.4초, 장마다 0.25초 늦게
/// (MZ2AZ-547 M9, 웹 `.proto-float`). 화면이 멈춘 것이 아니라는 표시. 「동작 줄이기」 면 가만히 둔다.
struct PlanningFloat: ViewModifier {
    let index: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var up = false

    func body(content: Content) -> some View {
        content
            .offset(y: up ? -8 : 0)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true).delay(Double(index) * 0.25)) {
                    up = true
                }
            }
    }
}
