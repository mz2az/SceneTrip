import SwiftUI

/// 경로여정(코스) 화면 — 「내 여행」 코스 목록 (MZ2AZ-547 P7 L1–L5·E17, 웹 `features/proto/ProtoCourse.tsx`).
///
/// 머리: ✕ · 「내 여행」 · 보라 「+ 새 코스」. 「+ 새 코스」 는 **「새 여행 코스」 시트**를 연다 — 도시로 시작 ·
/// 작품으로(AI) · 담은 곳으로의 세 갈래(F1·F2). 고른 흐름은 홈의 덮개(`TabRouter.Cover.newCourse`)로 갈아 끼워 띄운다.
///
/// 목록: 「여행 중」·「다가오는 여행」 묶음, 줄마다 사진·이름·「N일차 · N박 N일 · N곳」·작품(`CourseListRow`).
/// 지우기는 줄 오른쪽 「⋯」 → 「삭제」 → 확인. 줄을 꾹(0.2초) 누르면 여러 개 고르기 → 아래 빨간 「N개 삭제」.
/// 코스를 여는 동안(상세 받기)은 목록을 덮개와 스피너로 덮고, 실패하면 목록 위에 「다시 시도」 띠(E17).
struct RouteTabView: View {
    /// 홈이 덮개로 띄울 때 넘긴다 — 있으면 머리줄 왼쪽에 닫기 단추가 생긴다
    /// (홈 재편: 이 화면은 탭이 아니라 홈의 「내 여행 이어가기」가 연다).
    var onClose: (() -> Void)?

    @EnvironmentObject var store: RouteStore

    @State private var fork = false
    /// 새 코스 시트에서 고른 갈래 — 시트가 다 닫힌 뒤 덮개를 그 흐름으로 갈아 끼운다(MZ2AZ-547 F2).
    @State private var chosenStart: NewCourseStart?
    @StateObject private var opening = RouteCourseOpening()
    @StateObject private var details = CourseListDetails()

    /// 지우기 직전에 한 번 묻는다. `nil` 이면 안 묻는 중이다.
    ///
    /// **되돌릴 수 없다** — 서버에서 지우는 것이라 실행 취소가 없다. 며칠 걸려 짠
    /// 일정이 손가락 한 번에 사라지면 안 된다.
    @State private var doomed: RouteCourse?

    /// 여러 개 고르기(L4) — 꺼져 있으면 `picked == nil`.
    @State private var picking = CourseListPicking()
    @State private var confirmingBulk = false
    @State private var removing = false
    /// 「⋯」 메뉴가 떠 있는 줄의 열쇠.
    @State private var menuKey: String?

