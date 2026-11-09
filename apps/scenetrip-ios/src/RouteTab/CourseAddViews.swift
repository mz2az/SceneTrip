import SceneApiClient
import SwiftUI

// 계획 화면의 장소 넣기 두 갈래 (MZ2AZ-547 E8–E10, 웹 `proto/ProtoAddMenu.tsx`·`proto/protoCourseAdd.tsx`).
//
// - 「지도에서 추가」(`MapAddScreen`): 검색 탭을 편집 위에 그대로 띄운다. 장소 상세의 「＋ 코스에 추가」 → 일차 고르기.
// - 「보관함에서 추가」(`SavedAddSheet`): 큰 시트에서 담아 둔 곳을 고른 순서대로 체크 → 「N곳 선택 완료」 → 일차 고르기.
// 넣을 곳을 먼저 고르고 일차는 마지막에 고른다(웹 2026-11-07 사용자 결정). 규칙은 `CourseAddRules`·`CourseAddPicker`.

// MARK: - 장소 상세의 「코스에 추가」

/// 코스 편집 위에 띄운 검색 탭에서만 있다 — 장소 상세가 이것을 보고 「코스에 추가」 단추를 그린다. 없으면 아무것도 안 그린다.
struct CourseAddContext {
    /// 이미 코스(어느 일차든)에 든 촬영지 id.
    var taken: Set<Int64>
    /// 이 화면에서 넣은 곳 → 넣은 일차(0부터).
    var addedTo: [Int64: Int]
    /// 「코스에 추가」 — 일차 고르기를 띄운다.
    var ask: (PlaceSummary) -> Void
}

extension EnvironmentValues {
    @Entry var courseAdd: CourseAddContext?
}

/// 장소 상세의 버튼 묶음 맨 위 — 「＋ 코스에 추가」 / 「이미 코스에 있어요」 / 「✓ N일차에 넣었어요」.
struct CourseAddBar: View {
    let place: PlaceSummary
    @Environment(\.courseAdd) private var context

    var body: some View {
        if let context {
            switch CourseAddRules.button(for: place.id, taken: context.taken, addedTo: context.addedTo) {
            case let .added(day):
                Label(String(format: tr("%lld일차에 넣었어요"), day + 1), systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(RoundedRectangle(cornerRadius: 12).fill(TabBar.homePurple.opacity(0.09)))
            case .already:
                addButton(tr("이미 코스에 있어요"), enabled: false) {}
            case .add:
                addButton(tr("코스에 추가"), enabled: true) { context.ask(place) }
            }
        }
    }

    private func addButton(_ title: String, enabled: Bool, run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Label(title, systemImage: "plus")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 12).fill(TabBar.homePurple))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

// MARK: - 일차 고르기

/// 일차 줄 목록 — 「N일차」, 보고 있던 일차에 「지금 보는 날」, 오른쪽에 그 일차에 든 곳 수. 누르면 바로 고른다.
struct CourseDayList: View {
    let dayCounts: [Int]
    let current: Int
    let onPick: (Int) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(dayCounts.indices, id: \.self) { index in
                if index > 0 {
                    Divider().padding(.leading, 16)
                }
                Button { onPick(index) } label: {
                    HStack(spacing: 10) {
                        Text(String(format: tr("%lld일차"), index + 1)).font(.body.weight(.semibold))
                        if index == current {
                            Text("지금 보는 날").font(.caption.weight(.semibold)).foregroundStyle(TabBar.homePurple)
                        }
                        Spacer()
                        Text(caption(dayCounts[index])).font(.subheadline).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 54)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(index == current ? .isSelected : [])
            }
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
    }

    private func caption(_ count: Int) -> String {
        switch CourseAddRules.dayCaption(count) {
        case .empty: tr("비어 있음")
        case let .places(count): String(format: tr("%lld곳"), count)
        }
    }
}

/// 아래에서 올라오는 일차 고르기 — 제목, 일차 목록, 취소.
struct CourseDayPickerSheet: View {
    let title: String
    let dayCounts: [Int]
    let current: Int
    let onPick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 10) {
            Text(title).font(.headline).multilineTextAlignment(.center).padding(.top, 20).padding(.horizontal, 8)
            ScrollView {
                CourseDayList(dayCounts: dayCounts, current: current) { day in
                    onPick(day)
                    dismiss()
                }
            }
            .scrollBounceBehavior(.basedOnSize)
            Button { dismiss() } label: {
                Text("취소").font(.body.weight(.semibold)).foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12).padding(.bottom, 12)
        .background(Color(.systemGroupedBackground))
        .presentationDetents([.height(CGFloat(min(dayCounts.count, 5)) * 54 + 170), .large])
    }
}

