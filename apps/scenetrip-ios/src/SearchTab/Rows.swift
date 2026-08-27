import SceneApiClient
import SwiftUI

/// 작품 탭 한 줄.
struct WorkRow: View {
    let content: ContentSummary

    /// 찜 토글. 넘기지 않으면 하트를 그리지 않는다 — 장바구니 목록처럼 찜이 뜻을
    /// 갖지 않는 자리가 있다.
    var onLike: (() -> Void)?
    var liked = false

    private var meta: String {
        [
            content.broadcaster,
            content.releaseYear.map(String.init),
            content.genres?.joined(separator: " "),
        ]
        .compactMap { $0 }
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 12) {
            RemoteImage(url: content.posterUrl, symbol: "film")
                .frame(width: 46, height: 62)
                .clipShape(.rect(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 4) {
                Text(content.title).font(.headline).foregroundStyle(.primary)
                Text(meta).font(.caption).foregroundStyle(.secondary)
                Text("촬영지 \(content.placeCount)")
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Capsule().fill(Color(.systemGray6)))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            // **작품에는 하트, 장소에는 플러스** (8/11 회의 확정). 장소의 `+` 와 같은
            // 자리에 두어 사용자가 규칙을 한 번만 배우게 한다.
            //
            // 채운 하트와 빈 하트로 상태를 보인다 — `−` 로 바꾸지 않는 것은 장소
            // 쪽과 같은 이유다. 이 자리의 첫 임무는 "이미 담겼는지" 를 알려 주는 것.
            if let onLike {
                Button(action: onLike) {
                    // 찜은 **빨강**이다(2026-08-28 확정) — 화면이 온통 파랑이라
                    // 파란 하트는 묻혔다. 마이페이지의 하트와도 색이 맞는다.
                    Image(systemName: liked ? "heart.fill" : "heart")
                        .font(.title3)
                        .foregroundStyle(liked ? Color.red : .secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(liked ? "찜 빼기" : "찜하기")
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(.rect)
    }
}

/// 장소 탭 한 줄.
///
/// 오른쪽 `+` 가 계약이 적어 둔 담는 경로 셋 중 첫째다 — "장소 카드의 `+`, 상세의
/// 저장 버튼, 장면 팝업의 북마크. 셋 다 이 하나를 부른다."
struct PlaceRow: View {
    let place: PlaceSummary

    /// 지도 핀에 박힌 번호와 같은 값 — 행과 핀을 눈으로 잇는 다리다. 번호가 없는
    /// 자리(장바구니 등 지도 밖 목록)에서는 배지를 그리지 않는다.
    var number: Int?
    var onAdd: (() -> Void)?
    var saved = false

    private var works: String {
        (place.contents ?? []).map(\.title).joined(separator: ", ")
    }

    var body: some View {
        HStack(spacing: 12) {
            if let number {
                Text("\(number)")
                    .font(.caption2.weight(.heavy))
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(
                        Circle().fill(
                            LinearGradient(
                                colors: [Color(PinImage.light), Color(PinImage.deep)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                    )
            }

            RemoteImage(url: place.imageUrl, symbol: "mappin.and.ellipse")
                .frame(width: 54, height: 54)
                .clipShape(.rect(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 3) {
                Text(place.name).font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                if let address = place.address {
                    Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Text([works, place.type ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            }
            Spacer()
            // 담긴 상태에서 다시 누르면 뺀다.
            //
            // 전에는 `.disabled(saved)` 로 버튼을 꺼 버려서 **한 번 담으면 목록에서
            // 뺄 길이 없었다.** 장바구니 시트를 열어야만 뺄 수 있었다.
            //
            // 확인창을 두지 않는다 — 담기가 한 번에 되는데 빼기만 물어보면 무겁고,
            // 잘못 빼도 다시 담으면 그만이라 되돌리는 비용이 낮다.
            //
            // 아이콘은 `−` 가 아니라 체크를 유지한다. 이 자리의 첫 임무는 "이미
            // 담겼는지" 를 알려 주는 것이고, `−` 로 바꾸면 그 상태가 보이지 않는다.
            if let onAdd {
                Button(action: onAdd) {
                    Image(systemName: saved ? "checkmark.circle.fill" : "plus.circle")
                        .font(.title3)
                        .foregroundStyle(saved ? Color.accentColor : .secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(saved ? "장바구니에서 빼기" : "장바구니에 담기")
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(.rect)
    }
}

struct DetailHeader: View {
    let title: String
    let subtitle: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onBack) {
                Image(systemName: "chevron.left").font(.body.weight(.medium))
            }
            .foregroundStyle(.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 14).padding(.bottom, 10)
    }
}

/// 오류 화면 (계획서 §3-6).
///
/// **서버의 `message` 를 보여 주지 않는다.** 계약이 "사용자에게 그대로 보여 줄 문구가
/// 아니다" 라고 못 박았다. 문구는 여기서 상태 코드별로 갖는다.
///
/// `traceId` 는 `500` 에만 실리므로 "없으면 숨긴다" 가 아니라 **있을 때만 그린다**.
/// 눌러서 복사하게 한 것은 외국인 대상 앱이라 받아 적게 하는 것이 무리여서다
/// (§3-6 팀 확인 항목 #1 의 초안 — 확정은 논의로 한다).
struct ErrorView: View {
    let failure: ApiFailure
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle).foregroundStyle(.secondary)
            Text(failure.message).font(.subheadline)
            if failure.isRetryable {
                Button("다시 시도", action: onRetry)
                    .buttonStyle(.borderedProminent)
            }
            if let traceId = failure.traceId {
                Text(traceId)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}
