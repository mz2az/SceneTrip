import SceneApiClient
import SwiftUI

/// 리뷰 한 줄 (MZ2AZ-363) — 닉네임·별점·날짜, 글, 사진.
///
/// 작성자가 탈퇴했으면 「탈퇴한 사용자」. 글은 번역하지 않는다. 사진 주소는 한 시간 뒤 만료되므로
/// 저장하지 않고 받은 자리에서만 쓴다.
struct ReviewRow: View {
    let review: Review

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(ReviewRules.authorName(review.author))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(review.author == nil ? .secondary : .primary)
                    .lineLimit(1)
                if review.isMine {
                    Text("내 리뷰")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(Color.accentColor.opacity(0.12)))
                        .foregroundStyle(Color.accentColor)
                }
                Spacer(minLength: 8)
                Text(dateText).font(.caption2).foregroundStyle(.tertiary)
            }
            StarRow(rating: review.rating, size: 11)
            if let body = review.body, !body.isEmpty {
                Text(body)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            if !review.photos.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(review.photos, id: \.key) { photo in
                            RemoteImage(url: photo.url, symbol: "photo")
                                .frame(width: 84, height: 84)
                                .clipShape(.rect(cornerRadius: 8))
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // 닉네임·별·날짜·글이 따로 읽히지 않게 한 줄로 묶는다.
        .accessibilityElement(children: .combine)
    }

    /// 「3일 전」, 고쳤으면 「3일 전 · 수정됨」.
    private var dateText: String {
        let when = review.createdAt.formatted(
            .relative(presentation: .named).locale(AppLanguage.currentLocale)
        )
        return ReviewRules.edited(createdAt: review.createdAt, updatedAt: review.updatedAt)
            ? "\(when) · \(tr("수정됨"))"
            : when
    }
}
