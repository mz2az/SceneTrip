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
        return names.isEmpty ? "" : "출연 " + names.prefix(4).joined(separator: ", ")
    }

    var body: some View {
        VStack(spacing: 0) {
            DetailHeader(title: summary.title, subtitle: "", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header.padding(.horizontal, 14)

                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        Text("촬영지").font(.headline)
                        Text("\(summary.placeCount)곳")
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
            detail = try? await ContentsAPI.getContent(contentId: summary.id)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 14) {
                RemoteImage(url: summary.posterUrl, symbol: "film")
                    .frame(width: 92, height: 124)
                    .clipShape(.rect(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 6) {
                    Text(meta).font(.subheadline).foregroundStyle(.secondary)
                    if !castLine.isEmpty {
                        Text(castLine).font(.caption).foregroundStyle(.secondary)
                    }
                    if let aliases = detail?.aliases, !aliases.isEmpty {
                        Text(aliases.joined(separator: " · "))
                            .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                }
            }
            if let description = detail?.description, !description.isEmpty {
                Text(description)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
        }
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
