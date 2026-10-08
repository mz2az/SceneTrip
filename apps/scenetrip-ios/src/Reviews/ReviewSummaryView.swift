import SceneApiClient
import SwiftUI

/// 요약 — 왼쪽에 평균과 리뷰 수, 오른쪽에 5~1점 막대.
struct ReviewSummaryView: View {
    let summary: RatingSummary

    var body: some View {
        HStack(alignment: .center, spacing: 20) {
            VStack(spacing: 4) {
                Text(ReviewRules.average(summary.average))
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                StarRow(value: summary.average ?? 0, size: 12)
                Text(ReviewRules.countText(summary.count))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(minWidth: 84)
            VStack(spacing: 5) {
                ForEach(ReviewRules.bars(summary.distribution), id: \.score) { bar in
                    HStack(spacing: 8) {
                        Text(verbatim: "\(bar.score)")
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                            .frame(width: 10)
                        GeometryReader { proxy in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color(.systemGray5))
                                Capsule()
                                    .fill(Color(red: 1.0, green: 0.72, blue: 0.0))
                                    .frame(width: proxy.size.width * bar.share)
                            }
                        }
                        .frame(height: 6)
                        Text(verbatim: "\(bar.count)")
                            .font(.caption2.monospacedDigit()).foregroundStyle(.tertiary)
                            .frame(width: 26, alignment: .trailing)
                    }
                }
            }
        }
        // 막대의 숫자 열 개를 그대로 읽히면 뜻이 없다 — 한 문장으로 읽힌다.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ReviewRules.spoken(summary))
    }
}

/// 별 다섯. 리뷰 한 건은 정수(1~5), 평균은 반 개까지 그린다 — 4.6 을 다섯 개로 채우면 5.0 으로 읽힌다.
struct StarRow: View {
    let value: Double
    var size: CGFloat = 12

    init(rating: Int, size: CGFloat = 12) {
        value = Double(rating)
        self.size = size
    }

    init(value: Double, size: CGFloat = 12) {
        self.value = value
        self.size = size
    }

    var body: some View {
        HStack(spacing: 1.5) {
            ForEach(Array(ReviewRules.stars(for: value).enumerated()), id: \.offset) { _, star in
                Image(systemName: star.symbol)
                    .font(.system(size: size))
                    .foregroundStyle(
                        star == .empty ? Color(.systemGray3) : Color(red: 1.0, green: 0.72, blue: 0.0)
                    )
            }
        }
        .accessibilityElement()
        .accessibilityLabel(String(format: tr("별점 %@점"), ReviewRules.starValue(value)))
    }
}
