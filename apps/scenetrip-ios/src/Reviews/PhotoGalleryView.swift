import SwiftUI

/// 방문자 사진에서 「리뷰 보기」 를 눌렀을 때 그 리뷰로 가는 길(티켓 §4 「누르면 `reviewId` 의 리뷰로」).
enum PhotoReviewLink {
    /// 리뷰 시트를 이 위에 올린다 — 촬영지 상세·카드에서 연 사진첩.
    case sheet(title: String, onChanged: () -> Void)
    /// 이미 리뷰 시트 안이다 — 사진을 닫고 그 리뷰로 내려간다.
    case jump((Int64) -> Void)
}

/// 대표 사진 자리 (MZ2AZ-363) — 우리 사진 뒤로 방문자 사진을 이어 **옆으로 넘겨 본다**.
///
/// - 한 장뿐이면 넘김도 쪽 표시도 없다(전과 같은 모습). 한 장도 없으면 빈 자리 그림.
/// - 오른쪽 아래 「3 / 19」 를 누르면 전체를 격자로(티켓 「사진 전체 보기 (`photoCount`)」), 사진을 누르면 크게.
/// - 방문자 사진이면 왼쪽 아래에 「방문자 사진」.
/// - 보이는 쪽과 양옆만 받고, 끝이 가까워지면 다음 쪽을 받는다.
struct PhotoHero: View {
    @ObservedObject var gallery: PhotoGallery
    /// 대상의 이름 — 격자와 리뷰 시트의 머리줄에 적는다.
    let title: String
    /// 리뷰가 바뀌었다(이 사진첩에서 연 리뷰 시트에서) — 부른 화면이 상세를 다시 읽는다.
    var onChanged: () -> Void = {}

    @State private var index = 0
    @State private var viewing = false
    @State private var browsing = false

    private var photos: [GalleryPhoto] {
        gallery.book.photos
    }

    private var link: PhotoReviewLink {
        .sheet(title: title, onChanged: onChanged)
    }

    var body: some View {
        content
            .renewsPhotos(from: gallery)
            .onChange(of: index) { _, now in gallery.near(now) }
            .onChange(of: photos.count) { _, count in
                index = PhotoGalleryRules.clamped(index, count: count)
            }
            .fullScreenCover(isPresented: $viewing) {
                GalleryViewer(gallery: gallery, index: $index, reviews: link, entry: "detail")
            }
            .sheet(isPresented: $browsing) {
                PhotoGridView(gallery: gallery, title: title, reviews: link, entry: "detail")
            }
    }

    @ViewBuilder private var content: some View {
        if photos.isEmpty {
            RemoteImage(url: nil, symbol: "photo")
        } else if !PhotoGalleryRules.pages(total: gallery.book.total) {
            // 한 장뿐이어도 방문자 사진이면 그렇다고 적는다(사진이 없던 곳에 리뷰 사진 한 장).
            page(0)
                .overlay(alignment: .bottomLeading) {
                    if photos[0].isReview {
                        VisitorBadge().padding(8).allowsHitTesting(false)
                    }
                }
        } else {
            PhotoPager(count: photos.count, index: $index) { page($0) }
                .overlay(alignment: .bottomLeading) {
                    if photos.indices.contains(index), photos[index].isReview {
                        VisitorBadge().padding(8).allowsHitTesting(false)
                    }
                }
                .overlay(alignment: .bottomTrailing) { counter }
        }
    }

    private func page(_ number: Int) -> some View {
        Button {
            index = number
            viewing = true
        } label: {
            GalleryImage(photo: photos[number], active: PhotoGalleryRules.loads(page: number, current: index))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            PhotoGalleryRules.spoken(index: number, total: gallery.book.total, isReview: photos[number].isReview)
        )
        .accessibilityHint(tr("크게 보기"))
    }

