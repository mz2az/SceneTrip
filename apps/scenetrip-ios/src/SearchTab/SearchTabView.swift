import SceneApiClient
import SwiftUI

/// 작품검색 탭 (계획서 §3).
///
/// 지도는 항상 보이고 그 위에 바텀시트가 얹혀 드릴다운한다. 목록은 작품 / 장소 두
/// 탭이며 **검색어 하나로 둘이 동시에 채워진다** — 두 탭이 같은 `q` 로 서로 다른
/// 엔드포인트를 부른다 (§3-2 · §4).
struct SearchTabView: View {
    @StateObject private var data = SceneData()
    @StateObject private var cart = CartStore()

    @State private var draft = ""
    @State private var tab: Tab = .work
    @State private var chip = CategoryChip.all
    @State private var detent: BottomSheet<AnyView>.Detent = .medium
    @State private var selectedPlace: PlaceSummary?
    @State private var suggestions: [Suggestion] = []
    @State private var fitToken = 0
    @FocusState private var searchFocused: Bool

    enum Tab: String, CaseIterable { case work = "작품", place = "장소" }

    /// 화면에 실제로 쓰는 촬영지 — 칩까지 적용한 것.
    ///
    /// **목록과 지도가 이것을 같이 쓴다** (§3-5 확정). 프로토타입은 칩이 목록만
    /// 좁히고 지도 핀은 검색 결과 전체를 유지했으나, 실제 숫자를 보고 뒤집었다 —
    /// 목록에 없는 핀이 남으면 그 핀을 눌렀을 때 목록에 없는 장소가 열린다.
    private var visiblePlaces: [PlaceSummary] {
        guard chip != CategoryChip.all else { return data.places }
        return data.places.filter { CategoryChip.of($0.type) == chip }
    }

    var body: some View {
        ZStack(alignment: .top) {
            // 카메라는 fitToken 이 오를 때만 움직인다. 칩은 올리지 않는다 (§3-5).
            NaverMapView(pins: visiblePlaces, fitToken: fitToken) { place in
                selectedPlace = place
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
        .task {
            data.search("")
            await cart.refresh()
        }
        .overlay(alignment: .bottom) {
            // 계약이 409 에 "이미 저장된 장소입니다" 를 띄우라고 적어 뒀다. 베타도
            // 스낵바였다 — 담기는 화면을 옮기지 않으므로 알림이 그 자리에 떠야 한다.
            if let toast = cart.toast {
                Text(toast)
                    .font(.subheadline)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .padding(.bottom, 40)
                    .transition(.opacity)
                    .task {
                        try? await Task.sleep(for: .seconds(2))
                        cart.clearToast()
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: cart.toast)
    }

    // MARK: 검색바

    private var searchBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("작품·배우·장소로 검색", text: $draft)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .onSubmit { commit(draft) }
                    .onChange(of: draft) { _, term in Task { await suggest(term) } }
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

            // 담긴 개수를 배지로 보여 준다 — 베타의 CartIconButton 과 같다.
            // 장바구니 화면 자체는 별도 티켓이라 여기서는 개수까지만 보인다.
            Button {} label: {
                Image(systemName: "bag")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))
                    .overlay(alignment: .topTrailing) {
                        if !cart.items.isEmpty {
                            Text("\(cart.items.count)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5).padding(.vertical, 2)
                                .background(Capsule().fill(Color.red))
                                .offset(x: 4, y: -2)
                        }
                    }
            }
            .foregroundStyle(.primary)
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
    }

    // MARK: 자동완성 (§3-3)

    //
    // 랭킹과 갈래(작품·인물·장소)는 **서버가 정한다** — `GET /v1/search/suggestions`
    // 가 type 으로 구분해 돌려주고, 명세에 "앞글자 일치 우선, 동점이면 인기도순"
    // 이라고 적혀 있다. 프론트가 같은 규칙을 두 번 구현하면 iOS·Android 가 갈린다.

    private var suggestionList: some View {
        VStack(spacing: 0) {
            if draft.isEmpty {
                sectionLabel("추천 검색어")
                ForEach(["도깨비", "공유", "북촌한옥마을"], id: \.self) { term in
                    Button { commit(term) } label: {
                        row(icon: "magnifyingglass", text: term, detail: "")
                    }
                    .buttonStyle(.plain)
                }
            } else {
                ForEach(suggestions, id: \.id) { item in
                    Button { commit(item.name) } label: {
                        row(icon: symbol(item.type), text: item.name,
                            detail: item.subtitle ?? "")
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

    private func symbol(_ type: EntityType) -> String {
        switch type {
        case .content: "film"
        case .person: "person"
        case .place: "mappin.and.ellipse"
        }
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
        switch data.phase {
        case let .failed(failure):
            ErrorView(failure: failure) { data.retry() }
        case .loading where data.places.isEmpty && data.contents.isEmpty:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        default:
            if let place = selectedPlace {
                PlaceDetailView(summary: place, load: data.detail(ofPlace:)) {
                    selectedPlace = nil
                    fitToken += 1
                }
                .environmentObject(cart)
            } else {
                listContent
            }
        }
    }

    private var listContent: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { each in
                    Text("\(each.rawValue) \(count(each))").tag(each)
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
                        ForEach(data.contents, id: \.id) { content in
                            Button { open(content) } label: { WorkRow(content: content) }
                                .buttonStyle(.plain)
                            Divider().padding(.leading, 14)
                        }
                    } else {
                        ForEach(visiblePlaces, id: \.id) { place in
                            Button {
                                selectedPlace = place
                                fitToken += 1
                            } label: {
                                PlaceRow(
                                    place: place,
                                    onAdd: { Task { await cart.add(placeId: place.id) } },
                                    saved: cart.contains(place.id)
                                )
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }

    private func count(_ tab: Tab) -> Int {
        tab == .work ? data.contents.count : visiblePlaces.count
    }

    // MARK: 동작

    /// 검색 확정 — 추천어 선택이나 엔터. 이때만 서버를 부르고 카메라를 맞춘다 (§3-5).
    /// 글자마다 부르지 않는다.
    private func commit(_ term: String) {
        draft = term
        chip = CategoryChip.all
        selectedPlace = nil
        searchFocused = false
        detent = .medium
        data.search(term)
        fitToken += 1
    }

    /// 작품을 고르면 그 작품의 촬영지만 남긴다.
    private func open(_ content: ContentSummary) {
        commit(content.title)
        tab = .place
    }

    private func suggest(_ term: String) async {
        let query = term.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            suggestions = []
            return
        }
        suggestions = await (try? SearchAPI.suggest(q: query, limit: 8).items) ?? []
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