// MARK: - 떠 있는 ＋

/// 떠 있는 ＋ — 해태 동그라미(46)와 같은 크기·같은 오른쪽 여백(12)으로 그 위에 선다(E8). 누르면 위로 두 갈래 메뉴.
struct CourseAddFab: View {
    @Binding var open: Bool
    let onMap: () -> Void
    let onSaved: () -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 10) {
            if open {
                VStack(spacing: 0) {
                    menuRow("map", tr("지도에서 추가"), tr("검색 탭처럼 찾아서 넣기")) { pick(onMap) }
                    Divider().padding(.leading, 48)
                    menuRow("bag", tr("보관함에서 추가"), tr("담아 둔 곳 골라 넣기")) { pick(onSaved) }
                }
                .frame(width: 240)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .shadow(color: .black.opacity(0.18), radius: 12, y: 6)
                .transition(.scale(scale: 0.8, anchor: .bottomTrailing).combined(with: .opacity))
            }
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 21, weight: .bold))
                    .foregroundStyle(.white)
                    .rotationEffect(.degrees(open ? 45 : 0))
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(TabBar.homePurple))
                    .shadow(color: .black.opacity(0.22), radius: 8, y: 4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("장소 추가"))
            .coachTarget("editor-add")
        }
    }

    private func pick(_ run: () -> Void) {
        open = false
        run()
    }

    private func menuRow(_ icon: String, _ title: String, _ caption: String, run: @escaping () -> Void) -> some View {
        Button(action: run) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 18)).foregroundStyle(TabBar.homePurple).frame(width: 24)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(caption).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 보관함에서 추가

/// 큰 시트. 1단계: 담아 둔 곳 체크(고른 순서 번호). 2단계(「N곳 선택 완료」): 넣을 일차를 고른다(E10).
struct SavedAddSheet: View {
    @ObservedObject var cart: CartStore
    let dayCounts: [Int]
    let current: Int
    let taken: Set<Int64>
    let onAdd: ([PlaceSummary], Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var picker = CourseAddPicker()
    @State private var choosingDay = false

    private var places: [PlaceSummary] {
        RouteStore.cartPlaces(cart.items.filter { $0.delisted != true }).places
    }

    var body: some View {
        let split = CourseAddRules.split(places, taken: taken)
        NavigationStack {
            Group {
                if choosingDay {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(String(format: tr("고른 %lld곳을 넣을 날을 골라 주세요"), picker.picked.count))
                                .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 4)
                            CourseDayList(dayCounts: dayCounts, current: current) { day in
                                onAdd(picker.ordered(places), day)
                                dismiss()
                            }
                        }
                        .padding(16)
                    }
                    .navigationTitle(tr("몇 일차에 넣을까요?"))
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("이전") { choosingDay = false }
                        }
                    }
                } else {
                    pickList(free: split.free, inCourse: split.inCourse)
                        .navigationTitle(tr("보관함에서 추가"))
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("닫기") { dismiss() }
                            }
                        }
                        .safeAreaInset(edge: .bottom) {
                            if !split.free.isEmpty {
                                doneButton
                            }
                        }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .background(Color(.systemGroupedBackground))
        }
        .presentationDetents([.large])
        .task { await cart.refresh() }
    }

    @ViewBuilder
    private func pickList(free: [PlaceSummary], inCourse: [PlaceSummary]) -> some View {
        if free.isEmpty, inCourse.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "bag").font(.system(size: 30)).foregroundStyle(.tertiary)
                Text("아직 담아 둔 곳이 없어요").font(.subheadline.weight(.semibold))
                Text("「지도에서 추가」 로 찾아 넣어 보세요").font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if !free.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(String(format: tr("담아 둔 곳 %lld"), free.count))
                                    .font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                                Spacer()
                                let ids = free.map(\.id)
                                Button(picker.allPicked(ids) ? tr("모두 풀기") : tr("모두 고르기")) {
                                    picker.toggleAll(ids)
                                }
                                .font(.footnote.weight(.semibold))
                                .tint(TabBar.homePurple)
                            }
                            .padding(.horizontal, 4).frame(minHeight: 32)
                            rows(free, done: false)
                        }
                    }
                    if !inCourse.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(String(format: tr("이미 코스에 있는 곳 %lld"), inCourse.count))
                                .font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                                .padding(.horizontal, 4).frame(minHeight: 32)
                            rows(inCourse, done: true).opacity(0.55)
                        }
                    }
                }
                .padding(16)
            }
        }
    }

    private func rows(_ list: [PlaceSummary], done: Bool) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(list.enumerated()), id: \.element.id) { index, place in
                SavedAddRow(place: place, first: index == 0, order: done ? nil : picker.order(of: place.id), done: done) {
                    picker.toggle(place.id)
                }
            }
        }
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var doneButton: some View {
        let label = switch picker.done {
        case .pickFirst: tr("넣을 곳을 골라 주세요")
        case let .done(count): String(format: tr("%lld곳 선택 완료"), count)
        }
        return Button { choosingDay = true } label: {
            Text(label).font(.body.weight(.bold)).foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(RoundedRectangle(cornerRadius: 14).fill(TabBar.homePurple))
        }
        .buttonStyle(.plain)
        .disabled(picker.picked.isEmpty)
        .opacity(picker.picked.isEmpty ? 0.4 : 1)
        .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 12)
        .background(Color(.systemBackground).ignoresSafeArea())
    }
}

