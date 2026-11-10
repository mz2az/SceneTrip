import SceneApiClient
import SwiftUI

/// 엔터 뒤 — 검색 결과를 지도에 펼친다 (MZ2AZ-547 M7, 웹 `protoPoi.tsx` `ResultsView`).
///
/// 위에 검색어 알약(누르면 고쳐 쓰기) + ✕, 지도를 옮긴 뒤에만 「이 지역에서 다시 찾기」, 아래 서랍(42% ↔ 78%)에
/// 찾은 가게·장소. 촬영지는 찾지 않는다(2026-11-10 사용자 요청) — 「촬영지 N」 탭·사진 핀·일차 고르기 창을 뺐다.
/// 줄이나 지도 핀을 누르면 그곳으로 가서 정보 카드(「코스에 담기」). 지도 핀(갈래 아이콘)은 `RouteMapView` 가 그린다(`MapSearchPinRenderer`).
/// 목록 끝에서 한 줄 안이 보이면 같은 범위로 다음 20개(§7-4) — 받는 동안 도는 줄, 못 받으면 「다시 시도」 줄.
/// 상태 규칙은 `MapSearchResults`·`MapSearchPagingRules`.
struct MapSearchResultsView: View {
    @ObservedObject var model: MapSearchResultsModel
    /// 서랍이 서는 영역의 높이(지도 전체).
    let height: CGFloat
    let viewport: MapViewport?
    /// 결과를 눌렀다 — 그곳으로 가서 정보 카드(코스에 담기).
    let onPick: (RouteGuide.Place) -> Void
    let onEdit: (String) -> Void
    let onExit: () -> Void

    /// 끄는 동안의 서랍 높이(pt). 손을 떼면 nil — 그때는 붙은 비율(`drawerShare`)의 높이.
    @State private var dragHeight: CGFloat?
    /// 끌기를 시작할 때 붙어 있던 비율.
    @State private var dragStart: Double?
    /// 목록에서 시작한 끌기가 서랍을 내리는 끌기인가(목록이 맨 위이고 아래로) — 끌기마다 처음 한 번 정한다.
    @State private var listPull: Bool?
    @State private var listAtTop = true
    /// 서랍이 0.08초 뒤 6pt 내려오며 나타난다(M8, 웹 `.proto-search-results`).
    @State private var shown = false

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
                    .frame(height: dragHeight ?? height * state.drawerShare)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .opacity(shown ? 1 : 0)
                    .offset(y: shown ? 0 : -6)
                    .onAppear {
                        withAnimation(.easeOut(duration: 0.22).delay(0.08)) { shown = true }
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
                    if let count = state.headerCount {
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

    /// 서랍 — 손잡이(머리)를 위아래로 끌고, 누르면 42% ↔ 78%(§3). 목록이 맨 위일 때 아래로 끌어도 내려간다.
    private func drawer(_ state: MapSearchResults) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(Color(.systemGray3)).frame(width: 40, height: 5)
                .frame(maxWidth: .infinity).padding(.top, 10).padding(.bottom, 10).contentShape(.rect)
                .onTapGesture { settle(at: MapSearchDrawerRules.tappedShare(state.drawerShare)) }
                .gesture(handleDrag(state))
                .accessibilityElement()
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel(state.tall ? Text("목록 줄이기") : Text("목록 키우기"))
                .accessibilityAction { settle(at: MapSearchDrawerRules.tappedShare(state.drawerShare)) }
            ScrollView {
                content(state).padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 20)
                    .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named(Self.listSpace)).minY } action: {
                        listAtTop = $0 >= -1
                    }
            }
            .coordinateSpace(.named(Self.listSpace))
            .simultaneousGesture(listDrag(state))
        }
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16)
                .fill(Color(.systemGroupedBackground))
                .shadow(color: .black.opacity(0.15), radius: 8, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private static let listSpace = "mapSearchDrawerList"

    /// 손잡이 끌기. 서랍이 손가락 밑에서 움직이므로 화면 좌표로 잰다.
    private func handleDrag(_ state: MapSearchResults) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .global)
            .onChanged { follow($0.translation.height, from: state.drawerShare) }
            .onEnded { release(velocity: $0.velocity.height) }
    }

    /// 목록 위의 끌기 — 목록이 맨 위이고 아래로 끌 때만 서랍을 내린다. 아니면 목록이 스크롤한다.
    private func listDrag(_ state: MapSearchResults) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .global)
            .onChanged { value in
                if listPull == nil {
                    listPull = listAtTop && value.translation.height > 0
                }
                guard listPull == true else { return }
                follow(max(value.translation.height, 0), from: state.drawerShare)
            }
            .onEnded { value in
                defer { listPull = nil }
                guard listPull == true else { return }
                release(velocity: value.velocity.height)
            }
    }

    private func follow(_ translation: CGFloat, from share: Double) {
        let start = dragStart ?? share
        dragStart = start
        dragHeight = MapSearchDrawerRules.dragHeight(startShare: start, translation: translation, mapHeight: height)
    }

    private func release(velocity: CGFloat) {
        guard let current = dragHeight else { return }
        settle(at: MapSearchDrawerRules.settledShare(height: current, velocity: velocity, mapHeight: height))
    }

    /// 그 비율에 붙는다 — 0.28초.
    private func settle(at share: Double) {
        withAnimation(.timingCurve(0.32, 0.72, 0, 1, duration: MapSearchDrawerRules.snapDuration)) {
            if let state = model.state, state.drawerShare != share {
                model.state?.toggleDrawer()
            }
            dragHeight = nil
            dragStart = nil
        }
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
                    Button { model.searchEverywhere(viewport: viewport) } label: {
                        Text("이 지역 밖에서도 찾기").font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(TabBar.homePurple)
                            .padding(.horizontal, 16).frame(minHeight: 40)
                            .background(Capsule().fill(Color(.systemBackground)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 24).padding(.horizontal, 12)
        case let .shops(shops):
            VStack(spacing: 0) {
                LazyVStack(spacing: 0) {
                    ForEach(Array(shops.enumerated()), id: \.element.id) { index, place in
                        Button { onPick(place) } label: { MapShopRow(place: place) }
                            .buttonStyle(.plain)
                            .overlay(alignment: .top) {
                                if index > 0 {
                                    Divider().padding(.leading, MapShopRow.leadingInset)
                                }
                            }
                            // 끝에서 한 줄 안이 보이면 다음 쪽(§7-4). 짧은 목록은 처음부터 다 보이니 바로 이어 받는다.
                            .onAppear { model.rowAppeared(index) }
                    }
                }
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                moreRow(state)
            }
        }
    }

    /// 목록 끝 — 받는 중이면 도는 줄, 못 받았으면 「더 불러오지 못했어요 · 다시 시도」.
    @ViewBuilder
    private func moreRow(_ state: MapSearchResults) -> some View {
        if state.loadingMore {
            ProgressView().frame(maxWidth: .infinity).padding(14)
        } else if state.moreFailed {
            Button { model.retryMore() } label: {
                Text("더 불러오지 못했어요 · 다시 시도")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
    }
}
