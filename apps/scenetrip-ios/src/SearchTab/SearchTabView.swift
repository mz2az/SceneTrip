import SwiftUI

/// 작품검색 탭 (계획서 §3).
///
/// 지도는 항상 보이고 그 위에 바텀시트가 얹혀 드릴다운한다 — 목록 → 작품 상세 →
/// 촬영지 상세. 목록은 작품 / 장소 두 탭이며 **검색어 하나로 둘이 동시에 채워진다**
/// (§3-2). 데이터는 아직 목데이터이고, 생성 클라이언트가 붙으면 SceneData 만 갈린다.
struct SearchTabView: View {
    @StateObject private var data = SceneData()

    @State private var draft = ""
    @State private var query = ""
    @State private var tab: Tab = .work
    @State private var chip = CategoryChip.all
    @State private var detent: BottomSheet<AnyView>.Detent = .medium
    @State private var selectedWork: Work?
    @State private var selectedPlace: SceneRow?
    @State private var fitToken = 0
    @FocusState private var searchFocused: Bool

    enum Tab: String, CaseIterable { case work = "작품", place = "장소" }

    // MARK: - 결과

    /// 검색어에 걸리는 촬영지. 드릴다운 중이면 그 범위로 좁힌다.
    ///
    /// 검색어가 비어 있으면 전체를 보여 준다 — 첫 진입에 지도가 비어 있으면
    /// "지도는 항상 보인다"(§3-1)가 무의미해진다. 프로토타입도 같다.
    private var searchRows: [SceneRow] {
        if let place = selectedPlace {
            return [place]
        }
        if let work = selectedWork {
            return work.rows
        }
        guard !query.isEmpty else { return data.rows }
        return data.rows.filter { SceneSearch.matches($0, query) }
    }

    /// 화면에 실제로 쓰는 촬영지 — 칩까지 적용한 것.
    ///
    /// **목록과 지도가 이것을 같이 쓴다** (§3-5 확정). 프로토타입은 칩이 목록만
    /// 좁히고 지도 핀은 검색 결과 전체를 유지했으나, 실제 숫자를 보고 뒤집었다 —
    /// 도깨비 촬영지 58 곳 중 음식점은 12 곳이라, 목록에 없는 핀 46 개가 남으면
    /// 그 핀을 눌렀을 때 목록에 없는 장소가 열린다.
    private var placeRows: [SceneRow] {
        guard chip != CategoryChip.all else { return searchRows }
        return searchRows.filter { CategoryChip.of($0.placeType) == chip }
    }

    private var workRows: [Work] {
        query.isEmpty ? data.works : SceneSearch.works(data.works, query: query)
    }

    private var suggestions: [Suggestion] {
        SceneSearch.suggestions(rows: data.rows, works: data.works, query: draft)
    }

    // MARK: - 화면

    var body: some View {
        ZStack(alignment: .top) {
            // 카메라는 fitToken 이 오를 때만 움직인다. 칩은 올리지 않는다 (§3-5).
            NaverMapView(pins: placeRows, fitToken: fitToken) { row in
                selectedPlace = row
                tab = .place
                detent = .medium
            }
            .ignoresSafeArea()

            BottomSheet(detent: $detent, topInset: 108) {
                AnyView(sheetContent)
            }

            searchBar

            if searchFocused {
                suggestionList
                    .padding(.top, 104)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: searchFocused)
    }

    // MARK: 검색바

    private var searchBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("작품·배우·장소로 검색", text: $draft)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .onSubmit { commit(draft) }
                if !draft.isEmpty {
                    Button {
                        draft = ""
                        commit("")
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .background(Capsule().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))

            Button {} label: {
                Image(systemName: "bag")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))
            }
            .foregroundStyle(.primary)
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
    }

    // MARK: 자동완성 (§3-3)

    private var suggestionList: some View {
        VStack(spacing: 0) {
            if draft.isEmpty {
                sectionLabel("추천 검색어")
                ForEach(["도깨비", "김수현", "북촌한옥마을", "마포구"], id: \.self) { term in
                    Button { commit(term) } label: {
                        row(icon: "magnifyingglass", text: term, detail: "")
                    }
                    .buttonStyle(.plain)
                }
            } else {
                ForEach(suggestions) { item in
                    Button { commit(item.text) } label: {
                        row(icon: item.symbol, text: item.text, detail: item.detail)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .background(Color(.systemBackground))
        .clipShape(.rect(cornerRadius: 14))
        .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
        .padding(.horizontal, 14)
        .frame(maxHeight: 380)
    }

    private func sectionLabel(_ title: String) -> some View {
        HStack {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 4)
    }

    private func row(icon: String, text: String, detail: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).frame(width: 18).foregroundStyle(.secondary)
            Text(text).foregroundStyle(.primary)
            Spacer()
            if !detail.isEmpty {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .contentShape(.rect)
    }

    // MARK: 시트 내용

    @ViewBuilder private var sheetContent: some View {
        if let place = selectedPlace {
            PlaceDetail(row: place, others: data.sharingPlace(place)) { back() }
        } else if let work = selectedWork {
            WorkDetail(work: work, chip: $chip, rows: placeRows) { row in
                selectedPlace = row
                bumpFit()
            } onBack: { back() }
        } else {
            listContent
        }
    }

    private var listContent: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { each in
                    Text("\(each.rawValue) \(each == .work ? workRows.count : placeRows.count)")
                        .tag(each)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 14)
            .padding(.bottom, 8)

            if tab == .place {
                ChipRow(selected: $chip)
            }

            ScrollView {
                LazyVStack(spacing: 0) {
                    if tab == .work {
                        ForEach(workRows) { work in
                            Button {
                                selectedWork = work
                                tab = .place
                                bumpFit()
                            } label: {
                                WorkRow(work: work, badge: SceneSearch.castBadge(work, query: query))
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 14)
                        }
                    } else {
                        ForEach(placeRows) { row in
                            Button {
                                selectedPlace = row
                                bumpFit()
                            } label: {
                                PlaceRow(row: row)
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }

    // MARK: 동작

    /// 검색 확정 — 추천어 선택이나 엔터. 이때만 핀을 갈고 카메라를 맞춘다 (§3-5).
    /// 글자마다 갱신하지 않는다.
    private func commit(_ term: String) {
        draft = term
        query = term
        chip = CategoryChip.all
        selectedWork = nil
        selectedPlace = nil
        searchFocused = false
        detent = .medium
        bumpFit()
    }

    private func bumpFit() {
        fitToken += 1
    }

    private func back() {
        if selectedPlace != nil {
            selectedPlace = nil
        } else {
            selectedWork = nil
        }
        bumpFit()
    }
}

/// 카테고리 칩 줄. 목록과 지도를 **둘 다** 좁히고 카메라는 건드리지 않는다 (§3-5).
struct ChipRow: View {
    @Binding var selected: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(CategoryChip.names, id: \.self) { name in
                    let isOn = selected == name
                    Text(name)
                        .font(.subheadline)
                        .padding(.horizontal, 13).padding(.vertical, 7)
                        .background(
                            Capsule().fill(isOn ? Color.accentColor : Color(.systemGray6))
                        )
                        .foregroundStyle(isOn ? .white : .primary)
                        .onTapGesture { selected = isOn ? CategoryChip.all : name }
                }
            }
            .padding(.horizontal, 14)
        }
        .padding(.bottom, 8)
    }
}
