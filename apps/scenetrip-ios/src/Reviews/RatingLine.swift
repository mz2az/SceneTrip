import SceneApiClient
import SwiftUI

/// 별점 한 줄 — 「★4.6 · 리뷰 128」 (MZ2AZ-363). 누르면 리뷰 시트가 열린다.
///
/// 촬영지 상세·성지 카드·편의시설 카드 세 곳에 같은 줄이 붙는다. 서버가 별점을 안 실어 주면
/// (`rating == nil`) **아무것도 그리지 않는다.**
struct RatingLine: View {
    let rating: RatingSummary?
    let onTap: () -> Void

    var body: some View {
        switch ReviewRules.line(for: rating) {
        case .hidden:
            EmptyView()
        case .empty:
            button {
                Image(systemName: "star").foregroundStyle(.secondary)
                Text("아직 리뷰가 없어요").foregroundStyle(.secondary)
            }
        case let .rated(average, count):
            button {
                Image(systemName: "star.fill").foregroundStyle(Color(red: 1.0, green: 0.72, blue: 0.0))
                Text(average).fontWeight(.semibold).foregroundStyle(.primary)
                Text(verbatim: "·").foregroundStyle(.tertiary)
                Text(ReviewRules.countText(count)).foregroundStyle(.secondary)
            }
        }
    }

    /// 가운뎃점을 읽지 않게 한 문장으로.
    private var spoken: String {
        rating.map(ReviewRules.spoken) ?? ""
    }

    private func button(@ViewBuilder _ label: () -> some View) -> some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                label()
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .semibold)).foregroundStyle(.tertiary)
            }
            .font(.caption)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spoken)
        .accessibilityHint(tr("리뷰 보기"))
    }
}

extension View {
    /// 리뷰 시트를 올릴 자리. `subject` 가 차면 뜬다.
    func reviewsSheet(_ subject: Binding<ReviewSubject?>, title: String) -> some View {
        sheet(item: subject) { target in
            ReviewsSheet(subject: target, title: title)
                .presentationDetents([.large])
        }
    }
}