    /// 「3 / 19」 — 누르면 전체를 격자로 본다. 보이는 알약은 작고 누르는 자리는 44pt 다.
    private var counter: some View {
        Button {
            browsing = true
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "square.grid.2x2").font(.system(size: 10, weight: .semibold))
                Text(verbatim: PhotoGalleryRules.position(index: index, total: gallery.book.total))
                    .font(.caption.weight(.semibold).monospacedDigit())
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 9).padding(.vertical, 5)
            .background(Capsule().fill(.black.opacity(0.55)))
            .padding(8)
            .frame(minWidth: 44, minHeight: 44, alignment: .bottomTrailing)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(format: tr("사진 전체 보기 (%d)"), gallery.book.total))
    }
}

/// 사진첩 크게 보기 — `PhotoViewer` 에 사진첩을 물린다. 넘기다 끝이 가까우면 다음 쪽을 받고,
/// 방문자 사진의 「리뷰 보기」 를 그 리뷰로 잇는다.
struct GalleryViewer: View {
    @ObservedObject var gallery: PhotoGallery
    var scope: PhotoGallery.Scope = .all
    @Binding var index: Int
    let reviews: PhotoReviewLink
    /// 분석 이벤트의 `entry` — 어디서 열었나.
    let entry: String

    /// 이 위에 올릴 리뷰 시트.
    @State private var opening: Opening?

    private struct Opening: Identifiable {
        let subject: ReviewSubject
        let reviewId: Int64
        var id: Int64 {
            reviewId
        }
    }

    private var photos: [GalleryPhoto] {
        gallery.photos(in: scope)
    }

    var body: some View {
        PhotoViewer(
            photos: photos, total: gallery.total(in: scope), index: $index,
            marksVisitors: true, onReview: open
        )
        .renewsPhotos(from: gallery)
        .onChange(of: index, initial: true) { _, now in gallery.near(now, in: scope) }
        .onChange(of: photos.count) { _, count in
            index = PhotoGalleryRules.clamped(index, count: count)
        }
        .sheet(item: $opening) { target in
            if case let .sheet(title, onChanged) = reviews {
                ReviewsSheet(
                    subject: target.subject, title: title, focusReviewId: target.reviewId, onChanged: onChanged
                )
                .presentationDetents([.large])
            }
        }
        .onAppear {
            if let subject = gallery.subject {
                AppAnalytics.log(.viewPhotos(targetType: subject.kind, entry: entry))
            }
        }
    }

    private func open(_ reviewId: Int64) {
        switch reviews {
        case .sheet:
            if let subject = gallery.subject {
                opening = Opening(subject: subject, reviewId: reviewId)
            }
        case let .jump(jump):
            jump(reviewId)
        }
    }
}

/// 사진 전체를 격자로 (MZ2AZ-363) — 티켓의 「사진 전체 보기 (`photoCount`)」. 끝에 닿으면 다음 쪽을 받는다.
/// 리뷰 시트에서 열면 방문자 사진만 보인다(`scope`).
struct PhotoGridView: View {
    @ObservedObject var gallery: PhotoGallery
    var scope: PhotoGallery.Scope = .all
    /// 대상의 이름.
    let title: String
    let reviews: PhotoReviewLink
    let entry: String

