import SceneApiClient
import SwiftUI

/// 작품검색 탭 (계획서 §3).
///
/// 지도는 항상 보이고 그 위에 바텀시트가 얹혀 드릴다운한다. 목록은 작품 / 장소 두
/// 탭이며 **검색어 하나로 둘이 동시에 채워진다** — 두 탭이 같은 `q` 로 서로 다른
/// 엔드포인트를 부른다 (§3-2 · §4).
///
/// 드릴다운은 검색 상태와 별개다: 작품을 골라도(`selectedContent`) 검색어와 검색
/// 결과는 그대로 남아, 뒤로 가면 고르기 전 화면으로 돌아간다.
struct SearchTabView: View {
    @StateObject private var data = SceneData()
    @StateObject private var cart = CartStore()

    @State private var draft = ""
    @State private var tab: Tab = .work
    @State private var chip = CategoryChip.all
    @State private var detent: BottomSheet<AnyView>.Detent = .medium
    @State private var selectedPlace: PlaceSummary?

    /// 드릴다운 2단 — 고른 작품과 그 작품의 촬영지 (`GET /contents/{id}/places`).
    @State private var selectedContent: ContentSummary?
    @State private var contentPlaces: [PlaceSummary] = []
    @State private var contentLoading = false

    @State private var suggestions: [Suggestion] = []

    /// 자동완성 맨 위에 포스터와 함께 띄우는 가장 연관된 작품 (§3-3). 랭킹은 서버가
    /// 정하므로 suggest 응답의 첫 작품을 `GET /contents/{id}` 로 채운 것이다.
    @State private var topWork: ContentDetail?
    @State private var fitToken = 0
    @State private var focusToken = 0

    /// 검색을 확정했고 결과 도착을 기다리는 중 — 도착하면 그때 카메라를 맞춘다.
    ///
    /// 확정 즉시 `fitToken` 을 올리면 안 된다(실측): 서버 응답이 오기 전에 지도가
    /// **직전 검색의 핀 범위**로 fit 되고, 새 핀이 도착해도 카메라는 그대로다.
    @State private var pendingFit = false

    /// 자동완성에서 **장소**를 골랐다 — 결과가 오면 이 장소를 선택하고 확대한다.
    @State private var pendingFocusPlaceId: Int64?

    /// 담기(+)를 누른 장소 — 줌은 그대로 두고 그 핀이 가운데 오게 지도만 이동한다.
    @State private var panToken = 0
    @State private var panTarget: PlaceSummary?
    @State private var showCart = false
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

    /// 지도에 꽂는 핀. 작품을 골랐으면 그 작품의 촬영지, 아니면 검색 결과다 —
    /// 어느 쪽이든 **시트의 목록과 같은 배열**이라 행 번호가 곧 핀 번호다.
    private var mapPlaces: [PlaceSummary] {
        selectedContent == nil ? visiblePlaces : contentPlaces
    }

    /// 시트가 지도를 덮는 비율 — 카메라가 남는 영역 기준으로 움직이게 넘긴다.
    /// 최대 단계에서는 지도가 어차피 안 보이므로 중간 단 기준을 유지한다.
    private var mapInsetFraction: CGFloat {
        min(detent.rawValue, BottomSheet<AnyView>.Detent.medium.rawValue)
    }

