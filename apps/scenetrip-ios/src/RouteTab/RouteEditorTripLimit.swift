import SwiftUI
import UIKit

/// 안내 띠의 **길찾기 한도 안내 — 지도 앱으로 넘기기** (MZ2AZ-366 D, 계획 `app-retry.md` §12).
///
/// 앱 안 길찾기가 한도(429 `NAVIGATION_LIMIT_REACHED`)에 걸려도 여행은 멈추지 않는다. 안내는 켜진 채로
/// (목적지·직선 계획선·머무름 도착 판정·「여기 도착함」 그대로) 그 구간만 카카오맵·네이버 지도가 대신 안내한다.
/// 규칙은 `NavLimit`, 상태는 `NavLimitStore`, 주소는 `ExternalDirections`.
extension RouteEditorView {
    @ViewBuilder
    var tripLimitNotice: some View {
        switch trip.limits.state {
        case .clear, .lifted:
            // 풀렸다(또는 계정이 바뀌어 잊었다). **저절로 다시 부르지 않는다** — 유료 호출은 사람이 누른다.
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Label("이제 앱 안에서 다시 길을 찾을 수 있어요", systemImage: "checkmark.circle")
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("nav-limit-lifted")
                tripRetryButton
            }
        case let .reached(block):
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Label(NavLimit.message(block.window), systemImage: "hourglass")
                        .font(.caption).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("nav-limit-notice")
                    // 풀리는 때를 모르면 잠그지 않는다 — 바로 다시 물어볼 수 있다. 서버가 다시 정한다.
                    if block.until == nil {
                        tripRetryButton
                    }
                }
                handoffButtons
                Text("도착하면 돌아와서 「여기 도착함」 을 눌러 주세요")
                    .font(.caption2).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var tripRetryButton: some View {
        Button("다시 시도") { trip.retry() }
            .font(.caption.weight(.semibold))
            .buttonStyle(.bordered).controlSize(.mini)
            .accessibilityIdentifier("nav-retry")
    }

    /// 넘기는 단추 — 카카오맵은 언제나(앱이 없으면 웹), 네이버 지도는 앱이 깔려 있을 때만.
    @ViewBuilder
    private var handoffButtons: some View {
        let offers = handoffOffers
        // 좁으면(영어·큰 글자) 두 줄로 내려간다.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { handoffRow(offers) }
            VStack(alignment: .leading, spacing: 0) { handoffRow(offers) }
        }
    }

    private func handoffRow(_ offers: [ExternalDirections.Offer]) -> some View {
        ForEach(offers, id: \.app) { offer in
            Button {
                open(offer)
            } label: {
                Label(
                    offer.app == .kakao ? tr("카카오맵에서 길찾기") : tr("네이버 지도에서 길찾기"),
                    systemImage: "arrow.up.forward.app"
                )
                // 큰 글자에서는 두 줄로 내려간다 — 한 줄로 자르면 「카카오맵에서…」 만 남는다.
                .font(.caption.weight(.semibold))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.accentColor.opacity(0.14))
                )
                // 보이는 알약은 낮아도 **누를 자리는 44pt** 다(걸으면서 누르는 단추다).
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityHint(offer.opensApp ? tr("지도 앱이 열려요") : tr("브라우저에서 지도가 열려요"))
            .accessibilityIdentifier("nav-handoff-\(offer.app.rawValue)")
        }
    }

    /// 지금 자리 → 안내 중인 성지. 이동 수단은 앱 안 길찾기와 같은 대중교통이다.
    private var handoffOffers: [ExternalDirections.Offer] {
        guard let target = trip.target else { return [] }
        let origin = trip.here.map {
            ExternalDirections.Spot(name: tr("현재 위치", at: "출발"), latitude: $0.latitude, longitude: $0.longitude)
        }
        let destination = ExternalDirections.Spot(
            name: target.place.name, latitude: target.place.latitude, longitude: target.place.longitude
        )
        let installed = Set(ExternalDirections.App.allCases.filter { app in
            URL(string: "\(app.scheme)://").map(UIApplication.shared.canOpenURL) ?? false
        })
        return ExternalDirections.offers(
            from: origin, to: destination, mode: .transit,
            appName: Bundle.main.bundleIdentifier ?? "com.mz2az.scenetrip", installed: installed
        )
    }

    /// 연다. 앱 스킴이 끝내 안 열리면(방금 지웠다 등) 웹으로 물러선다. **안내는 끄지 않는다** — 돌아오면
    /// 그 자리에서 이어진다.
    private func open(_ offer: ExternalDirections.Offer) {
        UIApplication.shared.open(offer.url, options: [:]) { opened in
            if !opened, let fallback = offer.fallback {
                UIApplication.shared.open(fallback)
            }
        }
    }
}
