import SwiftUI

/// 지도 카드의 사진 줄 (MZ2AZ-363) — 작은 사진을 옆으로 넘기고, 누르면 사진첩을 크게 넘겨 본다.
///
/// 촬영지 상세의 대표 사진 자리와 같은 사진첩이다(우리 사진 뒤로 방문자 사진). 카드가 좁아 한 장씩 크게
/// 넘기지 않고 작은 줄로 둔다. 사진이 없으면 그리지 않는다.
struct CardPhotoStrip: View {
    @ObservedObject var gallery: PhotoGallery
    /// 대상의 이름 — 사진에서 연 리뷰 시트의 머리줄에 적는다.
    let title: String
    var onChanged: () -> Void = {}

    @State private var index = 0
    @State private var viewing = false

    private var photos: [GalleryPhoto] {
        gallery.book.photos
    }

    var body: some View {
        if !photos.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 6) {
                    ForEach(Array(photos.enumerated()), id: \.element.key) { number, photo in
                        thumb(number, photo)
                    }
                }
                .padding(.horizontal, 14)
            }
            .frame(height: 70)
            .renewsPhotos(from: gallery)
            .fullScreenCover(isPresented: $viewing) {
                GalleryViewer(
                    gallery: gallery, index: $index,
                    reviews: .sheet(title: title, onChanged: onChanged), entry: "card"
                )
            }
        }
    }

    private func thumb(_ number: Int, _ photo: GalleryPhoto) -> some View {
        Button {
            index = number
            viewing = true
        } label: {
            GalleryImage(photo: photo, size: .tile)
                .frame(width: 92, height: 70)
                .overlay(alignment: .bottomLeading) {
                    if photo.isReview {
                        VisitorBadge(compact: true).padding(3)
                    }
                }
                .clipShape(.rect(cornerRadius: 8))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            PhotoGalleryRules.spoken(index: number, total: gallery.book.total, isReview: photo.isReview)
        )
        .accessibilityHint(tr("크게 보기"))
        .onAppear { gallery.near(number) }
    }
}