/// 보관함 한 줄 — 사진 56 · 이름/작품/주소 · 고름 표시(고른 순서 번호). 고른 줄은 바탕이 옅은 보라.
private struct SavedAddRow: View {
    let place: PlaceSummary
    let first: Bool
    let order: Int?
    let done: Bool
    let onPress: () -> Void

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 14) {
                RemoteImage(url: place.imageUrl, symbol: "mappin.and.ellipse")
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.vertical, 12)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(place.name).font(.body.weight(.semibold)).lineLimit(1).foregroundStyle(.primary)
                        if let work = place.contents?.first?.title, !work.isEmpty {
                            Text(work).font(.footnote).foregroundStyle(TabBar.homePurple).lineLimit(1)
                        }
                        if let address = place.address {
                            Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    mark
                }
                .frame(maxHeight: .infinity)
                .padding(.trailing, 16)
                .overlay(alignment: .top) {
                    if !first {
                        Divider()
                    }
                }
            }
            .padding(.leading, 14)
            .background(order != nil ? TabBar.homePurple.opacity(0.07) : .clear)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(done)
        .accessibilityAddTraits(order != nil || done ? .isSelected : [])
    }

    @ViewBuilder private var mark: some View {
        if done {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 24)).foregroundStyle(Color(.systemGray3))
        } else if let order {
            Text("\(order)").font(.footnote.weight(.heavy)).foregroundStyle(.white)
                .frame(width: 26, height: 26).background(Circle().fill(TabBar.homePurple))
        } else {
            Circle().strokeBorder(Color(.systemGray3), lineWidth: 1.5).frame(width: 26, height: 26)
        }
    }
}

// MARK: - 지도에서 추가

/// 검색 탭을 그대로 편집 위에 띄운다(E9). 맨 위 줄은 「‹ 코스로」 · 「지도에서 추가」. 장소 상세의 「＋ 코스에 추가」 → 일차 고르기.
struct MapAddScreen: View {
    let dayCounts: [Int]
    let current: Int
    let taken: Set<Int64>
    let onAdd: (PlaceSummary, Int) -> Void
    let onClose: () -> Void

    @EnvironmentObject private var store: RouteStore
    @State private var asking: PlaceSummary?
    @State private var addedTo: [Int64: Int] = [:]

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Text("지도에서 추가").font(.headline)
                HStack {
                    Button(action: onClose) {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left").font(.body.weight(.semibold))
                            Text("코스로")
                        }
                        .frame(minHeight: 44).padding(.horizontal, 8).contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                    Spacer()
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(Color(.systemBackground))
            .overlay(alignment: .bottom) { Divider() }
            SearchTabView()
                .environment(\.tabBarClearance, 0)
                .environment(\.courseAdd, CourseAddContext(taken: taken, addedTo: addedTo) { asking = $0 })
                .environmentObject(store)
        }
        // `PlaceSummary` 는 생성 타입이라 Identifiable 이 아니다 — 있음/없음으로 띄운다.
        .sheet(isPresented: Binding(get: { asking != nil }, set: {
            if !$0 {
                asking = nil
            }
        })) {
            if let place = asking {
                CourseDayPickerSheet(
                    title: String(format: tr("「%@」 을 몇 일차에 넣을까요?"), place.name),
                    dayCounts: dayCounts, current: current
                ) { day in
                    onAdd(place, day)
                    addedTo[place.id] = day
                }
            }
        }
    }
}
