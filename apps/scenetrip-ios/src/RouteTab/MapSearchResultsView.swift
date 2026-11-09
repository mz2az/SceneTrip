import SceneApiClient
import SwiftUI

/// 엔터 뒤 — 검색 결과를 지도에 펼친다 (MZ2AZ-547 M7, 웹 `protoPoi.tsx` `ResultsView`).
///
/// 위에 검색어 알약(누르면 고쳐 쓰기) + ✕, 지도를 옮긴 뒤에만 「이 지역에서 다시 찾기」, 아래 서랍(42% ↔ 78%)에
/// 탭 「촬영지 N」/「가게 N」. 촬영지 줄의 ＋ 는 「몇 일차에 넣을까요?」(P4 `CourseDayPickerSheet`). 지도 핀은
/// `RouteMapView` 가 그린다(`MapSearchPinRenderer`). 상태 규칙은 `MapSearchResults`.
struct MapSearchResultsView: View {
    @ObservedObject var model: MapSearchResultsModel
    /// 서랍이 서는 영역의 높이(지도 전체).
    let height: CGFloat
    let viewport: MapViewport?
    let dayCounts: [Int]
    let currentDay: Int
    let taken: Set<Int64>
    let onAddToDay: (PlaceSummary, Int) -> Void
    /// 촬영지 줄을 눌렀다 — 그곳으로 지도를 옮긴다.
    let onFocus: (PlaceSummary) -> Void
    /// 가게를 눌렀다 — 그곳으로 가서 정보 카드(코스에 담기).
    let onPickShop: (RouteGuide.Place) -> Void
    let onEdit: (String) -> Void
    let onExit: () -> Void

    @State private var asking: PlaceSummary?

