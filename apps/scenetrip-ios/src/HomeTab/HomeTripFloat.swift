import SwiftUI

/// 홈 오른쪽 아래에 떠 있는 「여행 코스 짜기」 단추 (MZ2AZ-547 H4, 웹 `proto/ProtoScreens.tsx` `TripFloatBar`).
///
/// 홈 맨 위의 「내 여행 이어가기」 카드를 대신한다(H5). 모습과 동작은 `HomeTripFloatRules` 가 정한다 —
/// 여행 중 코스가 있으면 단추가 곧 그 코스, 없으면 「여행 코스 짜기」. 펼치면 같은 줄에서 단추가 오그라들고
/// 오른쪽 끝부터 ✕ · ≡ · 「+ 새 코스」 · 내 코스(최대 5)가 45ms 간격으로 미끄러져 들어온다. 움직임 줄이기면 바로 바뀐다.
struct HomeTripFloat: View {
    /// 줄에 올릴 코스 — `HomeTripFloatRules.courses` 를 거친 것.
    let courses: [RouteCourse]
    let courseList: RouteStore.CourseListState
    let onOpenCourse: (Int64) -> Void
    let onAll: () -> Void
    /// 「+ 새 코스」·코스 0개의 「여행 코스 짜기」. **P3 가 새 코스 시트(F1)로 바꿀 자리**다(`TabRouter.startNewCourse`).
    let onNew: () -> Void

    /// 확인용 뒷문 — `simctl launch … -homeFloatOpen 1` 이면 펼친 채로 뜬다(시뮬레이터에 합성 탭이 닿지 않아 화면 확인용).
    @State private var open = UserDefaults.standard.bool(forKey: "homeFloatOpen")
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let ease = Animation.timingCurve(0.22, 1, 0.36, 1, duration: 0.42)

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                collapsed
                    .padding(.trailing, 14)
                    .opacity(open ? 0 : 1)
                    .scaleEffect(open ? 0.8 : 1, anchor: .trailing)
                    .animation(reduceMotion ? nil : .timingCurve(0.22, 1, 0.36, 1, duration: 0.3), value: open)
                    .allowsHitTesting(!open)
                    .accessibilityHidden(open)
                row(width: geo.size.width)
                    .allowsHitTesting(open)
                    .accessibilityHidden(!open)
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .trailing)
        }
        .frame(height: 56)
    }

    // MARK: 접힌 단추

    @ViewBuilder private var collapsed: some View {
        switch HomeTripFloatRules.collapsed(courses) {
        case .plan:
            Button {
                switch HomeTripFloatRules.planTap(courseList: courseList, courseCount: courses.count) {
                case .newCourse: onNew()
                case .expand: open = true
                }
            } label: {
                mainPill(symbol: "paperplane.fill", title: tr("여행 코스 짜기"))
            }
            .buttonStyle(.plain)
        case let .running(course):
            HStack(spacing: 8) {
                Button { open = true } label: { round(symbol: "list.bullet") }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tr("내 코스 펼치기"))
                Button {
                    if let id = course.serverId {
                        onOpenCourse(id)
                    }
                } label: {
                    mainPill(symbol: "location.north.fill", title: course.title)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: tr("여행 중: %@ 열기"), course.title))
            }
        }
    }

    private func mainPill(symbol: String, title: String) -> some View {
        HStack(spacing: 7) {
            Image(systemName: symbol).font(.system(size: 14))
            // 알약 전체 최대 240 — 아이콘·여백을 뺀 몫만 글자에 준다.
            CappedWidth(max: 185) {
                Text(title).font(.system(size: 14, weight: .bold)).lineLimit(1)
            }
        }
        .foregroundStyle(.white)
        .padding(.leading, 14).padding(.trailing, 16)
        .frame(height: 44)
        .background(Capsule().fill(TabBar.homePurple))
        .floatShadow()
        .contentShape(Capsule())
    }

    private func round(symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(TabBar.homePurple)
            .frame(width: 44, height: 44)
            .background(Circle().fill(Color(.systemBackground)))
            .floatShadow()
            .contentShape(Circle())
    }

    // MARK: 펼친 줄

    /// 오른쪽 끝(단추 자리)부터 늘어서고 옆으로 끌어 넘긴다. 코스가 많아 줄이 넘치면 처음엔 오른쪽 끝이 보인다.
    private func row(width: CGFloat) -> some View {
        let items = HomeTripFloatRules.items(courses)
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                // 화면에는 왼쪽부터 그리므로 뒤집는다 — 맨 오른쪽이 ✕.
                ForEach(Array(items.enumerated().reversed()), id: \.offset) { index, item in
                    itemView(item)
                        .opacity(open ? 1 : 0)
                        .offset(x: open ? 0 : 28)
                        .scaleEffect(open ? 1 : 0.92)
                        .animation(
                            reduceMotion ? nil : Self.ease.delay(HomeTripFloatRules.delay(index: index, open: open)),
                            value: open
                        )
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 6)
            .frame(minWidth: width, alignment: .trailing)
        }
        .defaultScrollAnchor(.trailing)
        .scrollClipDisabled()
    }

    @ViewBuilder
    private func itemView(_ item: HomeTripFloatRules.Item) -> some View {
        switch item {
        case .close:
            Button { open = false } label: { round(symbol: "xmark") }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("접기"))
        case .all:
            Button {
                open = false
                onAll()
            } label: { round(symbol: "list.bullet") }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("전체 코스 보기"))
        case .newCourse:
            Button {
                open = false
                onNew()
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "plus").font(.system(size: 14, weight: .bold))
                    Text("새 코스").font(.system(size: 14, weight: .bold)).lineLimit(1)
                }
                .foregroundStyle(TabBar.homePurple)
                .padding(.horizontal, 16)
                .frame(height: 44)
                .background(Capsule().fill(Color(.systemBackground)))
                .floatShadow()
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        case let .course(course):
            Button {
                open = false
                if let id = course.serverId {
                    onOpenCourse(id)
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: course.isRunning ? "location.north.fill" : "paperplane")
                        .font(.system(size: 13))
                    // 알약 전체 최대 220.
                    CappedWidth(max: 165) {
                        Text(course.title).font(.system(size: 14, weight: .bold)).lineLimit(1)
                    }
                }
                .foregroundStyle(course.isRunning ? .white : TabBar.homePurple)
                .padding(.horizontal, 16)
                .frame(height: 44)
                .background(Capsule().fill(course.isRunning ? TabBar.homePurple : Color(.systemBackground)))
                .floatShadow()
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
    }
}

/// 자식에게 `max` 보다 넓게 주지 않는다 — 가로 스크롤 줄처럼 폭 제안이 없는 곳에서도 긴 코스 이름이 말줄임된다.
/// (`frame(maxWidth:)` 는 제안이 없으면 자식을 이상적인 폭으로 그려 넘친다.)
private struct CappedWidth: Layout {
    let max: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let width = Swift.min(proposal.width ?? max, max)
        return subviews.first?.sizeThatFits(ProposedViewSize(width: width, height: proposal.height)) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal _: ProposedViewSize, subviews: Subviews, cache _: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}

private extension View {
    /// 화면 위에 떠 있는 것의 그림자 — 웹 `shadow.float`(0 6 20 / 14%).
    func floatShadow() -> some View {
        shadow(color: .black.opacity(0.14), radius: 10, y: 6)
    }
}
