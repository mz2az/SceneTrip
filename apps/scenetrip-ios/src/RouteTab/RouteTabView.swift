import SwiftUI

/// 경로여정(코스) 탭 — 첫 화면.
///
/// 8/11 회의에서 확정된 갈림길이 이 화면의 전부다. **코스가 하나도 없으면**
/// 「AI 로 짜기 / 직접 짜기」 두 갈래가 바로 뜨고, **코스가 있으면** 목록을 보여 준 뒤
/// 「코스 추가하기」를 눌렀을 때 같은 두 갈래가 시트로 나온다.
/// > *"코스 추가에 직접 짜는 거하고 AI가 짜는 거하고 나눠져 있는데, 만약에 여기
/// > 아무것도 없으면 저걸 넣는다"*
///
/// 갈림길은 **기능이 있다는 것을 알리는 장치**이기도 하다 — *"우리한테 이런 기능도
/// 있다, 이런 걸 알려주기 위한 느낌"*. 그래서 두 카드는 크기·구조가 대등하다.
/// 한쪽에 「추천」 배지를 달면 다른 쪽을 고른 사람이 자기 선택을 이상하게 느낀다.
///
/// **서버가 없다.** 이 탭은 통째로 목 데이터로 돈다 — `RouteMockData.swift` 머리말 참고.
struct RouteTabView: View {
    @EnvironmentObject private var store: RouteStore

    @State private var fork = false
    @State private var wizard: RouteWizardKind?
    @State private var editing: RouteCourse?
    @State private var market = false

    var body: some View {
        NavigationStack {
            Group {
                if store.courses.isEmpty {
                    emptyState
                } else {
                    courseList
                }
            }
            .navigationTitle("경로여정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        market = true
                    } label: {
                        Label("인기 코스", systemImage: "square.grid.2x2")
                    }
                }
            }
        }
        .sheet(isPresented: $fork) { forkSheet }
        .sheet(item: $wizard) { kind in
            RouteWizardView(kind: kind)
        }
        .fullScreenCover(item: $editing) { course in
            RouteEditorView(course: course, isNew: false)
        }
        .sheet(isPresented: $market) {
            RouteMarketView()
        }
    }

    // MARK: 코스가 없을 때

    private var emptyState: some View {
        VStack(spacing: 16) {
            RouteAIBanner(text: "AI 가 일정을 짜 드립니다")
                .padding(.horizontal, 16)

            Text("코스를 어떻게 만들까요?")
                .font(.title3.weight(.semibold))

            RouteForkCards(
                onAI: { wizard = .aiPlan },
                onManual: { wizard = .manual }
            )
            .padding(.horizontal, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }

    /// 코스가 있을 때 「코스 추가하기」가 여는 시트. 첫 화면과 **같은 두 갈래**다.
    private var forkSheet: some View {
        VStack(spacing: 16) {
            Capsule().fill(Color(.systemGray3)).frame(width: 40, height: 5)
                .padding(.top, 8)
            Text("코스를 어떻게 만들까요?").font(.headline)
            RouteForkCards(
                onAI: {
                    fork = false
                    wizard = .aiPlan
                },
                onManual: {
                    fork = false
                    wizard = .manual
                }
            )
            .padding(.horizontal, 16)
            Spacer(minLength: 0)
        }
        .presentationDetents([.height(420)])
    }

    // MARK: 코스 목록

    private var courseList: some View {
        List {
            // 진행 중인 코스를 맨 위에 따로 모은다 — 여행 중에는 그것 말고 볼 것이 없다.
            let running = store.courses.filter(\.isRunning)
            if !running.isEmpty {
                Section("여행 중") {
                    ForEach(running) { course in
                        row(course)
                    }
                }
            }
            Section {
                ForEach(store.courses.filter { !$0.isRunning }) { course in
                    row(course)
                }
                .onDelete { offsets in
                    let targets = store.courses.filter { !$0.isRunning }
                    offsets.map { targets[$0] }.forEach { store.delete($0) }
                }
            } header: {
                Text("내 코스 \(store.courses.count)")
            }

            // 「코스 추가하기」를 **목록의 한 행**으로 둔다. 처음에는 Section 의 footer 에
            // 넣었는데, footer 는 iOS 가 회색 작은 글씨로 그리는 자리라 버튼이 눌리지
            // 않는 것처럼 보였다(실측).
            Section {
                Button {
                    fork = true
                } label: {
                    Label("코스 추가하기", systemImage: "plus")
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .foregroundStyle(Color.accentColor)
            }
        }
        .listStyle(.insetGrouped)
    }

    private func row(_ course: RouteCourse) -> some View {
        Button {
            editing = course
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    if course.madeByAI {
                        Image(systemName: "sparkles")
                            .font(.caption)
                            .foregroundStyle(Color.accentColor)
                    }
                    Text(course.title).font(.headline)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption).foregroundStyle(.tertiary)
                }
                // 날짜를 안 정한 코스는 그 자리에 기간만 남는다 (회의 확정: 날짜는 선택).
                Text(course.dateLabel ?? course.spanLabel)
                    .font(.caption).foregroundStyle(.secondary)
                Text("\(course.stops.count)곳 · 직선 \(RouteFormat.kilometers(distance(course)))")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            .padding(.vertical, 4)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /// 일차별 이동거리의 합. **일차를 넘나드는 이동은 세지 않는다** — 자고 일어나
    /// 다음 날 처음 가는 곳까지는 그날의 동선이 아니다.
    private func distance(_ course: RouteCourse) -> Double {
        course.days.reduce(0) { $0 + RouteGeometry.totalKilometers($1.stops) }
    }
}

/// 「AI 로 짜기 / 직접 짜기」 두 카드. **대등하다** — 크기·구조·설명 길이가 같다.
struct RouteForkCards: View {
    let onAI: () -> Void
    let onManual: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            card(
                title: "AI 로 짜기",
                caption: "기간·작품만 고르면 동선까지 짜 드립니다",
                symbol: "sparkles",
                action: onAI
            )
            card(
                title: "직접 짜기",
                caption: "장바구니에서 하나씩 담습니다",
                symbol: "hand.draw",
                action: onManual
            )
        }
    }

    private func card(
        title: String,
        caption: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.accentColor.opacity(0.12)))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Text(caption).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground))
                    .shadow(color: .black.opacity(0.06), radius: 4, y: 2)
            )
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// AI 가 일한다는 것을 화면에 남기는 띠.
///
/// 8/11 회의에서 가장 강하게 요구된 항목이다 — 목업만 보면 사용자가 하나하나 직접
/// 고르는 화면으로 읽힌다는 지적이었다.
/// > *"AI로 일정을 짜 드리겠습니다 같은 말이 있잖아요. 지금은 그게 없으니까 …
/// > 이것만 보면 내가 하나하나 다 해야 되는 걸로 보이거든요"*
///
/// 같은 회의에서 **글자 수를 줄이라**는 지적도 함께 나왔으므로 한 줄로만 쓴다.
struct RouteAIBanner: View {
    let text: String
    var tint: Color = .accentColor

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles").font(.footnote.weight(.semibold))
            Text(text).font(.footnote.weight(.medium))
            Spacer(minLength: 0)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 12).padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 10).fill(tint.opacity(0.10)))
    }
}

enum RouteWizardKind: String, Identifiable {
    case aiPlan, manual
    var id: String {
        rawValue
    }
}