    var body: some View {
        if let state = model.state {
            ZStack(alignment: .top) {
                VStack(spacing: 8) {
                    queryPill(state)
                    if state.showsRefresh {
                        MapRefreshPill { model.searchHere(viewport: viewport) }
                    }
                }
                .padding(.horizontal, 10).padding(.top, 10)
                drawer(state)
                    .frame(height: height * state.drawerShare)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .sheet(isPresented: Binding(get: { asking != nil }, set: {
                if !$0 {
                    asking = nil
                }
            })) {
                if let place = asking {
                    CourseDayPickerSheet(
                        title: String(format: tr("「%@」 을 몇 일차에 넣을까요?"), place.name),
                        dayCounts: dayCounts, current: currentDay
                    ) { day in
                        onAddToDay(place, day)
                    }
                }
            }
        }
    }

    /// 검색어 알약 — 누르면 고쳐 쓰기, ✕ 는 끝내기.
    private func queryPill(_ state: MapSearchResults) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "magnifyingglass").font(.system(size: 15)).foregroundStyle(.secondary)
            Button { onEdit(state.query) } label: {
                HStack(spacing: 6) {
                    Text("“\(state.query)”").font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    if let count = state.count(of: .spots) {
                        Text(String(format: tr("%lld곳"), count)).font(.system(size: 14)).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44).contentShape(.rect)
            }
            .buttonStyle(.plain)
            Button(action: onExit) {
                Image(systemName: "xmark.circle.fill").font(.system(size: 20)).foregroundStyle(Color(.tertiaryLabel))
                    .frame(width: 40, height: 40).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("검색 끝내기"))
        }
        .padding(.leading, 14).padding(.trailing, 6)
        .frame(height: 46)
        .background(Capsule().fill(Color(.systemBackground)))
        .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
    }

    private func drawer(_ state: MapSearchResults) -> some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.spring(duration: 0.28)) { model.state?.toggleDrawer() }
            } label: {
                Capsule().fill(Color(.systemGray3)).frame(width: 40, height: 5)
                    .frame(maxWidth: .infinity).padding(.top, 8).padding(.bottom, 4).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(state.tall ? Text("목록 줄이기") : Text("목록 키우기"))
            tabs(state)
            ScrollViewReader { proxy in
                ScrollView {
                    content(state).padding(.horizontal, 16).padding(.bottom, 20)
                }
                .onChange(of: state.selected) { _, id in
                    if let id {
                        withAnimation { proxy.scrollTo(id) }
                    }
                }
            }
        }
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16)
                .fill(Color(.systemGroupedBackground))
                .shadow(color: .black.opacity(0.15), radius: 8, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func tabs(_ state: MapSearchResults) -> some View {
        HStack(spacing: 6) {
            let spots = state.count(of: .spots).map { String(format: tr("촬영지 %lld"), $0) } ?? tr("촬영지")
            tab(.spots, label: spots, state: state)
            tab(.shops, label: String(format: tr("가게 %lld"), state.count(of: .shops) ?? 0), state: state)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 10)
    }

    private func tab(_ tab: MapSearchResults.Tab, label: String, state: MapSearchResults) -> some View {
        let isOn = state.tab == tab
        return Button { model.state?.tab = tab } label: {
            Text(label)
                .font(.system(size: 13, weight: isOn ? .bold : .semibold))
                .foregroundStyle(isOn ? Color(.systemBackground) : .secondary)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(isOn ? Color(.label) : Color(.systemGray6)))
                .frame(minHeight: 36)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected, .isButton] : .isButton)
    }

    @ViewBuilder
    private func content(_ state: MapSearchResults) -> some View {
        switch state.body {
        case .loading:
            ProgressView().padding(24)
        case .failed:
            Text("찾지 못했어요. 잠시 후 다시 해 주세요")
                .font(.system(size: 14)).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(24)
        case let .empty(empty, offerEverywhere):
            VStack(spacing: 10) {
                Text(MapSearchText.empty(empty)).font(.system(size: 14)).foregroundStyle(.secondary)
                if offerEverywhere {
                    Button { model.searchEverywhere() } label: {
                        Text("이 지역 밖에서도 찾기").font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(TabBar.homePurple)
                            .padding(.horizontal, 16).frame(minHeight: 40)
                            .background(Capsule().fill(Color(.systemBackground)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 24).padding(.horizontal, 12)
        case let .spots(spots):
            card {
                ForEach(Array(spots.enumerated()), id: \.element.id) { index, place in
                    spotRow(place, first: index == 0, selected: state.selected == place.id).id(place.id)
                }
            }
        case let .shops(shops):
            card {
                ForEach(Array(shops.enumerated()), id: \.element.id) { index, place in
                    Button { onPickShop(place) } label: { MapShopRow(place: place) }
                        .buttonStyle(.plain)
                        .overlay(alignment: .top) {
                            if index > 0 {
                                Divider().padding(.leading, 66)
                            }
                        }
                }
            }
        }
    }

    private func card(@ViewBuilder _ rows: () -> some View) -> some View {
        LazyVStack(spacing: 0, content: rows)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func spotRow(_ place: PlaceSummary, first: Bool, selected: Bool) -> some View {
        let inCourse = taken.contains(place.id)
        return HStack(spacing: 4) {
            Button {
                model.state?.select(place.id)
                onFocus(place)
            } label: {
                MapSpotRow(place: place, thumb: 52)
            }
            .buttonStyle(.plain)
            Button { asking = place } label: {
                Group {
                    if inCourse {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 24))
                            .foregroundStyle(Color(.systemGray3))
                    } else {
                        Image(systemName: "plus").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 30, height: 30).background(Circle().fill(TabBar.homePurple))
                    }
                }
                .frame(width: 44, height: 44).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(inCourse)
            .accessibilityLabel(inCourse
                ? Text("이미 코스에 있어요")
                : Text(String(format: tr("%@ 코스에 추가"), place.name)))
            .padding(.trailing, 8)
        }
        .background(selected ? Color.blue.opacity(0.08) : Color.clear)
        .overlay(alignment: .top) {
            if !first {
                Divider().padding(.leading, 78)
            }
        }
    }
}
