import SceneApiClient
import SwiftUI

/// 엔터 뒤 — 검색 결과를 지도에 펼친다 (MZ2AZ-547 M7, 웹 `protoPoi.tsx` `ResultsView`).
///
/// 위에 검색어 알약(누르면 고쳐 쓰기) + ✕, 지도를 옮긴 뒤에만 「이 지역에서 다시 찾기」, 아래 서랍(42% ↔ 78%)에
/// 찾은 가게·장소. 촬영지는 찾지 않는다(2026-11-10 사용자 요청) — 「촬영지 N」 탭·사진 핀·일차 고르기 창을 뺐다.
/// 줄이나 지도 점을 누르면 그곳으로 가서 정보 카드(「코스에 담기」). 지도 점은 `RouteMapView` 가 그린다(`MapSearchPinRenderer`).
/// 상태 규칙은 `MapSearchResults`.
struct MapSearchResultsView: View {
    @ObservedObject var model: MapSearchResultsModel
    /// 서랍이 서는 영역의 높이(지도 전체).
    let height: CGFloat
    let viewport: MapViewport?
    /// 결과를 눌렀다 — 그곳으로 가서 정보 카드(코스에 담기).
    let onPick: (RouteGuide.Place) -> Void
    let onEdit: (String) -> Void
    let onExit: () -> Void

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
        }
    }

    /// 검색어 알약 — 누르면 고쳐 쓰기, ✕ 는 끝내기.
    private func queryPill(_ state: MapSearchResults) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "magnifyingglass").font(.system(size: 15)).foregroundStyle(.secondary)
            Button { onEdit(state.query) } label: {
                HStack(spacing: 6) {
                    Text("“\(state.query)”").font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    if let count = state.count {
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
            ScrollView {
                content(state).padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 20)
            }
        }
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16)
                .fill(Color(.systemGroupedBackground))
                .shadow(color: .black.opacity(0.15), radius: 8, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
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
        case let .shops(shops):
            LazyVStack(spacing: 0) {
                ForEach(Array(shops.enumerated()), id: \.element.id) { index, place in
                    Button { onPick(place) } label: { MapShopRow(place: place) }
                        .buttonStyle(.plain)
                        .overlay(alignment: .top) {
                            if index > 0 {
                                Divider().padding(.leading, 66)
                            }
                        }
                }
            }
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }
}
