import SceneApiClient
import SwiftUI

/// 명세의 `Scene`(장면 = 작품 × 장소)과 SwiftUI 의 `Scene`(앱 장면)이 이름이 같다.
/// 이 파일이 다루는 것은 전자이므로 별칭으로 못 박는다 — 매번 한정해 쓰면 읽기 나쁘다.
typealias SceneItem = SceneApiClient.Scene

/// 드릴다운 3단 — 촬영지 상세.
///
/// 목록에서 넘어온 `PlaceSummary` 로 먼저 그리고, 상세(`GET /places/{id}`)가 오면
/// 채운다. 상세에만 있는 것이 **작품별 장면**(`scenes`) 이라 그 절은 도착 후에 뜬다.
struct PlaceDetailView: View {
    let summary: PlaceSummary
    let load: (Int64) async throws -> PlaceDetail
    let onBack: () -> Void

    @EnvironmentObject private var cart: CartStore
    @Environment(\.openURL) private var openURL
    @State private var detail: PlaceDetail?
    @State private var scene: SceneItem?

    private var scenes: [SceneItem] {
        detail?.scenes ?? []
    }

    var body: some View {
        VStack(spacing: 0) {
            DetailHeader(title: summary.name, subtitle: summary.address ?? "", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    RemoteImage(url: detail?.imageUrl ?? summary.imageUrl, symbol: "photo")
                        .frame(height: 180)
                        .frame(maxWidth: .infinity)
                        .clipShape(.rect(cornerRadius: 10))

                    if let type = summary.type, !type.isEmpty {
                        Text(type)
                            .font(.caption)
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(Capsule().fill(Color(.systemGray6)))
                            .foregroundStyle(.secondary)
                    }

                    actions

                    if !scenes.isEmpty {
                        sceneSection
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
        }
        .task(id: summary.id) {
            detail = try? await load(summary.id)
        }
        .sheet(item: $scene) { picked in
            ScenePopup(scene: picked, placeName: summary.name)
                .presentationDetents([.medium])
        }
    }

    /// 베타의 장소 상세와 같은 두 버튼이다 — 담기와 네이버 지도.
    ///
    /// 담기는 계약이 적어 둔 세 경로 중 하나이고(§ `/cart/items`), 이미 담긴 장소면
    /// 체크 + "저장됨" 으로 바뀐다. 네이버 지도는 외부 앱/브라우저로 넘긴다 —
    /// 길찾기와 영업정보는 우리가 만들 것이 아니다.
    private var actions: some View {
        HStack(spacing: 8) {
            let saved = cart.contains(summary.id)
            Button {
                Task {
                    if saved {
                        await cart.remove(placeId: summary.id)
                    } else {
                        await cart.add(placeId: summary.id)
                    }
                }
            } label: {
                Label(
                    saved ? "담김 · 누르면 빼기" : "장바구니에 담기",
                    systemImage: saved ? "checkmark" : "bag.badge.plus"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            if let link = detail?.naverPlaceUrl, let url = URL(string: link) {
                Button { openURL(url) } label: {
                    Label("네이버 지도", systemImage: "arrow.up.right.square")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    /// 목업 §2.6 — "이 장소의 장면 · N개 작품이 이곳에서 촬영".
    ///
    /// 한 장소를 여러 작품이 함께 쓰는 일이 실제로 있다(북촌한옥마을 = 도깨비 +
    /// 케이팝 데몬 헌터스). 그럴 때 **작품마다 장면 설명이 다르므로** 목록으로 편다.
    private var sceneSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text("이 장소의 장면").font(.headline)
                Text("\(scenes.count)개 작품이 이곳에서 촬영")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(scenes, id: \.contentId) { item in
                        Button { scene = item } label: { SceneCard(scene: item) }
                            .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

/// 가로로 넘기는 장면 카드.
///
/// **장면 스틸을 쓴다** (`sceneImageUrl`). 한동안 포스터로 대신했는데, 그러면 한 작품의
/// 장면이 여럿일 때 카드가 전부 같은 그림이 됐다 — "이 장면이 찍힌 곳" 을 보여 주는
/// 것이 이 앱의 핵심이라 포스터로는 대체가 안 된다.
///
/// 스틸이 없는 장면은 포스터로 물러선다. 계약이 "수집분에 없는 장면이 있어 null 이 올
/// 수 있다" 고 적어 뒀다.
struct SceneCard: View {
    let scene: SceneItem

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RemoteImage(url: scene.sceneImageUrl ?? scene.posterUrl, symbol: "film")
                .frame(width: 190, height: 110)

            VStack(alignment: .leading, spacing: 4) {
                Text(scene.contentTitle)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.accentColor)
                Text(scene.sceneDescription ?? "장면 설명이 아직 없습니다")
                    .font(.subheadline)
                    .foregroundStyle(scene.sceneDescription == nil ? .secondary : .primary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack {
                    Spacer()
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.caption2).foregroundStyle(.tertiary)
                }
            }
            .padding(10)
            .frame(height: 96, alignment: .top)
        }
        .frame(width: 190)
        .background(Color(.systemBackground))
        .clipShape(.rect(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12).stroke(Color(.systemGray5))
        )
    }
}

/// 장면 팝업. 카드에서 두 줄로 잘린 설명의 전문을 본다.
///
/// **담기 버튼을 두지 않는다.** 베타에는 있었으나 잘못된 설계였다 — 장바구니에 담기는
/// 단위는 **장소**인데(계약의 `CartItemCreate` 가 `placeId` 를 받는다), 장면 팝업은
/// "이 장소에서 어느 작품의 어떤 장면을 찍었나" 를 보는 자리다. 거기서 담으면
/// 사용자는 장면을 담는다고 생각하는데 실제로는 장소가 담긴다.
///
/// 담는 자리는 이미 둘 있다 — 장소 목록 행의 `+`, 장소 상세의 저장 버튼. 둘 다
/// 장소를 다루는 자리다.
struct ScenePopup: View {
    let scene: SceneItem
    let placeName: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                RemoteImage(url: scene.sceneImageUrl ?? scene.posterUrl, symbol: "film")
                    .frame(height: 200)
                    .frame(maxWidth: .infinity)
                    .clipShape(.rect(cornerRadius: 12))
                    // 닫기는 아래로 끌어내리는 것뿐이었다. 그 동작을 아는 사용자만
                    // 팝업을 닫을 수 있다.
                    //
                    // 포스터 **위에** 겹친다. 포스터가 팝업 맨 위를 꽉 채우고 있어
                    // 바깥 여백에 두면 버튼 하나 때문에 위쪽이 벌어진다.
                    //
                    // 원 배경을 까는 이유: 포스터는 작품마다 밝기가 제각각이라
                    // 선 아이콘만 두면 밝은 포스터에서 보이지 않는다.
                    .overlay(alignment: .topTrailing) { closeButton }

                Text(scene.contentTitle)
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                    .foregroundStyle(Color.accentColor)

                Text(placeName).font(.headline)

                if let description = scene.sceneDescription {
                    Text(description).font(.subheadline)
                }
            }
            .padding(18)
        }
    }

    /// 보이는 원은 28pt 인데 누를 수 있는 자리는 44pt 다. 애플이 권하는 최소
    /// 터치 크기가 44pt 이고, 그보다 작으면 손가락이 자꾸 빗나간다.
    private var closeButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(.black.opacity(0.45)))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("닫기")
    }
}

extension SceneApiClient.Scene: @retroactive Identifiable {
    public var id: Int64 {
        contentId
    }
}
