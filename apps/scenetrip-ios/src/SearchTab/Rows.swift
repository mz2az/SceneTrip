import SceneApiClient
import SwiftUI

/// 작품 탭 한 줄.
struct WorkRow: View {
    let content: ContentSummary

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
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(.rect)
    }
}

/// 장소 탭 한 줄.
struct PlaceRow: View {
    let place: PlaceSummary

    private var works: String {
        (place.contents ?? []).map(\.title).joined(separator: ", ")
    }

    var body: some View {
        HStack(spacing: 12) {
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

    private var message: String {
        switch failure.statusCode {
        case nil: "서버에 연결하지 못했습니다."
        case 500: "잠시 문제가 생겼습니다."
        default: "요청을 처리하지 못했습니다."
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle).foregroundStyle(.secondary)
            Text(message).font(.subheadline)
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
