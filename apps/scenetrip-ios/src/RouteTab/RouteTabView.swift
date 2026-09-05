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
    /// 홈이 덮개로 띄울 때 넘긴다 — 있으면 머리줄 왼쪽에 닫기 단추가 생긴다
    /// (홈 재편: 이 화면은 탭이 아니라 홈의 「내 여행 이어가기」가 연다).
    var onClose: (() -> Void)?

    /// 「둘러보기」 세그먼트로 열지 — 홈의 「여행자들의 코스」가 켠다.
    var startInMarket = false

    @EnvironmentObject var store: RouteStore

    @State private var fork = false
    @State private var wizard: RouteWizardKind?
    @State var editing: RouteCourse?

    /// 지우기 직전에 한 번 묻는다. `nil` 이면 안 묻는 중이다.
    ///
    /// **되돌릴 수 없다** — 서버에서 지우는 것이라 실행 취소가 없다. 며칠 걸려 짠
    /// 일정이 손가락 한 번에 사라지면 안 된다.
    @State private var doomed: RouteCourse?

    /// 「내 코스 / 코스마켓」. 목업의 `S.homeSeg` 와 같은 자리다.
    @State private var segment: Segment = .mine

    /// 마이페이지가 남긴 쪽지(열어 줄 코스)를 읽는다.
    @ObservedObject var router = TabRouter.shared

    enum Segment: String, CaseIterable, Identifiable {
        case mine = "내 코스"
        /// 「코스마켓」이었다(2026-09-02 개명). 마켓은 돈이 오가고 보기 전에 산다는
        /// 뜻을 풍기는데, 이 화면은 남(유저·운영진)이 짠 코스를 구경하고 마음에 들면
        /// 내 코스로 담는 곳이다. 홈에서는 절 제목 「여행자들의 코스」로 부른다.
        /// 코드 식별자(`RouteMarketView`, 서버 `/market/courses`)는 계약에 걸려 있어
        /// 그대로다 — 개명은 백엔드와 함께 티켓으로.
        case market = "둘러보기"
        var id: String {
            rawValue
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
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
            // 내비게이션 바를 접고 머리줄을 직접 그린다 — 바에 단추를 넣으면
            // 시스템이 유리 캡슐(흰 판)을 깔아 피노 색을 가린다(2026-08-28 사용자
            // 지적, X 단추 때와 같은 문제).
            .toolbar(.hidden, for: .navigationBar)
            .onAppear {
                if startInMarket {
                    segment = .market
                }
            }
            .task {
                await store.refresh()
                await ensureDemoCourse()
                openPending()
            }
            .onChange(of: router.pendingCourseId) { _, _ in openPending() }
            .confirmationDialog(
                doomed.map { "「\($0.title)」을 지울까요?" } ?? "",
                isPresented: Binding(get: { doomed != nil }, set: {
                    if !$0 {
                        doomed = nil
                    }
                }),
                titleVisibility: .visible
            ) {
                Button("삭제", role: .destructive) {
                    guard let course = doomed else { return }
                    doomed = nil
                    Task { await store.delete(course) }
                }
                Button("취소", role: .cancel) { doomed = nil }
            } message: {
                Text("되돌릴 수 없습니다.")
            }
            .refreshable { await store.refresh() }
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

    /// 손수 그린 머리줄. 큰 제목 대신 **코스 추가**가 그 자리를 쓴다 — 맨 아래
    /// 행이던 시절에는 코스가 쌓일수록 추가하러 끝까지 내려가야 했다. 이 탭의
    /// 첫 행동이라 피노 색으로 늘 반짝인다.
    private var header: some View {
        ZStack {
            Text("코스").font(.headline)
            HStack {
                if let onClose {
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.body.weight(.semibold))
                            .padding(8)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("닫기")
                }
                Spacer()
                if segment == .mine {
                    Button {
                        fork = true
                    } label: {
                        Label("코스 추가", systemImage: "plus")
                            .labelStyle(.titleAndIcon)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 10).padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .modifier(PinoNudge(on: true, cornerRadius: 15))
                }
            }
        }
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 8)
    }

    // MARK: 코스 목록

    private var courseList: some View {
        List {
            // 진행 중인 코스를 맨 위에 따로 모은다 — 여행 중에는 그것 말고 볼 것이 없다.
            let running = store.courses.filter(\.isRunning)
            let planned = store.courses.filter { !$0.isRunning }
            if !running.isEmpty {
                Section("여행 중 \(running.count)") {
                    ForEach(running) { course in
                        row(course)
                    }
                }
            }
            // 헤더 숫자는 **이 절에 나열되는 것만** 센다 — 전체를 세니 「내 코스 3」 아래가
            // 텅 빈 채였다(2026-09-05 사용자 지적: 셋 다 여행 중이었다).
            Section {
                if planned.isEmpty {
                    Text(running.isEmpty ? "아직 만든 코스가 없습니다" : "모든 코스가 여행 중이에요")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(planned) { course in
                    row(course)
                }
            } header: {
                Text("예정 \(planned.count)")
            }
        }
        .listStyle(.insetGrouped)
    }

    /// 마이페이지에서 「경로여정에서 열기」로 넘어온 코스를 연다. 쪽지는 한 번
    /// 읽고 버린다 — 남겨 두면 탭에 올 때마다 또 열린다.
    private func openPending() {
        guard let wanted = router.pendingCourseId,
              let course = store.courses.first(where: { $0.serverId == wanted })
        else { return }
        router.pendingCourseId = nil
        Task { editing = await store.detail(course) ?? course }
    }

    private func row(_ course: RouteCourse) -> some View {
        rowButton(course)
            // **`.onDelete` 만으로는 아무도 못 찾는다.** 밀어야 나오는 것을 알려 주는
            // 표시가 화면에 없어서다(2026-08-24 사용자 지적). 글자가 붙은 빨간 버튼으로
            // 바꾸면 한 번 밀어 본 사람에게는 무엇인지 분명해진다. 처음 여는 사람을
            // 위한 길은 따로 있다 — 코스를 열면 맨 아래에 「코스 삭제」가 있다.
            //
            // 「여행 중」 코스도 지울 수 있어야 한다. 앞서 그 섹션에는 삭제가 아예
            // 없어서, 시작해 둔 코스는 **여행을 끝내기 전까지 지울 방법이 없었다.**
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) {
                    doomed = course
                } label: {
                    Label("삭제", systemImage: "trash")
                }
            }
    }

    private func rowButton(_ course: RouteCourse) -> some View {
        Button {
            // 목록 카드에는 일차 속이 없다(`CourseSummary`). 열 때 상세를 받아온다 —
            // 목록에서 전부 받아 두면 코스가 많을 때 첫 화면이 그만큼 느려진다.
            Task { editing = await store.detail(course) ?? course }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    if course.madeByAI {
                        Image(systemName: "sparkles")
                            .font(.caption)
                            .foregroundStyle(Color.accentColor)
                    }
                    Text(course.title).font(.subheadline.weight(.semibold))
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption).foregroundStyle(.tertiary)
                }
                // 날짜를 안 정한 코스는 그 자리에 기간만 남는다 (회의 확정: 날짜는 선택).
                // 곳 수·직선거리는 지웠다(2026-08-28) — 목록 카드에는 일차 속이
                // 없어(`CourseSummary`) 늘 「0곳 · 0 km」였다. 틀린 숫자는 없느니만
                // 못하고, 행도 그만큼 얇아진다.
                Text(course.dateLabel ?? course.spanLabel)
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
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
