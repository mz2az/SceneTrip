import SceneApiClient
import SwiftUI

/// 🔍 를 누르면 칩 줄 자리를 덮는 검색칸과 그 아래 목록 (MZ2AZ-547 M6, 웹 `protoPoi.tsx` `SearchPanel`).
///
/// 가게·장소(편의시설)만 이름으로 찾는다 — 촬영지는 찾지 않는다(2026-11-10 사용자 요청, 웹 72f87416).
/// 먼저 지금 지도 범위에서 찾고, 더 보고 싶으면 「이 지역 밖에서도 찾기」. 검색 중에는 갈래 칩을 숨긴다
/// (검색어와 칩 필터가 겹치면 「왜 안 나오지?」 가 생긴다). 엔터 → 결과를 지도에 펼친다(`MapSearchResultsView`).
/// 목록이 떠 있는 동안 찾은 곳은 지도에도 점으로 찍힌다 — 점은 계획 화면 지도가 `model.pinned` 로 그린다.
struct MapSearchPanel: View {
    /// 목록 상태 — 계획 화면이 들고 있다(지도가 같은 결과를 점으로 그린다).
    @ObservedObject var model: MapSearchModel
    let viewport: MapViewport?
    /// 목록에서 하나를 골랐다 — 지도가 그곳으로 가고 정보 카드가 뜬다.
    let onPick: (RouteGuide.Place) -> Void
    /// 엔터 — 검색어와 「이 지역 밖」 여부.
    let onCommit: (String, Bool) -> Void
    let onClose: () -> Void
    var initialText = ""

    @State private var text = ""
    @State private var wide = false
    @State private var listHeight: CGFloat = 0
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 8) {
            field
            if model.phase != .idle {
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
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 10).padding(.top, 10)
        .onAppear {
            text = initialText
            focused = true
        }
        // 닫히면 목록과 지도의 점을 지운다.
        .onDisappear { model.reset() }
        .onChange(of: text) { _, _ in
            wide = false
            model.update(text: text, wide: false, viewport: viewport)
        }
        .onChange(of: wide) { _, _ in model.update(text: text, wide: wide, viewport: viewport) }
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(MapSearchRules.rows(model.phase).enumerated()), id: \.offset) { _, row in
                rowView(row)
            }
        }
    }

    /// 검색칸 — ‹ 닫기 · 입력 · ✕ 지우기.
    private var field: some View {
        HStack(spacing: 4) {
            Button(action: onClose) {
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
                        onCommit(query, wide)
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
        .frame(height: 46)
        .background(Capsule().fill(Color(.systemBackground)))
        .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
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
        case .searchEverywhere:
            Button { wide = true } label: {
                Text("이 지역 밖에서도 찾기")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
            .overlay(alignment: .top) { Divider() }
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
        case .shopsHere: tr("가게·장소 · 이 지역")
        case .shopsEverywhere: tr("가게·장소 · 전체")
        }
    }

    static func empty(_ empty: MapSearchEmpty) -> String {
        switch empty {
        case .noneHere: tr("이 지역엔 없어요")
        case .noPlaces: tr("찾는 곳이 없어요")
        }
    }
}

/// 가게 한 줄 — 갈래 색 점 · 이름 · 분류·주소.
struct MapShopRow: View {
    let place: RouteGuide.Place

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8).fill(Color(.systemGray6))
                .frame(width: 40, height: 40)
                .overlay {
                    Circle().fill(RoutePoiTone.of(place.poiGroup)).frame(width: 12, height: 12)
                        .overlay(Circle().strokeBorder(.white, lineWidth: 2))
                }
                .padding(.vertical, 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(place.label.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                Text([place.label.category, place.label.address].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .contentShape(.rect)
    }
}
