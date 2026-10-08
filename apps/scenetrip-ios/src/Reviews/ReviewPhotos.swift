import SceneApiClient
import SwiftUI

/// 리뷰에 붙은 사진 줄 (MZ2AZ-363) — 작은 사진을 옆으로 넘기고, 누르면 크게 본다.
/// 리뷰 목록과 「내 리뷰」 가 같이 쓴다.
///
/// 사진 주소는 한 시간 뒤 만료된다 — 저장하지 않고, 목록이 받은 것을 그 자리에서만 쓴다.
/// 목록을 다시 받으면 새 주소가 온다. 같은 사진인지는 저장소 키로 가린다 — 주소가 바뀌어도 다시 받지 않는다.
///
/// 크게 보기는 사진첩과 같은 화면이다(`PhotoViewer`).
struct ReviewPhotoThumbs: View {
    let photos: [ReviewPhoto]
    var size: CGFloat = 84

    @State private var viewing = false
    /// 크게 볼 때 보이는 사진.
    @State private var index = 0

    private var items: [GalleryPhoto] {
        photos.map(PhotoGalleryRules.photo)
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.element.key) { number, photo in
                    Button {
                        index = number
                        viewing = true
                    } label: {
                        GalleryImage(photo: photo, size: .tile)
                            .frame(width: size, height: size)
                            .clipShape(.rect(cornerRadius: 8))
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: tr("사진 %d"), number + 1))
                    .accessibilityHint(tr("크게 보기"))
                }
            }
        }
        .fullScreenCover(isPresented: $viewing) {
            PhotoViewer(photos: items, total: items.count, index: $index)
        }
    }
}
