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

    /// 「내 코스 / 코스마켓」. 목업의 `S.homeSeg` 와 같은 자리다.
    @State private var segment: Segment = .mine

    enum Segment: String, CaseIterable, Identifiable {
        case mine = "내 코스"
        case market = "코스마켓"
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // **세그먼트가 마켓으로 가는 길이다.** 전에는 오른쪽 위 아이콘 버튼
                // 하나였는데, 목업은 「내 코스」와 대등한 자리로 두었다 — 마켓은
                // 곁다리가 아니라 이 탭의 절반이다.
                Picker("", selection: $segment) {
                    ForEach(Segment.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 14)
                .padding(.bottom, 8)

                switch segment {
                case .mine:
                    if store.courses.isEmpty {
                        emptyState
                    } else {
                        courseList
                    }
                case .market:
                    RouteMarketView(embedded: true)
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("코스")
        }
        .sheet(isPresented: $fork) { forkSheet }
        .sheet(item: $wizard) { kind in
            RouteWizardView(kind: kind)
        }
        .fullScreenCover(item: $editing) { course in
            RouteEditorView(course: course, isNew: false)
        }
    }

    // MARK: 코스가 없을 때

    /// 첫 사용자 화면. **갈림길이 아니라 빈 상태다.**
    ///
    /// 전에는 「코스를 어떻게 만들까요?」와 카드 두 장을 첫 화면에 그대로 두었는데,
    /// 목업에서 그 물음은 **코스가 이미 있을 때 올라오는 액션시트**다(`sheet-newcourse`).
    /// 둘을 한 화면에 뭉치니 위쪽 500pt 가 통째로 비었다(실측).
    ///
    /// 그래서 여기서는 사용자에게 상태를 말하고(*"아직 만든 코스가 없습니다"*), 할 일을
    /// 버튼 둘로 준다. AI 를 채운 버튼으로 두는 것은 회의 확정 사항이다 —
    /// *"AI가 짜 준다는 문구를 넣는다"* (정승길, 8/11). 다만 별도 띠로 광고하지 않고
    /// 설명 문장과 버튼 이름에 녹인다.
    private var emptyState: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(
                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 56, height: 56)
                    Image(systemName: "sparkles")
                        .font(.system(size: 26, weight: .medium))
                        .foregroundStyle(.white)
                }
                .padding(.bottom, 6)

                Text("아직 만든 코스가 없습니다")
                    .font(.headline)
                Text("보고 싶은 작품과 기간만 고르면,\n촬영지를 이어서 일차별 일정으로 짜 드립니다")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20)

            VStack(spacing: 10) {
                Button { wizard = .aiPlan } label: {
                    Label("AI 로 여정 짜기", systemImage: "sparkles")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button { wizard = .manual } label: {
                    Text("직접 짜기")
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
            .padding(.horizontal, 20)
            .padding(.top, 22)

            Spacer(minLength: 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
