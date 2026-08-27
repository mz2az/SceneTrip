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
    @StateObject var data = SceneData()
    @StateObject var cart = CartStore()
    @ObservedObject var likes = LikeStore.shared

    @State var draft = ""
    /// **확정된** 검색어. `draft` 는 타이핑하는 동안에도 바뀌므로 "지금 이 목록이
    /// 무엇을 검색한 결과인가" 를 그것으로 판단하면 헤더가 글자마다 깜빡인다.
    @State var committed = ""
    @State var tab: Tab = .work
    @State var chip = CategoryChip.all
    @State var detent: BottomSheet<AnyView>.Detent = .medium
    @State var selectedPlace: PlaceSummary?

    /// 드릴다운 2단 — 고른 작품과 그 작품의 촬영지 (`GET /contents/{id}/places`).
    @State var selectedContent: ContentSummary?
    @State var contentPlaces: [PlaceSummary] = []
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
    @State var showCart = false

    /// 지도 중심을 읽어 오는 창구 — "현 지도 내 성지 검색" 이 누르는 순간에 쓴다.
    @State var camera = MapCamera()

    /// 반경 검색이 켜져 있나. 켜져 있으면 버튼이 해제 버튼으로 바뀐다(목업).
    @State var nearby = false

    /// 값이 오르면 지도가 남한 전체로 돌아간다 — 첫 화면의 작품 탭이 그 상태다.
    @State var koreaToken = 0

    /// 값이 오르면 내 위치로 이동한다.
    @State var locateToken = 0

    /// 「내 위치」 가 실패했을 때만 값이 찬다. 성공은 지도가 움직여서 알 수 있다.
    @State private var locateFailure: LocateOutcome?

    /// 지도가 남한 밖으로 나갔나 — "한국으로" 버튼을 그때만 띄운다.
    @State var outsideKorea = false

    /// 시트가 지금 덮고 있는 실제 높이(pt). 시트가 직접 알려 준다.
    @State private var sheetHeight: CGFloat = 0
    @FocusState var searchFocused: Bool

    enum Tab: String, CaseIterable { case work = "작품", place = "장소" }

    /// 화면에 실제로 쓰는 촬영지 — 칩까지 적용한 것.
    ///
    /// **목록과 지도가 이것을 같이 쓴다** (§3-5 확정). 프로토타입은 칩이 목록만
    /// 좁히고 지도 핀은 검색 결과 전체를 유지했으나, 실제 숫자를 보고 뒤집었다 —
    /// 목록에 없는 핀이 남으면 그 핀을 눌렀을 때 목록에 없는 장소가 열린다.
    private var visiblePlaces: [PlaceSummary] {
        let filtered = chip == CategoryChip.all
            ? data.places
            : data.places.filter { CategoryChip.of($0.type) == chip }
        // 첫 화면은 **인기 상위 10곳만** 본다. 155곳을 한꺼번에 보여 주면 목록도
        // 지도도 읽을 수 없다 — 무엇부터 봐야 할지가 사라진다.
        return isInitial ? Array(filtered.prefix(10)) : filtered
    }

    /// 아무것도 좁히지 않은 첫 화면인가 — 검색어도, 반경도, 고른 작품도 없는 상태.
    private var isInitial: Bool {
        committed.isEmpty && !nearby && selectedContent == nil
    }

    /// 핀에 번호를 찍을 것인가.
    ///
    /// 번호는 "목록의 N번 = 지도의 N번" 을 잇는 장치다. 첫 화면의 **작품 탭**에서는
    /// 목록이 작품이고 핀은 장소라 이어 볼 짝이 없다 — 그때만 민 핀으로 둔다.
    /// 장소 탭으로 옮기면 목록과 핀이 같은 10곳이 되므로 번호를 붙인다(인기 1위부터).
    private var numbersOnPins: Bool {
        !(isInitial && tab == .work)
    }

    /// 지도에 꽂는 핀. 작품을 골랐으면 그 작품의 촬영지, 아니면 검색 결과다 —
    /// 어느 쪽이든 **시트의 목록과 같은 배열**이라 행 번호가 곧 핀 번호다.
    private var mapPlaces: [PlaceSummary] {
        if selectedContent != nil {
            return contentPlaces
        }
        return visiblePlaces
    }

    var body: some View {
        ZStack(alignment: .top) {
            // 카메라는 fitToken(검색 확정·드릴다운)·focusToken(장소 선택)이 오를
            // 때만 움직인다. 칩은 어느 쪽도 올리지 않는다 (§3-5).
            NaverMapView(
                pins: mapPlaces,
                camera: camera,
                numbered: numbersOnPins,
                koreaToken: koreaToken,
                locateToken: locateToken,
                fitToken: fitToken,
                focusToken: focusToken,
                focus: selectedPlace,
                panToken: panToken,
                pan: panTarget,
                sheetHeight: sheetHeight,
                onLocateFailure: { locateFailure = $0 }
            ) { place in
                selectedPlace = place
                if selectedContent == nil {
                    tab = .place
                }
                detent = .medium
                focusToken += 1
            }
            .ignoresSafeArea()

            BottomSheet(
                detent: $detent,
                topInset: 108,
                onHeightChange: { sheetHeight = $0 }
            ) {
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
                if !searchFocused {
                    // 「현 지도 내 성지 검색」은 가운데, 조작 버튼은 오른쪽 —
                    // 위 검색창의 장바구니와 같은 세로선에 놓아 지도를 덜 가린다.
                    ZStack {
                        nearbyButton
                        HStack(spacing: 8) {
                            Spacer()
                            if outsideKorea {
                                mapControl("한국으로", symbol: "map") { koreaToken += 1 }
                            }
                            // 과녁 십자(dot.scope) — 네이버·카카오 지도의 현위치
                            // 버튼과 같은 모양이다. location.circle 은 원 안에
                            // 화살표라 "방향" 으로 읽힌다.
                            mapControl("내 위치", symbol: "dot.scope") {
                                locateToken += 1
                            }
                        }
                        .padding(.trailing, 20)
                    }
                    .padding(.top, 8)
                }
                if searchFocused {
                    SuggestionPanel(
                        draft: draft,
                        suggestions: suggestions,
                        topWork: topWork,
                        onCommit: { term, kind in commit(term, kind: kind) },
                        onOpenWork: { open(summary(of: $0)) },
                        onSelectPlace: { select(placeSuggestion: $0) }
                    )
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .animation(.easeInOut(duration: 0.15), value: searchFocused)
        // 「내 위치」 가 실패한 경우에만 뜬다. 문구와 「설정 열기」 는
        // LocateAlert.swift 에 있다 — 경로 탭에서도 같은 버튼을 쓰게 된다.
        .locateFailureAlert($locateFailure)
        .ignoresSafeArea(.keyboard)
        .task {
            data.search("")
            await cart.refresh()
        }
        // 카메라 fit 은 결과가 실제로 도착한 순간에 건다. 첫 진입 로드는 pendingFit
        // 이 false 라 서울 중심을 유지한다 (MZ2AZ-162, §3-1).
        // 첫 화면에서 장소 탭으로 옮기면 인기 10곳이 **한 화면에 다 들어오게** 맞춘다.
        // 서울 중심 줌 11 에서는 강릉·포항이 화면 밖이라 목록의 절반이 어디 있는지
        // 보이지 않는다. 검색 결과일 때는 이미 확정 시점에 맞춰 뒀으므로 건드리지 않는다.
        // 지도가 남한 밖으로 나갔는지 지켜본다.
        //
        // 카메라가 움직일 때마다 상태를 갱신하면 화면이 매 프레임 다시 그려진다.
        // 이 버튼은 즉각적일 필요가 없어 1 초에 한 번만 확인한다.
        .task {
            while !Task.isCancelled {
                let outside = camera.isOutsideKorea
                if outside != outsideKorea {
                    outsideKorea = outside
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
        .onChange(of: tab) { _, current in
            guard isInitial else { return }
            if current == .place, !visiblePlaces.isEmpty {
                fitToken += 1
            } else if current == .work {
                // 작품 탭은 특정 촬영지를 가리키지 않는다 — 남한 전체로 돌아간다.
                koreaToken += 1
            }
        }
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
            // 검색을 하고 들어온 목록이면 나가는 길을 준다.
            //
            // 화면은 초기목록 → 검색결과 → 작품상세 → 장소상세 로 쌓인다. 상세 둘은
            // `<` 로 한 단계씩 나오는데 **검색결과에만 그것이 없어서**, 작품 상세에서
            // `<` 를 눌러 여기까지 온 사용자가 더 나갈 자리를 못 찾았다. 검색바의
            // ⊗ 로 지울 수는 있지만 방금 누른 것과 다른 자리라 이어지지 않는다.
            //
            // 그래서 **상세와 같은 헤더를 같은 자리에** 쓴다. 브라우저 뒤로가기처럼
            // 한 번에 한 단계씩만 나온다 — 여기서 한 단계는 "검색 전" 이다.
            if !committed.isEmpty {
                DetailHeader(title: committed, subtitle: "") {
                    draft = ""
                    commit("")
                }
            }

            // 첫 화면의 숫자는 **전체가 아니라 인기순으로 추린 것** 이다. 그냥
            // "장소 10" 이라고만 두면 전국에 10곳뿐인 것으로 읽힌다.
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { each in
                    Text("\(isInitial ? "인기 " : "")\(each.rawValue) \(count(each))").tag(each)
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
                            Button { open(content) } label: {
                                WorkRow(
                                    content: content,
                                    onLike: { likes.toggle(content.id) },
                                    liked: likes.contains(content.id)
                                )
                            }
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
    /// 검색을 확정한다.
    ///
    /// `kind` 는 **사용자가 고른 것의 갈래** 다. 장소를 골랐으면 장소 탭이, 작품이나
    /// 배우를 골랐으면 작품 탭이 열린다 — 배우로 찾는 것은 "그 배우가 나온 작품" 이지
    /// 장소가 아니다. 직접 입력하고 엔터를 친 경우처럼 갈래를 모르면 탭을 그대로 둔다.
    func commit(_ term: String, kind: EntityType? = nil) {
        switch kind {
        case .place:
            tab = .place
        case .content, .person:
            tab = .work
        case nil:
            break
        }

        draft = term
        committed = term.trimmingCharacters(in: .whitespaces)
        // 단어로 검색하는 순간 반경 모드는 끝난다 — 두 조건이 함께 걸려 있으면
        // 결과가 왜 그렇게 나왔는지 화면만 보고는 설명할 수 없다.
        nearby = false
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
        // 이미 담긴 장소면 **뺀다.** 목록 행의 버튼은 담기 전용이 아니라 토글이다.
        if cart.contains(place.id) {
            Task { await cart.remove(placeId: place.id) }
            return
        }
        Task { await cart.add(placeId: place.id) }
        // 지도를 옮기는 것은 담을 때만이다. 뺄 때 옮기면 사라진 것을 보여 주는 꼴이다.
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

    func suggest(_ term: String) async {
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
