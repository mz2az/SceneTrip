import SceneApiClient
import SwiftUI

/// 🔍 를 누르면 칩 줄 자리를 덮는 검색칸과 그 아래 목록 (MZ2AZ-547 M6, 웹 `protoPoi.tsx` `SearchPanel`).
///
/// 촬영지와 가게를 한 칸에서 찾고 결과만 둘로 나눈다 — 사람은 「경복궁」 이 촬영지인지 편의시설인지 따지지 않는다.
/// 먼저 지금 지도 범위에서 찾고, 더 보고 싶으면 「이 지역 밖에서도 찾기」. 검색 중에는 갈래 칩을 숨긴다
/// (검색어와 칩 필터가 겹치면 「왜 안 나오지?」 가 생긴다). 엔터 → 결과를 지도에 펼친다(`MapSearchResultsView`).
struct MapSearchPanel: View {
    let viewport: MapViewport?
    /// 목록에서 하나를 골랐다 — 지도가 그곳으로 가고 정보 카드가 뜬다.
    let onPick: (RouteGuide.Place) -> Void
    /// 엔터 — 검색어와 「이 지역 밖」 여부.
    let onCommit: (String, Bool) -> Void
    let onClose: () -> Void
    var initialText = ""

    @StateObject private var model = MapSearchModel()
    @State private var text = ""
    @State private var wide = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 8) {
            field
            if model.phase != .idle {
                // 짧으면 내용 높이만, 길면 420 에서 넘긴다.
                ViewThatFits(in: .vertical) {
                    rows
                    ScrollView { rows }
                }
                .frame(maxHeight: 420)
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
            TextField(tr("촬영지·가게 이름"), text: $text)
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
        case let .spot(place):
            Button { onPick(MapSearchText.guidePlace(place)) } label: {
                MapSpotRow(place: place, thumb: 40)
            }
            .buttonStyle(.plain)
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
    static func title(_ title: MapSearchTitle) -> String {
        switch title {
        case .spotsHere: tr("촬영지 · 이 지역")
        case .spotsEverywhere: tr("촬영지 · 전체")
        case .shopsHere: tr("가게·편의시설 · 이 지역")
        case .shopsEverywhere: tr("가게·편의시설 · 전체")
        }
    }

    static func empty(_ empty: MapSearchEmpty) -> String {
        switch empty {
        case .noneHere: tr("이 지역엔 없어요")
        case .noSpots: tr("찾는 촬영지가 없어요")
        case .noShops: tr("찾는 가게가 없어요")
        }
    }

    /// 촬영지를 고르면 정보 카드(`RoutePlaceCard`)가 뜬다 — 카드는 가이드 장소 모양을 받는다. id 가 `place-` 라 상세는 촬영지 것이다.
    static func guidePlace(_ place: PlaceSummary) -> RouteGuide.Place {
        RouteGuide.Place(
            id: "place-\(place.id)", name: place.name, category: place.type,
            address: place.address, latitude: place.latitude, longitude: place.longitude
        )
    }
}

/// 촬영지 한 줄 — 사진 · 이름 · 작품 · 주소.
struct MapSpotRow: View {
    let place: PlaceSummary
    let thumb: CGFloat

    var body: some View {
        HStack(spacing: 12) {
            RemoteImage(url: place.imageUrl, symbol: "mappin.and.ellipse")
                .frame(width: thumb, height: thumb)
                .clipShape(RoundedRectangle(cornerRadius: thumb > 44 ? 10 : 8))
                .padding(.vertical, 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(place.name).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                if let work = place.contents?.first?.title {
                    Text(work).font(.system(size: 12)).foregroundStyle(TabBar.homePurple).lineLimit(1)
                }
                if let address = place.address {
                    Text(address).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .contentShape(.rect)
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