    @Environment(\.dismiss) private var dismiss
    @State private var index = 0
    @State private var viewing = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    private var photos: [GalleryPhoto] {
        gallery.photos(in: scope)
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetHeader(title: heading, subtitle: title) { dismiss() }
            Divider()
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(Array(photos.enumerated()), id: \.element.key) { number, photo in
                        tile(number, photo)
                    }
                }
            }
        }
        .renewsPhotos(from: gallery)
        .fullScreenCover(isPresented: $viewing) {
            GalleryViewer(gallery: gallery, scope: scope, index: $index, reviews: inner, entry: entry)
        }
    }

    private var heading: String {
        let total = gallery.total(in: scope)
        return scope == .all
            ? PhotoGalleryRules.allTitle(total)
            : PhotoGalleryRules.visitorTitle(total)
    }

    /// 리뷰 시트 안에서 연 격자면 — 크게 보기부터 닫고 그 리뷰로 간다(격자는 리뷰 시트가 닫는다).
    private var inner: PhotoReviewLink {
        guard case let .jump(jump) = reviews else { return reviews }
        return .jump { reviewId in
            viewing = false
            jump(reviewId)
        }
    }

    private func tile(_ number: Int, _ photo: GalleryPhoto) -> some View {
        Button {
            index = number
            viewing = true
        } label: {
            GalleryImage(photo: photo, size: .tile)
                .aspectRatio(1, contentMode: .fit)
                .overlay(alignment: .bottomLeading) {
                    // 전체 격자에서만 가린다 — 방문자 사진만 모은 격자는 전부 방문자 사진이다.
                    if scope == .all, photo.isReview {
                        VisitorBadge(compact: true).padding(4)
                    }
                }
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            PhotoGalleryRules.spoken(
                index: number, total: gallery.total(in: scope), isReview: scope == .all && photo.isReview
            )
        )
        .accessibilityHint(tr("크게 보기"))
        .onAppear { gallery.near(number, in: scope) }
    }
}

/// 리뷰 시트 위쪽의 방문자 사진 모아 보기 (MZ2AZ-363) — 한 줄로 옆으로 넘기고, 「모두 보기」 는 격자.
/// 누르면 크게 보고, 거기서 「리뷰 보기」 를 누르면 목록의 그 리뷰로 내려간다. 방문자 사진이 없으면 그리지 않는다.
struct VisitorPhotoStrip: View {
    @ObservedObject var gallery: PhotoGallery
    let title: String
    /// 그 리뷰로 — 리뷰 시트가 목록을 그 줄까지 내린다.
    let onReview: (Int64) -> Void

    @State private var index = 0
    @State private var viewing = false
    @State private var browsing = false

    private var photos: [GalleryPhoto] {
        gallery.photos(in: .reviews)
    }

    /// 사진과 격자를 닫고 그 리뷰로.
    private var link: PhotoReviewLink {
        .jump { reviewId in
            viewing = false
            browsing = false
            onReview(reviewId)
        }
    }

    var body: some View {
        if !photos.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(verbatim: PhotoGalleryRules.visitorTitle(gallery.book.reviewTotal))
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Button(tr("모두 보기")) { browsing = true }
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                }
                .padding(.horizontal, 16)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 6) {
                        ForEach(Array(photos.enumerated()), id: \.element.key) { number, photo in
                            thumb(number, photo)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .frame(height: 84)
            }
            .padding(.vertical, 14)
            .renewsPhotos(from: gallery)
            .fullScreenCover(isPresented: $viewing) {
                GalleryViewer(gallery: gallery, scope: .reviews, index: $index, reviews: link, entry: "reviews")
            }
            .sheet(isPresented: $browsing) {
                PhotoGridView(gallery: gallery, scope: .reviews, title: title, reviews: link, entry: "reviews")
            }
        }
    }

    private func thumb(_ number: Int, _ photo: GalleryPhoto) -> some View {
        Button {
            index = number
            viewing = true
        } label: {
            GalleryImage(photo: photo, size: .tile)
                .frame(width: 84, height: 84)
                .clipShape(.rect(cornerRadius: 8))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            PhotoGalleryRules.spoken(index: number, total: gallery.total(in: .reviews), isReview: false)
        )
        .accessibilityHint(tr("크게 보기"))
        .onAppear { gallery.near(number, in: .reviews) }
    }
}

/// 시트의 머리줄 — 가운데 제목과 작은 부제, 오른쪽에 닫기. 리뷰 시트와 사진 격자가 같은 모양이다.
struct SheetHeader: View {
    let title: String
    let subtitle: String
    let onClose: () -> Void

    var body: some View {
        ZStack {
            VStack(spacing: 1) {
                Text(verbatim: title).font(.headline)
                Text(verbatim: subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 56)
            HStack {
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("닫기"))
            }
        }
        .padding(.horizontal, 6).padding(.top, 6).padding(.bottom, 4)
    }
}
