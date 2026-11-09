import SceneApiClient
import SwiftUI

/// 계획 화면의 **보기/편집 · 지도 켜고 끄기 · 장소 넣기** (MZ2AZ-547 P4 E1–E10·E16, 웹 `route/editor/RouteEditor.tsx` 시안).
///
/// 규칙(무엇을 묻고 언제 지도를 맞추나)은 `RouteEditorRules`·`RouteMapToggle`·`CourseAddRules` 에 있고 여기는 그 답을 화면에 옮긴다.
/// 본체(`RouteEditorView.swift`)의 타입 본문 길이(swiftlint 350줄) 때문에 확장으로 뗐다.
extension RouteEditorView {
    // MARK: 머리줄

    /// 보기의 머리줄 — ‹ 뒤로 · 코스 이름(못 고침) · 「편집」(E1).
    var viewBar: some View {
        ZStack {
            Text(course.title).font(.headline).lineLimit(1).frame(maxWidth: 220)
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.title3.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44).contentShape(.rect)
                }
                .accessibilityLabel(Text("뒤로"))
                Spacer()
                Button("편집") { startEdit() }
                    .frame(minWidth: 44, minHeight: 44)
            }
        }
    }

    // MARK: 보기 ↔ 편집

    func startEdit() {
        beforeEdit = course
        mode = .editing
    }

    /// 보기 중에 코스가 바뀌는 길(가이드 초안·카드의 「담기」)이 부른다 — 바뀌기 전 모습을 남기고 편집으로 넘어간다.
    /// 안 넘기면 바뀐 것을 저장할 단추가 없다.
    func beginEditIfViewing() {
        guard mode == .viewing else { return }
        startEdit()
    }

    /// 편집 중 「취소」.
    func cancelTapped() {
        let changed = isNew ? dirty : RouteBridge.changed(from: RouteBridge.outgoing(from: beforeEdit), to: course)
        switch RouteEditorRules.cancel(isNew: isNew, isFresh: fresh, changed: changed) {
        case .close: dismiss()
        case .revertToView: revert()
        case .confirmDiscardThenClose: confirmingDiscard = true
        case .confirmDiscardThenRevert: confirmingRevert = true
        case .confirmLeaveAndDelete: confirmingLeave = true
        }
    }

    /// 편집 전으로 되돌리고 보기로.
    func revert() {
        course = beforeEdit
        endEditing()
    }

    /// 저장했다 — 서버가 준 모습(항목 id 가 붙은)으로 보기로.
    func finishEdit(saved: RouteCourse) {
        course = saved
        opened = RouteBridge.outgoing(from: saved)
        beforeEdit = saved
        endEditing()
    }

    private func endEditing() {
        mode = .viewing
        pinning = false
        addMenuOpen = false
        focusedStop = nil
        dayIndex = min(dayIndex, max(course.days.count - 1, 0))
    }

    /// 방금 만든 코스를 저장 없이 나간다 — 서버에 만들어 둔 것을 지운다(E16).
    func leaveFresh() {
        TabRouter.shared.clearFreshCourse()
        let doomed = course
        Task { await store.delete(doomed) }
        dismiss()
    }

    // MARK: 지도 켜고 끄기

    /// 「지도」/「목록만」 알약 — 왼쪽 아래(16, 16), 검은 바탕(E4). 오른쪽은 해태와 ＋ 가 세로로 쓴다.
    var mapPill: some View {
        Button { toggleMap() } label: {
            let open = mapToggle.pill == .listOnly
            HStack(spacing: 6) {
                Image(systemName: open ? "list.bullet" : "map").font(.system(size: 15))
                Text(open ? tr("목록만") : tr("지도")).font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(Color(.systemBackground))
            .padding(.horizontal, 18).frame(height: 44)
            .background(Capsule().fill(Color(.label)))
            .shadow(color: .black.opacity(0.22), radius: 10, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(mapToggle.isOpen ? .isSelected : [])
        .padding(16)
    }

    func toggleMap() {
        let wasOpen = mapToggle.isOpen
        let fit = mapToggle.toggle()
        if !wasOpen {
            panelDetent = .medium
        } else {
            pinning = false
        }
        if fit {
            fitAfterOpening()
        }
    }

    /// 줄을 눌렀다·핀 찍기를 켰다 — 접혀 있으면 펼친다(E5).
    func revealMap() {
        let wasOpen = mapToggle.isOpen
        if mapToggle.reveal() {
            fitAfterOpening()
        }
        if !wasOpen {
            panelDetent = .medium
        }
    }

    /// 처음 펼칠 때만 코스 전체에 맞춘다 — 서랍이 중간 단에 내려앉은 뒤(인셋이 정해진 뒤)에(`RouteCameraRules.mapFirstOpen`).
    private func fitAfterOpening() {
        guard RouteCameraRules.refits(.mapFirstOpen) else { return }
        Task {
            try? await Task.sleep(for: .milliseconds(380))
            fitToken += 1
        }
    }

    // MARK: 떠 있는 ＋

    /// 해태 동그라미(오른쪽 12, 바닥 76, 46)와 세로 한 줄 — 그 바로 위. 편집 중이고 가이드 창·핀 찍기가 아닐 때만.
    @ViewBuilder var addFab: some View {
        if RouteEditorRules.showsEditTools(mode), !showGuide, !pinning, !trip.isActive {
            ZStack(alignment: .bottomTrailing) {
                if addMenuOpen {
                    Color.black.opacity(0.12)
                        .ignoresSafeArea()
                        .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { addMenuOpen = false } }
                }
                CourseAddFab(open: $addMenuOpen, onMap: { showMapAdd = true }, onSaved: { showSavedAdd = true })
                    .padding(.trailing, 12)
                    .padding(.bottom, 76 + 46 + 10)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        }
    }

    /// 고른 일차에 넣는다 — 다른 일차에 넣었으면 그 일차로 옮겨 보여 주고 지도를 맞춘다(E9·E10·E12).
    func addToDay(_ places: [PlaceSummary], day: Int) {
        let result = CourseAddRules.adding(places, toDay: day, in: course)
        guard result.added > 0 else { return }
        beginEditIfViewing()
        course = result.course
        dayIndex = day
        focusedStop = nil
        if RouteCameraRules.refits(.add) {
            fitToken += 1
        }
        if course.days[day].stops.count >= 2 {
            optimizeNudge = true
        }
    }

    var dayCounts: [Int] {
        course.days.map(\.stops.count)
    }

    // MARK: 창들

    /// 묻는 창·넣기 화면·장소 상세. 본문에 `.background { modePresentations }` 한 줄로 붙는다.
    var modePresentations: some View {
        Color.clear
            .alert(tr("바꾼 내용을 버릴까요?"), isPresented: $confirmingRevert) {
                Button(tr("버리기"), role: .destructive) { revert() }
                Button(tr("계속 편집"), role: .cancel) {}
            }
            .alert(tr("저장하지 않고 나갈까요?"), isPresented: $confirmingLeave) {
                Button(tr("나가기"), role: .destructive) { leaveFresh() }
                Button(tr("계속 편집"), role: .cancel) {}
            } message: {
                Text("방금 만든 코스가 지워져요.")
            }
            .sheet(isPresented: $showSavedAdd) {
                SavedAddSheet(cart: cart, dayCounts: dayCounts, current: dayIndex, taken: takenPlaceIds) { places, day in
                    addToDay(places, day: day)
                }
            }
            .fullScreenCover(isPresented: $showMapAdd) {
                MapAddScreen(dayCounts: dayCounts, current: dayIndex, taken: takenPlaceIds, onAdd: { place, day in
                    addToDay([place], day: day)
                }, onClose: { showMapAdd = false })
                    .environmentObject(store)
            }
            // `PlaceSummary` 는 생성 타입이라 Identifiable 이 아니다 — 있음/없음으로 띄운다.
            .fullScreenCover(isPresented: Binding(get: { detailOf != nil }, set: {
                if !$0 {
                    detailOf = nil
                }
            })) {
                if let place = detailOf {
                    PlaceDetailView(summary: place, load: { try await MyReviewDetailStore.place($0) }) { detailOf = nil }
                        .environmentObject(cart)
                        .environmentObject(store)
                }
            }
    }

    /// 줄 사진의 ⓘ — 촬영지만 장소 상세로 간다(E6). 편의시설·직접 찍은 곳은 사진만.
    func photoOpener(for stop: RouteStop) -> (() -> Void)? {
        guard let placeId = stop.placeId else { return nil }
        return {
            var summary = stop.place
            summary.id = placeId
            detailOf = summary
        }
    }
}
