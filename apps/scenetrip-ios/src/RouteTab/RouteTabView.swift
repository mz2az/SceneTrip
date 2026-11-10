import SwiftUI

/// 경로여정(코스) 탭 — 첫 화면.
///
/// 내 코스 목록. 「코스 추가」(코스가 없으면 「새 코스 만들기」)는 **「새 여행 코스」 시트**를 연다 — 도시로 시작 ·
/// 작품으로(AI) · 담은 곳으로의 세 갈래(MZ2AZ-547 F1·F2, 웹 시안). 8/11 회의의 「AI 로 짜기 / 직접 짜기」 두 갈래를 대신한다.
/// 고른 흐름은 홈의 덮개(`TabRouter.Cover.newCourse`)로 갈아 끼워 띄운다.
///
/// **서버가 없다.** 이 탭은 통째로 목 데이터로 돈다 — `RouteMockData.swift` 머리말 참고.
struct RouteTabView: View {
    /// 홈이 덮개로 띄울 때 넘긴다 — 있으면 머리줄 왼쪽에 닫기 단추가 생긴다
    /// (홈 재편: 이 화면은 탭이 아니라 홈의 「내 여행 이어가기」가 연다).
    var onClose: (() -> Void)?

    @EnvironmentObject var store: RouteStore

    @State private var fork = false
    /// 새 코스 시트에서 고른 갈래 — 시트가 다 닫힌 뒤 덮개를 그 흐름으로 갈아 끼운다(MZ2AZ-547 F2).
    @State private var chosenStart: NewCourseStart?
    @StateObject private var opening = RouteCourseOpening()

    /// 지우기 직전에 한 번 묻는다. `nil` 이면 안 묻는 중이다.
    ///
    /// **되돌릴 수 없다** — 서버에서 지우는 것이라 실행 취소가 없다. 며칠 걸려 짠
    /// 일정이 손가락 한 번에 사라지면 안 된다.
    @State private var doomed: RouteCourse?

    @ObservedObject var router = TabRouter.shared

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                if opening.failedCourse != nil {
                    detailLoadError
                }
                // 「둘러보기」(코스마켓) 세그먼트가 있었다 — 쓰지 않는 기능이라 걷어냈다
                // (2026-10-03 팀 회의, MZ2AZ-350). 이 화면은 내 코스 하나다.
                if store.courses.isEmpty {
                    emptyState
                } else {
                    courseList
                }
            }
            .background(Color(.systemGroupedBackground))
            // 내비게이션 바를 접고 머리줄을 직접 그린다 — 바에 단추를 넣으면
            // 시스템이 유리 캡슐(흰 판)을 깔아 피노 색을 가린다(2026-08-28 사용자
            // 지적, X 단추 때와 같은 문제).
            .toolbar(.hidden, for: .navigationBar)
            .task {
                await store.refresh()
                await ensureDemoCourse()
                openPending()
            }
            .onChange(of: router.pendingCourseId) { _, _ in openPending() }
            .signInSheet()
            .nicknameSheet()
            .confirmationDialog(
                doomed.map { String(format: tr("「%@」을 지울까요?"), $0.title) } ?? "",
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
        // 「새 여행 코스」 시트(MZ2AZ-547 F1·F2) — 예전 「AI 로 짜기 / 직접 짜기」 두 갈래를 대신한다.
        .sheet(isPresented: $fork, onDismiss: {
            guard let start = chosenStart else { return }
            chosenStart = nil
            router.chooseNewCourse(start)
        }) {
            NewCourseSheet { start in
                chosenStart = start
                fork = false
            }
            .environmentObject(store)
        }
        // 편집 화면이 **다 닫힌 뒤에** 「작품으로」 흐름을 띄운다 — 닫히는 중에 띄우면 SwiftUI 가 버린다.
        .fullScreenCover(item: $opening.editing, onDismiss: openPlannerIfAsked) { course in
            RouteEditorView(course: course, isNew: false)
                // 방금 「도시로」 만든 코스의 안내 띠(R6).
                .overlay(alignment: .top) { CreatedCourseBanner() }
        }
    }

    /// 가이드 챗봇의 한도 안내에서 「AI 로 짜기 열기」 를 눌렀다(`RouteEditorView.leaveForPlanner`) — 「작품으로」(AI) 흐름.
    private func openPlannerIfAsked() {
        guard router.pendingPlanner else { return }
        router.pendingPlanner = false
        router.chooseNewCourse(.works)
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

            // 세 갈래(도시로·작품으로·담은 곳으로)는 새 코스 시트가 고른다(MZ2AZ-547 F2).
            NewCoursePrimaryButton(tr("새 코스 만들기")) { fork = true }
                .padding(.horizontal, 20)
                .padding(.top, 22)

            Spacer(minLength: 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// 손수 그린 머리줄. 큰 제목 대신 **코스 추가**가 그 자리를 쓴다 — 맨 아래
    /// 행이던 시절에는 코스가 쌓일수록 추가하러 끝까지 내려가야 했다. 이 탭의
    /// 첫 행동이라 피노 색으로 늘 반짝인다.
    private var header: some View {
        ZStack {
            Text(tr("코스", at: "화면 제목")).font(.headline)
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
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 8)
    }

    // MARK: 코스 목록

    private var detailLoadError: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("코스를 불러오지 못했어요", systemImage: "exclamationmark.circle")
                .font(.subheadline)
            Button("다시 시도") {
                Task { await opening.retry(using: store) }
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

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
                    Text(running.isEmpty ? tr("아직 만든 코스가 없습니다") : tr("모든 코스가 여행 중이에요"))
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
        guard let wanted = router.pendingCourseId else { return }
        guard let course = store.courses.first(where: { $0.serverId == wanted }) else {
            // 확인용 뒷문(`-openCourseId`)에 줄 번호를 찾을 수 있게 — 목록에 있는 코스 번호를 남긴다.
            let ids = store.courses.compactMap(\.serverId).map(String.init).joined(separator: ",")
            RouteEditorView.log.debug("열 코스 없음 wanted=\(wanted, privacy: .public) 목록=\(ids, privacy: .public)")
            return
        }
        router.pendingCourseId = nil
        Task { await opening.open(course, using: store) }
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
            Task { await opening.open(course, using: store) }
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
        .disabled(opening.isLoading)
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
