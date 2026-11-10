import SceneApiClient
import SwiftUI

/// 🔍 를 누르면 칩 줄 자리를 덮는 검색칸과 그 아래 목록 (MZ2AZ-547 M6, 웹 `protoPoi.tsx` `SearchPanel`).
///
/// 가게·장소(편의시설)만 이름으로 찾는다 — 촬영지는 찾지 않는다(2026-11-10 사용자 요청, 웹 72f87416).
/// 먼저 지금 지도 범위에서 찾고, 더 보고 싶으면 「이 지역 밖에서도 찾기」. 검색 중에는 갈래 칩을 숨긴다
/// (검색어와 칩 필터가 겹치면 「왜 안 나오지?」 가 생긴다). 엔터 → 결과를 지도에 펼친다(`MapSearchResultsView`).
/// 글자 치는 동안은 목록만 — 지도에는 아무것도 찍지 않는다(§7 구글처럼). 목록 맨 위 「‘q’ 검색」 은 키보드 검색과 같다.
struct MapSearchPanel: View {
    /// 목록 상태 — 계획 화면이 들고 있다.
    @ObservedObject var model: MapSearchModel
    let viewport: MapViewport?
    /// 목록에서 하나를 골랐다 — 지도가 그곳으로 가고 정보 카드가 뜬다.
    let onPick: (RouteGuide.Place) -> Void
    /// 엔터 — 검색어.
    let onCommit: (String) -> Void
    let onClose: () -> Void
    var initialText = ""

    @State private var text = ""
    @State private var listHeight: CGFloat = 0
    /// 검색칸이 🔍 동그라미(46) 자리에서 오른쪽으로 다 펼쳐졌나(M7, 웹 `.proto-search-open`).
    @State private var revealed = false
    /// 닫는 중 — 검색칸이 동그라미로 줄어드는 0.2초 동안 목록은 먼저 숨긴다.
    @State private var closing = false
    @FocusState private var focused: Bool

    /// 🔍 동그라미의 지름 — 검색칸은 이만큼에서 시작해 이만큼으로 돌아간다.
    private static let circle: CGFloat = 46
    private static let growDuration = 0.28
    private static let shrinkDuration = 0.2

    var body: some View {
        VStack(spacing: 8) {
            field
            if model.phase != .idle, !closing {
                // 짧으면 내용 높이만, 길면 420 에서 넘긴다. `ViewThatFits` 는 짧은 목록에도 420 칸을 다 차지해 위아래가 비었다
                // (가게만 찾게 된 뒤 한두 줄짜리 목록에서 드러났다) — 잰 내용 높이로 칸을 정한다.
                ScrollView {
                    rows.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { listHeight = $0 }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: min(listHeight, 420))
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
                // 0.08초 뒤 6pt 내려오며 0.22초에 나타난다(M8, 웹 `.proto-search-results`).
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .offset(y: -6))
                        .animation(.easeOut(duration: 0.22).delay(0.08)),
                    removal: .opacity.animation(.easeIn(duration: 0.12))
                ))
            }
        }
        .padding(.horizontal, 10).padding(.top, 10)
        .onAppear {
            text = initialText
            focused = true
            withAnimation(.timingCurve(0.32, 0.72, 0, 1, duration: Self.growDuration)) { revealed = true }
        }
        // 닫히면 목록과 지도의 점을 지운다.
        .onDisappear { model.reset() }
        .onChange(of: text) { _, _ in model.update(text: text, viewport: viewport) }
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 맨 위 줄 — 이 검색어로 결과 서랍을 연다(키보드 검색과 같다, §7-2).
            if let query = MapSearchRules.query(text) {
                Button { onCommit(query) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").font(.system(size: 15))
                        Text(String(format: tr("‘%@’ 검색"), query))
                            .font(.system(size: 15, weight: .semibold)).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(TabBar.homePurple)
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
            ForEach(Array(MapSearchRules.rows(model.phase).enumerated()), id: \.offset) { _, row in
                rowView(row)
            }
        }
    }

    /// 검색칸 — ‹ 닫기 · 입력 · ✕ 지우기.
    private var field: some View {
        HStack(spacing: 4) {
            Button(action: close) {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color(.label))
                    .frame(width: 40, height: 40).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("검색 닫기"))
            TextField(MapSearchText.placeholder, text: $text)
                .font(.system(size: 16))
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit {
                    if let query = MapSearchRules.query(text) {
                        onCommit(query)
                    }
                }
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 18))
                        .foregroundStyle(Color(.tertiaryLabel))
                        .frame(width: 40, height: 40).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("검색어 지우기"))
            }
        }
        .padding(.horizontal, 6)
        .frame(height: Self.circle)
        .background(Capsule().fill(Color(.systemBackground)))
        // 폭을 바꾸지 않고 보이는 범위만 넓힌다 — 글자 칸이 다시 배치되며 흔들리지 않게(웹 clip-path 와 같다).
        .mask(alignment: .leading) {
            GeometryReader { geo in
                Capsule().frame(width: revealed ? geo.size.width : Self.circle)
            }
        }
        .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
    }

    /// ‹ — 검색칸이 동그라미로 줄어든 뒤 닫는다(0.2초, 웹 `.proto-search-close`).
    private func close() {
        guard !closing else { return }
        focused = false
        withAnimation(.timingCurve(0.4, 0, 1, 1, duration: Self.shrinkDuration)) {
            closing = true
            revealed = false
        }
        Task {
            try? await Task.sleep(for: .seconds(Self.shrinkDuration))
            onClose()
        }
    }

    @ViewBuilder
    private func rowView(_ row: MapSearchRow) -> some View {
        switch row {
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(24)
        case .failed:
            Text("찾지 못했어요. 잠시 후 다시 해 주세요")
                .font(.system(size: 14)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(20)
        case let .title(title):
            Text(MapSearchText.title(title))
                .font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 6)
                .background(Color(.systemGroupedBackground))
        case let .shop(place):
            Button { onPick(place) } label: { MapShopRow(place: place) }
                .buttonStyle(.plain)
        case let .empty(empty):
            Text(MapSearchText.empty(empty))
                .font(.system(size: 13)).foregroundStyle(.secondary)
                .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 12)
        }
    }
}

/// 검색 목록·결과 서랍이 함께 쓰는 문구.
enum MapSearchText {
    /// 검색칸 — 「가게·장소 이름」.
    static var placeholder: String {
        tr("가게·장소 이름")
    }

    static func title(_ title: MapSearchTitle) -> String {
        switch title {
        case .shopsNearby: tr("가게·장소 · 가까운 순")
        }
    }

    static func empty(_ empty: MapSearchEmpty) -> String {
        switch empty {
        case .noPlaces: tr("찾는 곳이 없어요")
        }
    }
}

/// 가게 한 줄 — 이름 · 분류·주소. 왼쪽의 회색 칸·갈래 색 점은 뺐다(MZ2AZ-547 §2) — 글은 줄 왼쪽 여백(16)에서 시작하고
/// 구분선도 같은 들여쓰기(`leadingInset`).
struct MapShopRow: View {
    let place: RouteGuide.Place

    /// 글이 시작하는 왼쪽 여백 — 구분선도 여기서 시작한다.
    static let leadingInset: CGFloat = 16

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(place.label.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
            Text([place.label.category, place.label.address].compactMap { $0 }.joined(separator: " · "))
                .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .padding(.vertical, 6)
        .padding(.horizontal, Self.leadingInset)
        .contentShape(.rect)
    }
}
