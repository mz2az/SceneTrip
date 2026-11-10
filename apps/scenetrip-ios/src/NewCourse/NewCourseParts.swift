import SceneApiClient
import SwiftUI

/// 새 코스 만들기 화면들이 같이 쓰는 값과 조각 (MZ2AZ-547 P3, 웹 `proto/protoTokens.ts`·`protoParts.tsx`).
enum NewCourseStyle {
    /// 주 색 — 앱 시그니처 보라 단색(탭바와 같다, 흰 글씨 대비 약 6:1).
    static let purple = TabBar.homePurple
    static let radiusS: CGFloat = 8
    static let radiusM: CGFloat = 14
    static let radiusL: CGFloat = 20

    /// 사진이 없을 때의 바탕 — 하늘→보라. 글자를 올리지 않는 자리에만 쓴다.
    static var brandFill: LinearGradient {
        LinearGradient(
            colors: [Color(PinImage.light), Color(PinImage.deep)],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
    }
}

/// 화면 전체를 덮는 새 코스 화면의 틀 — 위 줄(‹ 뒤로 · 단계 점), 가운데 내용(스크롤), 아래 단추 (웹 `ProtoScreen`).
///
/// `hero` 를 주면 맨 위를 가장자리까지 사진 머리로 채우고, 위 줄은 그 위에 흰색으로 얹힌다.
struct NewCourseScreen<Hero: View, Content: View, Footer: View>: View {
    var step: (at: Int, of: Int)?
    var onBack: (() -> Void)?
    @ViewBuilder var hero: () -> Hero
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footer: () -> Footer

    private var hasHero: Bool {
        Hero.self != EmptyView.self
    }

    private var hasFooter: Bool {
        Footer.self != EmptyView.self
    }

    var body: some View {
        VStack(spacing: 0) {
            if !hasHero {
                bar
            }
            ScrollView {
                VStack(spacing: 0) {
                    hero()
                    VStack(alignment: .leading, spacing: 18) {
                        content()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.top, hasHero ? 16 : 4)
                    .padding(.bottom, 24)
                }
            }
            .scrollBounceBehavior(.basedOnSize)
            .ignoresSafeArea(edges: hasHero ? .top : [])
            if hasFooter {
                VStack(spacing: 0) {
                    Divider()
                    footer()
                        .padding(.horizontal, 20)
                        .padding(.top, 10)
                        .padding(.bottom, 14)
                }
                .background(Color(.systemGroupedBackground))
            }
        }
        .overlay(alignment: .top) {
            if hasHero {
                bar
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
    }

    private var bar: some View {
        ZStack {
            if let step {
                StepDots(at: step.at, of: step.of, onPhoto: hasHero)
            }
            HStack {
                if let onBack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(hasHero ? Color.white : NewCourseStyle.purple)
                            .shadow(color: hasHero ? .black.opacity(0.3) : .clear, radius: 3)
                            .frame(width: 44, height: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.press)
                    .accessibilityLabel(tr("뒤로"))
                }
                Spacer()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .frame(minHeight: 44)
    }
}

extension NewCourseScreen where Hero == EmptyView {
    init(
        step: (at: Int, of: Int)? = nil,
        onBack: (() -> Void)?,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder footer: @escaping () -> Footer
    ) {
        self.init(step: step, onBack: onBack, hero: { EmptyView() }, content: content, footer: footer)
    }
}

extension NewCourseScreen where Hero == EmptyView, Footer == EmptyView {
    init(step: (at: Int, of: Int)? = nil, onBack: (() -> Void)?, @ViewBuilder content: @escaping () -> Content) {
        self.init(step: step, onBack: onBack, hero: { EmptyView() }, content: content, footer: { EmptyView() })
    }
}

/// 화면 제목 — 26 heavy.
struct NewCourseTitle: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(.system(size: 26, weight: .heavy))
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

/// 질문 단계 점 — 지금 단계만 길고 진하게(20×7). 화면 읽기에는 「3단계 중 1단계」.
struct StepDots: View {
    let at: Int
    let of: Int
    var onPhoto = false

    var body: some View {
        HStack(spacing: 6) {
            ForEach(1 ... max(of, 1), id: \.self) { index in
                Capsule()
                    .fill(color(index))
                    .frame(width: index == at ? 20 : 7, height: 7)
            }
        }
        .animation(.easeOut(duration: 0.2), value: at)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(format: tr("%lld단계 중 %lld단계"), of, at))
    }

    private func color(_ index: Int) -> Color {
        if onPhoto {
            return index <= at ? .white : .white.opacity(0.45)
        }
        return index <= at ? NewCourseStyle.purple : Color(.systemGray4)
    }
}

/// 주 단추 — 보라 단색, 흰 글씨 17 bold. 막혀 있으면 흐리게.
struct NewCoursePrimaryButton<Label: View>: View {
    var disabled = false
    let action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(NewCourseStyle.purple))
                .opacity(disabled ? 0.45 : 1)
                .contentShape(.rect)
        }
        .buttonStyle(.press)
        .disabled(disabled)
    }
}

