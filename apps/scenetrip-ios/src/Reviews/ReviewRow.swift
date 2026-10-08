import SceneApiClient
import SwiftUI

/// 리뷰 한 줄 (MZ2AZ-363) — 닉네임·별점·날짜, 글, 사진.
///
/// 작성자가 탈퇴했으면 「탈퇴한 사용자」. 글은 번역하지 않는다. 사진 주소는 한 시간 뒤 만료되므로
/// 저장하지 않고 받은 자리에서만 쓴다. 사진을 누르면 크게 넘겨 본다(`ReviewPhotoThumbs`).
struct ReviewRow: View {
    let review: Review
    /// 내 리뷰일 때만 준다 — 「고치기」 가 뜬다(지우기는 고치는 화면 안에 있다).
    var onEdit: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
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
                HStack {
                    StarRow(rating: review.rating, size: 11)
                    Spacer()
                    if let onEdit {
                        Button(tr("고치기"), action: onEdit)
                            .font(.caption.weight(.semibold))
                            .buttonStyle(.plain)
                            .foregroundStyle(Color.accentColor)
                    }
                }
                if let body = review.body, !body.isEmpty {
                    Text(body)
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // 닉네임·별·날짜·글이 따로 읽히지 않게 한 줄로 묶는다. 사진은 묶지 않는다 — 한 장씩 눌러 크게 본다.
            .accessibilityElement(children: .combine)
            if !review.photos.isEmpty {
                ReviewPhotoThumbs(photos: review.photos)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
