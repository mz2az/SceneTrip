import SceneApiClient
import SwiftUI

/// 드릴다운 2단 — 작품 상세.
///
/// 작품 탭에서 작품을 고르면 **검색 상태를 건드리지 않고** 이 화면으로 들어온다 —
/// 프로토타입과 같은 동선이다. 검색어를 작품명으로 치환하던 초기 구현은 폐기했다:
/// 뒤로 갔을 때 검색어에 작품명이 남아 원래 결과로 돌아갈 수 없었다.
///
/// 목록에서 넘어온 `ContentSummary` 로 먼저 그리고, 상세(`GET /contents/{id}`)가
/// 오면 채운다. 상세에만 있는 것이 줄거리·별칭·출연진이라 그 줄은 도착 후에 뜬다.
struct ContentDetailView: View {
    let summary: ContentSummary

    /// 이 작품의 촬영지. 부모가 `GET /contents/{id}/places` 로 채운다 — 지도 핀과
    /// 같은 배열이므로 행 번호가 곧 핀 번호다.
    let places: [PlaceSummary]
    let loading: Bool
    let onBack: () -> Void
    let onSelectPlace: (PlaceSummary) -> Void

    /// 행의 `+` — 담기와 "담은 핀이 보이게 지도 이동"은 부모가 한다.
    let onSave: (PlaceSummary) -> Void

    @EnvironmentObject private var cart: CartStore
    @State private var detail: ContentDetail?
    @ObservedObject private var likes = LikeStore.shared

    private var meta: String {
        [
            summary.broadcaster,
            summary.releaseYear.map(String.init),
            summary.genres?.joined(separator: " "),
        ]
        .compactMap { $0 }
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
    }

    private var castLine: String {
        let names = (detail?.cast ?? []).map(\.name)
        return names.isEmpty ? "" : tr("출연: ") + names.prefix(4).joined(separator: ", ")
    }

    var body: some View {
        VStack(spacing: 0) {
            DetailHeader(title: summary.title, subtitle: "", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header.padding(.horizontal, 14)

                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        Text("촬영지").font(.headline)
                        Text(trCount("%lld곳", summary.placeCount))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 14)

                    placeList
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
            }
        }
        .task(id: summary.id) {
            detail = nil
            AppAnalytics.log(.viewTitle(contentId: summary.id))
            UsageEvents.view(.content, id: summary.id)
            guard let fresh = try? await ContentsAPI.getContent(contentId: summary.id),
                  !Task.isCancelled, fresh.id == summary.id else { return }
            detail = fresh
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            poster.frame(maxWidth: .infinity, alignment: .center)
            let delisted = UnavailableItem.contentDelisted(summary: summary, detail: detail)
            if delisted {
                UnavailableItemNotice(item: .content)
            }
            Button {
                likes.toggle(summary.id)
            } label: {
                Label(likes.contains(summary.id) ? tr("찜 빼기") : tr("찜하기"),
                      systemImage: likes.contains(summary.id) ? "heart.fill" : "heart")
            }
            .buttonStyle(.bordered)
            .disabled(!UnavailableItem.canToggle(delisted: delisted, saved: likes.contains(summary.id)))
            VStack(alignment: .leading, spacing: 6) {
                if !meta.isEmpty {
                    Text(meta).font(.subheadline).foregroundStyle(.secondary)
                }
                if !castLine.isEmpty {
                    Text(castLine).font(.caption).foregroundStyle(.secondary)
                }
                // 별칭(`Goblin · Guardian: The Lonely and Great God · 쓸…`)은
                // 빼 둔다. 한 줄에 담기지 않아 늘 뒤가 잘렸고, **잘린 별칭은
                // 아무 값도 하지 않는다** — 사용자가 그 이름으로 찾아 들어온
                // 뒤라 이미 아는 정보이기 때문이다. 검색에는 그대로 쓰인다.
            }
            if let description = detail?.description, !description.isEmpty {
                Text(description)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
        }
    }

    @ViewBuilder private var poster: some View {
        if let url = ProducerLink.destination(summary: summary, detail: detail) {
            Link(destination: url) {
                posterImage.overlay(alignment: .bottomTrailing) {
                    Image(systemName: "arrow.up.right.square")
                        .font(.caption.weight(.semibold)).padding(6)
                        .background(.regularMaterial, in: Circle()).padding(6)
                }
                .contentShape(Rectangle())
            }
            .accessibilityLabel(String(format: tr("%@ 제작사 홈페이지 열기"), summary.title))
            .accessibilityHint(tr("외부 브라우저에서 열립니다"))
        } else {
            posterImage
        }
    }

    private var posterImage: some View {
        RemoteImage(url: summary.posterUrl, symbol: "film")
            .frame(height: 176, alignment: .center)
            .frame(maxWidth: .infinity, alignment: .center)
            .clipShape(.rect(cornerRadius: 8))
    }

    @ViewBuilder private var placeList: some View {
        if loading {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 28)
        } else if places.isEmpty {
            Text("촬영지를 불러오지 못했습니다")
                .font(.caption).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 28)
        } else {
            LazyVStack(spacing: 0) {
                ForEach(Array(places.enumerated()), id: \.element.id) { index, place in
                    Button {
                        onSelectPlace(place)
                    } label: {
                        PlaceRow(
                            place: place,
                            number: index + 1,
                            onAdd: { onSave(place) },
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
