import SceneApiClient
import SwiftUI

/// 길찾기 지도에서 **성지(코스 번호 핀)를 눌렀을 때** 뜨는 카드 (2026-08-28).
///
/// 편의시설 카드(`RoutePlaceCard`)와 하는 일이 다르다 — 저쪽은 네이버에서 남의
/// 정보를 빌려 오고, 이쪽은 **우리 자료**(작품별 장면 설명, 작품검색 탭과 같은
/// 상세 API)를 보여 준다. 장바구니 담기는 없다 — 이미 코스에 있는 곳이다.
/// 대신 「여기로 길찾기」로 목적지를 이곳으로 바꾼다.
struct RouteStopCard: View {
    let stop: RouteStop
    var onReroute: (() -> Void)?
    let onClose: () -> Void

    @State private var detail: PlaceDetail?
    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if loading {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("장면 설명을 찾는 중입니다")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14).padding(.bottom, 12)
            } else if let scenes = detail?.scenes, !scenes.isEmpty {
                // 작품검색 탭이 보여 주는 그 장면 설명이다. 둘까지만 — 카드는
                // 요약이고 전문은 작품검색 탭이 맡는다.
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(scenes.prefix(2), id: \.contentId) { scene in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(scene.contentTitle)
                                .font(.caption.weight(.bold))
                                .foregroundStyle(Color.accentColor)
                            Text(scene.sceneDescription ?? "장면 설명이 아직 없습니다")
                                .font(.caption)
                                .foregroundStyle(
                                    scene.sceneDescription == nil ? .secondary : .primary
                                )
                                .lineLimit(3)
                        }
                    }
                }
                .padding(.horizontal, 14).padding(.bottom, 12)
            } else {
                Text(stop.place.id > 0 ? "장면 정보가 아직 없습니다" : "직접 찍은 곳입니다")
                    .font(.caption).foregroundStyle(.secondary)
                    .padding(.horizontal, 14).padding(.bottom, 12)
            }

            if let onReroute {
                Button(action: onReroute) {
                    HStack(spacing: 6) {
                        Image(systemName: "location.north.fill").font(.caption)
                        Text("여기로 길찾기").font(.subheadline.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.accentColor))
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 14).padding(.bottom, 14)
            }
        }
        // 코스 핀·경로선과 같은 보라 계열 — 「우리 것」의 색이다. 편의시설 카드의
        // 연보라와 같은 결이라 흰 시트 위에서도 구별된다.
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(PinImage.light).opacity(0.16),
                            Color(PinImage.deep).opacity(0.07),
                        ],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                )
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemBackground)))
                .shadow(color: .black.opacity(0.22), radius: 12, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(Color(PinImage.light).opacity(0.5), lineWidth: 1)
        )
        .task(id: stop.id) {
            loading = true
            detail = nil
            // 직접 찍은 핀(id 음수)은 우리 표에 없다 — 물어볼 곳이 없다.
            if stop.place.id > 0 {
                detail = try? await PlacesAPI.getPlace(placeId: stop.place.id)
            }
            loading = false
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(stop.place.name).font(.headline)
                Text([stop.place.type, stop.place.address].compactMap { $0 }
                    .joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }
}