    @ObservedObject var router = TabRouter.shared

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if picking.isOn {
                    pickingHeader
                } else {
                    header
                }
                content
                    .overlay(alignment: .top) {
                        // 실패 띠는 목록 위에 뜬다 — 예전엔 목록을 밀어 내렸다(E17).
                        if opening.failedCourse != nil, !opening.isLoading {
                            detailLoadError
                        }
                    }
            }
            .background(Color(.systemGroupedBackground))
            // 코스 속을 받는 동안 목록에 멈춘 것처럼 보이지 않게 덮는다(E17) — 머리까지 덮어 두 번 누르지 못하게.
            .overlay {
                if opening.isLoading {
                    ZStack {
                        Color(.systemGroupedBackground).opacity(0.7)
                        ProgressView().controlSize(.large)
                    }
                    .ignoresSafeArea()
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("불러오는 중입니다"))
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if picking.isOn {
                    deleteBar
                }
            }
            // 내비게이션 바를 접고 머리줄을 직접 그린다 — 바에 단추를 넣으면
            // 시스템이 유리 캡슐(흰 판)을 깔아 피노 색을 가린다(2026-08-28 사용자
            // 지적, X 단추 때와 같은 문제).
            .toolbar(.hidden, for: .navigationBar)
            .task {
                await store.refresh()
                await ensureDemoCourse()
                openPending()
                applyCheckArgument()
            }
            .onChange(of: router.pendingCourseId) { _, _ in openPending() }
            .onChange(of: store.courses.map(CourseListRules.key)) { _, keys in picking.keep(only: keys) }
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
            .confirmationDialog(
                CourseListText.bulkConfirmTitle(count: picking.count),
                isPresented: $confirmingBulk,
                titleVisibility: .visible
            ) {
                Button("삭제", role: .destructive) {
                    Task { await removePicked() }
                }
                Button("취소", role: .cancel) {}
            } message: {
                Text("되돌릴 수 없습니다.")
            }
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
        .fullScreenCover(item: $opening.editing, onDismiss: closedEditor) { course in
            RouteEditorView(course: course, isNew: false)
                // 방금 「도시로」 만든 코스의 안내 띠(R6).
                .overlay(alignment: .top) { CreatedCourseBanner() }
        }
    }

    /// 편집 화면이 닫혔다 — 고친 사진·작품이 줄에 다시 보이게 받아 둔 상세를 잊는다.
    private func closedEditor() {
        details.reset()
        openPlannerIfAsked()
    }

    /// 가이드 챗봇의 한도 안내에서 「AI 로 짜기 열기」 를 눌렀다(`RouteEditorView.leaveForPlanner`) — 「작품으로」(AI) 흐름.
    private func openPlannerIfAsked() {
        guard router.pendingPlanner else { return }
        router.pendingPlanner = false
        router.chooseNewCourse(.works)
    }

    // MARK: 목록

    @ViewBuilder
    private var content: some View {
        if store.courses.isEmpty, store.courseList == .unknown {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    let groups = CourseListRules.sections(store.courses)
                    if store.courses.isEmpty {
                        emptyState
                    }
                    if !groups.running.isEmpty {
                        CourseListGroup(label: tr("여행 중"), courses: groups.running) { row($0, first: $1) }
                    }
                    if !groups.upcoming.isEmpty {
                        CourseListGroup(label: tr("다가오는 여행"), courses: groups.upcoming) { row($0, first: $1) }
                    }
                }
                .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 32)
            }
            .refreshable { await store.refresh() }
        }
    }

    /// 코스가 없을 때(L2) — 「새 코스」 로 첫 여행을 짜라고 말한다. 단추는 머리의 「+ 새 코스」 하나다.
    private var emptyState: some View {
        VStack(spacing: 10) {
            Text("아직 코스가 없어요")
                .font(.system(size: 17, weight: .bold))
            Text("「새 코스」 로 첫 여행을 짜 보세요")
                .font(.system(size: 15))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    private func row(_ course: RouteCourse, first: Bool) -> some View {
        let key = CourseListRules.key(course)
        let detail = details.detail(course)
        let stops = detail?.stops ?? []
        let works = CourseListRules.works(stops) { stop in
            stop.placeId.flatMap { store.workTitles(ofPlace: $0).first }
        }
        return CourseListRow(
            course: course,
            first: first,
            photo: CourseListRules.photo(thumbnail: course.thumbnailUrl, stops: stops),
            works: works,
            picking: picking.isOn,
            checked: picking.isPicked(key),
            menuOpen: Binding(get: { menuKey == key }, set: { menuKey = $0 ? key : nil }),
            onOpen: {
                // 목록 카드에는 일차 속이 없다(`CourseSummary`). 열 때 상세를 받아온다.
                Task { await opening.open(course, using: store) }
            },
            onHold: { picking.hold(key) },
            onToggle: { picking.toggle(key) },
            onDelete: { doomed = course }
        )
        .disabled(opening.isLoading || removing)
        .task(id: key) { await details.load(course, using: store) }
    }

    private var detailLoadError: some View {
        HStack(spacing: 10) {
            Label("코스를 불러오지 못했어요", systemImage: "exclamationmark.circle")
                .font(.subheadline)
            Spacer(minLength: 0)
            Button("다시 시도") {
                Task { await opening.retry(using: store) }
            }
            .buttonStyle(.borderedProminent)
            .tint(NewCourseStyle.purple)
            .controlSize(.small)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(.regularMaterial))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
        .padding(.horizontal, 12).padding(.top, 8)
    }

    // MARK: 동작

    /// 고른 코스를 하나씩 지운다 — 서버에 여러 개 지우기가 없다. 지울 때마다 목록을 다시 받는다(`RouteStore.delete`).
    private func removePicked() async {
        let doomed = CourseListRules.doomed(store.courses, picking: picking)
        removing = true
        await CourseListRules.removeOneByOne(doomed) { await store.delete($0) }
        removing = false
        picking.cancel()
    }

    /// 마이페이지에서 「경로여정에서 열기」로 넘어온 코스를 연다. 쪽지는 한 번
    /// 읽고 버린다 — 남겨 두면 탭에 올 때마다 또 열린다.
    private func openPending() {
        guard let wanted = router.pendingCourseId,
              let course = store.courses.first(where: { $0.serverId == wanted })
        else { return }
        router.pendingCourseId = nil
        Task { await opening.open(course, using: store) }
    }

    /// 확인용 뒷문(MZ2AZ-547 P7) — 시뮬레이터에 합성 탭이 닿지 않아 화면 확인용.
    /// `-courseList menu` 는 첫 줄의 「⋯」 메뉴를, `pick` 은 앞의 두 줄을 고른 여러 개 고르기를, `confirm` 은 그 확인 창을 띄운다.
    /// **지우지는 않는다** — 확인 창의 「삭제」 는 사람이 누른다.
    private func applyCheckArgument() {
        let keys = store.courses.map(CourseListRules.key)
        guard let first = keys.first else { return }
        switch UserDefaults.standard.string(forKey: "courseList") {
        case "menu":
            menuKey = first
        case "pick", "confirm":
            picking.hold(first)
            if keys.count > 1 {
                picking.toggle(keys[1])
            }
            confirmingBulk = UserDefaults.standard.string(forKey: "courseList") == "confirm"
        default:
            break
        }
    }
}

// MARK: - 머리·아래 단추

extension RouteTabView {
    /// 손수 그린 머리줄 — ✕ · 「내 여행」 · 보라 「+ 새 코스」(L1). 아래 얇은 선으로 목록과 나눈다.
    private var header: some View {
        HStack(spacing: 8) {
            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("닫기")
            }
            Text(CourseListText.headerTitle(picking))
                .font(.system(size: 17, weight: .bold))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                fork = true
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "plus").font(.system(size: 13, weight: .bold))
                    Text("새 코스").font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(height: 36)
                .background(Capsule().fill(NewCourseStyle.purple))
                .contentShape(.capsule)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .frame(minHeight: 56)
        .background(Color(.systemBackground))
        .overlay(alignment: .bottom) { Rectangle().fill(Color(.separator)).frame(height: 0.5) }
    }

    /// 고르는 중 머리 — [취소] · 「N개 선택」 · [전체 선택 / 선택 해제] (L4).
    private var pickingHeader: some View {
        let keys = store.courses.map(CourseListRules.key)
        let all = picking.allPicked(keys)
        return HStack(spacing: 8) {
            Button("취소") { picking.cancel() }
                .font(.system(size: 16, weight: .semibold))
                .frame(minHeight: 44)
                .padding(.horizontal, 6)
            Spacer()
            Text(CourseListText.headerTitle(picking))
                .font(.system(size: 17, weight: .bold))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button(CourseListText.allButton(allPicked: all)) { picking.toggleAll(keys) }
                .font(.system(size: 16, weight: .semibold))
                .frame(minHeight: 44)
                .padding(.horizontal, 6)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .disabled(removing)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .frame(minHeight: 56)
        .background(Color(.systemBackground))
        .overlay(alignment: .bottom) { Rectangle().fill(Color(.separator)).frame(height: 0.5) }
    }

    /// 아래 고정 빨간 「N개 삭제」 — 하나도 안 골랐거나 지우는 중이면 흐리게.
    private var deleteBar: some View {
        let off = picking.count == 0 || removing
        return Button {
            confirmingBulk = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "trash").font(.system(size: 16))
                Text(CourseListText.deleteButton(count: picking.count, removing: removing))
                    .font(.system(size: 16, weight: .bold))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(Color.red))
            .opacity(off ? 0.4 : 1)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(off)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Color(.systemBackground))
        .overlay(alignment: .top) { Rectangle().fill(Color(.separator)).frame(height: 0.5) }
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