    var body: some View {
        ZStack(alignment: .top) {
            // 카메라는 fitToken(검색 확정·드릴다운)·focusToken(장소 선택)이 오를
            // 때만 움직인다. 칩은 어느 쪽도 올리지 않는다 (§3-5).
            NaverMapView(
                pins: mapPlaces,
                fitToken: fitToken,
                focusToken: focusToken,
                focus: selectedPlace,
                panToken: panToken,
                pan: panTarget,
                bottomInsetFraction: mapInsetFraction
            ) { place in
                selectedPlace = place
                if selectedContent == nil {
                    tab = .place
                }
                detent = .medium
                focusToken += 1
            }
            .ignoresSafeArea()

            BottomSheet(detent: $detent, topInset: 108) {
                AnyView(sheetContent)
            }

            // 검색 중에는 지도·시트를 스크림으로 덮는다 — 패널 밖을 누르면 닫힌다.
            if searchFocused {
                Color.black.opacity(0.25)
                    .ignoresSafeArea()
                    .onTapGesture { searchFocused = false }
                    .transition(.opacity)
            }

            // 자동완성은 검색창 **바로 아래에 붙어** 내려오는 드롭다운이다.
            VStack(spacing: 2) {
                searchBar
                if searchFocused {
                    SuggestionPanel(
                        draft: draft,
                        suggestions: suggestions,
                        topWork: topWork,
                        onCommit: { commit($0) },
                        onOpenWork: { open(summary(of: $0)) },
                        onSelectPlace: { select(placeSuggestion: $0) }
                    )
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .animation(.easeInOut(duration: 0.15), value: searchFocused)
        // 키보드가 떠도 레이아웃을 밀어 올리지 않는다 — 검색창·패널·시트는 제자리에
        // 있어야 한다. 패널 총높이는 SuggestionPanel 이 키보드에 닿지 않게 붙든다.
        .ignoresSafeArea(.keyboard)
        .task {
            data.search("")
            await cart.refresh()
        }
        // 카메라 fit 은 결과가 실제로 도착한 순간에 건다. 첫 진입 로드는 pendingFit
        // 이 false 라 서울 중심을 유지한다 (MZ2AZ-162, §3-1).
        .onChange(of: data.phase) { _, phase in
            guard phase == .loaded, pendingFit else { return }
            pendingFit = false
            // 자동완성에서 장소를 골라 들어왔으면 결과 전체가 아니라 **그 장소**로
            // 바로 확대하고 상세를 연다.
            if let id = pendingFocusPlaceId {
                pendingFocusPlaceId = nil
                if let place = data.places.first(where: { $0.id == id }) {
                    selectedPlace = place
                    focusToken += 1
                    return
                }
            }
            fitToken += 1
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
        .sheet(isPresented: $showCart) {
            CartSheet().environmentObject(cart)
        }
    }

    // MARK: 검색바

    /// 검색어와 장바구니가 **한 캡슐**이다 — 프로토타입의 검색바와 같다. 따로 떠
    /// 있던 원형 버튼은 폐기했다.
    private var searchBar: some View {
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

            Divider().frame(height: 22)

            Button { showCart = true } label: {
                Image(systemName: "cart")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.primary)
                    .overlay(alignment: .topTrailing) {
                        if !cart.items.isEmpty {
                            Text("\(cart.items.count)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5).padding(.vertical, 2)
                                .background(Capsule().fill(Color.red))
                                .offset(x: 10, y: -8)
                        }
                    }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(Capsule().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))
        .padding(.horizontal, 14)
        .padding(.top, 8)
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
            } else if let content = selectedContent {
                ContentDetailView(
                    summary: content,
                    places: contentPlaces,
                    loading: contentLoading,
                    onBack: {
                        selectedContent = nil
                        contentPlaces = []
                        fitToken += 1
                    },
                    onSelectPlace: { place in
                        selectedPlace = place
                        focusToken += 1
                    },
                    onSave: { save($0) }
                )
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
                        // 번호는 지도 핀과 같은 배열의 같은 순서다 — "3번 행 = 3번 핀".
                        ForEach(Array(visiblePlaces.enumerated()), id: \.element.id) { index, place in
                            Button {
                                selectedPlace = place
                                focusToken += 1
                            } label: {
                                PlaceRow(
                                    place: place,
                                    number: index + 1,
                                    onAdd: { save(place) },
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

    /// 검색 확정 — 추천어 선택이나 엔터. 이때만 서버를 부른다 (§3-5). 글자마다
    /// 부르지 않는다. 카메라는 여기서 움직이지 않고 결과 도착 때 맞춘다 (pendingFit).
    private func commit(_ term: String) {
        draft = term
        chip = CategoryChip.all
        selectedPlace = nil
        selectedContent = nil
        contentPlaces = []
        searchFocused = false
        detent = .medium
        data.search(term)
        pendingFit = true
    }

    /// 작품을 고르면 작품 상세로 들어간다. 검색어·검색 결과·탭은 **건드리지 않는다**
    /// — 뒤로 가면 고르기 전 화면 그대로다.
    private func open(_ content: ContentSummary) {
        selectedContent = content
        contentPlaces = []
        contentLoading = true
        selectedPlace = nil
        searchFocused = false
        detent = .medium
        Task {
            let places = await (try? data.places(ofContent: content.id)) ?? []
            // 로드 중에 뒤로 갔거나 다른 작품을 골랐으면 버린다.
            guard selectedContent?.id == content.id else { return }
            contentPlaces = places
            contentLoading = false
            fitToken += 1
        }
    }

    /// 담기 — 계약이 적어 둔 세 경로 중 목록 행의 `+`. 담는 순간 그 핀이 가운데
    /// 오게 지도를 **이동만** 한다. 확대는 장소를 열 때만 한다.
    private func save(_ place: PlaceSummary) {
        Task { await cart.add(placeId: place.id) }
        panTarget = place
        panToken += 1
    }

    /// 자동완성에서 장소를 골랐다. 검색을 확정하되 **장소 탭으로 넘어가고**, 결과가
    /// 도착하면 그 장소를 바로 선택·확대한다 — 작품 탭에 남아 있으면 고른 것과
    /// 무관한 작품이 보인다.
    private func select(placeSuggestion item: Suggestion) {
        commit(item.name)
        tab = .place
        pendingFocusPlaceId = item.id
    }

    /// 자동완성 카드의 상세를 목록용 요약으로 접는다 — `open(_:)` 은 목록 행과 같은
    /// 타입을 받는다.
    private func summary(of work: ContentDetail) -> ContentSummary {
        ContentSummary(
            id: work.id,
            category: work.category,
            title: work.title,
            posterUrl: work.posterUrl,
            broadcaster: work.broadcaster,
            releaseYear: work.releaseYear,
            genres: work.genres,
            placeCount: work.placeCount
        )
    }

    private func suggest(_ term: String) async {
        let query = term.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            suggestions = []
            topWork = nil
            return
        }
        let items = await (try? SearchAPI.suggest(q: query, limit: 8).items) ?? []
        // 응답이 오는 사이 검색어가 바뀌었으면 버린다 — 늦게 온 응답이 최신을 덮는다.
        guard draft.trimmingCharacters(in: .whitespaces) == query else { return }
        suggestions = items

        // 첫 작품 후보만 포스터·메타까지 채운다. 없으면 카드도 없다.
        guard let first = items.first(where: { $0.type == .content }) else {
            topWork = nil
            return
        }
        if topWork?.id != first.id {
            let detail = try? await ContentsAPI.getContent(contentId: first.id)
            guard draft.trimmingCharacters(in: .whitespaces) == query else { return }
            topWork = detail
        }
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
