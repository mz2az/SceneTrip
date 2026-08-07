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
/// 프로토타입은 여기에 **씬 스틸**을 썼다(`_SceneCardH` 가 `row.sceneImage`). 서버는
/// 그 이미지를 주지 못한다 — 수집 CSV 에는 `scene_image_url` 이 164 행 전부 채워져
/// 있지만 스키마에 담을 자리가 없어 적재에서 버려진다(실측). 그래서 포스터로 대신한다.
/// 자리를 만드는 것은 권호와 상의할 항목이며 계획서 §6 에 올렸다.
struct SceneCard: View {
    let scene: SceneItem

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RemoteImage(url: scene.posterUrl, symbol: "film")
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

/// 목업 §2.7 — 장면 팝업. 카드는 두 줄로 잘리므로 전문은 여기서 본다.
struct ScenePopup: View {
    let scene: SceneItem
    let placeName: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                RemoteImage(url: scene.posterUrl, symbol: "film")
                    .frame(height: 200)
                    .frame(maxWidth: .infinity)
                    .clipShape(.rect(cornerRadius: 12))

                Text(scene.contentTitle)
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                    .foregroundStyle(Color.accentColor)

                Text(placeName).font(.headline)

                if let description = scene.sceneDescription {
                    Text(description).font(.subheadline)
                }

                // 장바구니는 MZ2AZ-170 이 서버에 이미 구현돼 있다. 화면 연결은 별도
                // 티켓이라 여기서는 자리만 둔다 — 계획서 §2 "장바구니는 담기까지만".
                Button {} label: {
                    Label("담기", systemImage: "bag.badge.plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(true)
            }
            .padding(18)
        }
    }
}

extension SceneApiClient.Scene: @retroactive Identifiable {
    public var id: Int64 {
        contentId
    }
}