extension NewCoursePrimaryButton where Label == Text {
    init(_ title: String, disabled: Bool = false, action: @escaping () -> Void) {
        self.init(disabled: disabled, action: action) { Text(verbatim: title) }
    }
}

/// 보조 단추 — 흰 바탕, 보라 테두리·글씨.
struct NewCourseSecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(NewCourseStyle.purple)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(Color(.systemBackground)))
                .overlay(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).strokeBorder(NewCourseStyle.purple, lineWidth: 1.5))
                .contentShape(.rect)
        }
        .buttonStyle(.press)
    }
}

/// 사진 한 장 — 절대 http(s) 주소만 받고, 없거나 못 받으면 브랜드 바탕.
struct NewCoursePhoto: View {
    let url: String?

    var body: some View {
        // 사진은 덮개로 — 채우기 사진이 틀을 키우지 않게.
        NewCourseStyle.brandFill
            .overlay {
                if let url, let parsed = URL(string: url), Self.usable(url) {
                    AsyncImage(url: parsed) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        }
                    }
                }
            }
            .clipped()
    }

    static func usable(_ raw: String) -> Bool {
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased() else { return false }
        return (scheme == "http" || scheme == "https") && url.host?.isEmpty == false
    }
}

/// 묶음 하나 — 위 작은 제목, 흰 칸(모서리 12), 아래 도움말 (웹 `ProtoPlanFlows.tsx` `Group`).
struct NewCourseGroup<Content: View>: View {
    let label: String
    var footnote: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(verbatim: label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                content()
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            if let footnote {
                Text(verbatim: footnote)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// 사진 위의 작은 둥근 단추 — 흰 바탕 32, 누르는 자리 44.
struct PhotoRoundButton: View {
    let symbol: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Color(red: 60 / 255, green: 60 / 255, blue: 67 / 255))
                .frame(width: 32, height: 32)
                .background(Circle().fill(.white.opacity(0.92)))
                .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.press)
        .accessibilityLabel(label)
    }
}

/// 작품 표 — 보라 12% 바탕 알약에 film 과 작품 이름.
struct WorkTag: View {
    let title: String
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 4 : 5) {
            Image(systemName: "film").font(.system(size: compact ? 11 : 12))
            Text(verbatim: title).font(.system(size: 12, weight: compact ? .semibold : .bold)).lineLimit(1)
        }
        .foregroundStyle(NewCourseStyle.purple)
        .padding(.horizontal, compact ? 0 : 9)
        .padding(.vertical, compact ? 0 : 3)
        .background {
            if !compact {
                Capsule().fill(NewCourseStyle.purple.opacity(0.12))
            }
        }
    }
}

/// 칩을 가로로 늘어놓다 넘치면 다음 줄로 — 줄마다 가운데 맞춤(짜는 중 화면의 요약 칩).
struct CenteredFlow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let rows = rows(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: min(width, proposal.width ?? width), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal _: ProposedViewSize, subviews: Subviews, cache _: inout ()) {
        var top = bounds.minY
        for row in rows(width: bounds.width, subviews: subviews) {
            var left = bounds.minX + (bounds.width - row.width) / 2
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: left, y: top), proposal: ProposedViewSize(size))
                left += size.width + spacing
            }
            top += row.height + spacing
        }
    }

    private struct Row {
        var items: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func rows(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.items.isEmpty {
                rows.append(current)
                current = Row()
            }
            current.width = current.items.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.items.append(index)
        }
        if !current.items.isEmpty {
            rows.append(current)
        }
        return rows
    }
}

/// 방금 「도시로」 만든 코스의 안내 띠 (MZ2AZ-547 R6) — 계획 화면 맨 위에 6초 떠 있다 사라진다.
/// 값은 `TabRouter.justCreated`(만든 직후 `courseCreated` 가 켠다).
struct CreatedCourseBanner: View {
    @ObservedObject private var router = TabRouter.shared

    var body: some View {
        Group {
            if let created = router.justCreated {
                Text(verbatim: NewCourseText.createdBanner(city: created.city, days: created.days))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(NewCourseStyle.purple))
                    .shadow(color: .black.opacity(0.14), radius: 10, y: 6)
                    .padding(.horizontal, 16)
                    // 상태줄 아래 + 64(웹 `safe-area + 64`) — 편집 화면이 안전영역을 무시하고 그려 덮개도 맨 위부터 잡힌다(시뮬레이터 실측).
                    .safeAreaPadding(.top)
                    .padding(.top, 64)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
                    .onTapGesture { router.justCreated = nil }
                    .task(id: created) {
                        try? await Task.sleep(for: .seconds(NewCourseText.bannerSeconds))
                        guard !Task.isCancelled else { return }
                        withAnimation(.easeOut(duration: 0.25)) { router.justCreated = nil }
                    }
            }
        }
        .animation(.easeOut(duration: 0.25), value: router.justCreated)
    }
}
