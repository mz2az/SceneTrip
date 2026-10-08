import SceneApiClient
import SwiftUI

/// 리뷰에 붙은 사진 줄 (MZ2AZ-363) — 작은 사진을 옆으로 넘기고, 누르면 크게 본다.
/// 리뷰 목록과 「내 리뷰」 가 같이 쓴다.
///
/// 사진 주소는 한 시간 뒤 만료된다 — 저장하지 않고, 목록이 받은 것을 그 자리에서만 쓴다.
/// 목록을 다시 받으면 새 주소가 온다.
struct ReviewPhotoThumbs: View {
    let photos: [ReviewPhoto]
    var size: CGFloat = 84

    @State private var viewing: Viewing?

    /// 크게 볼 때 처음 보일 사진.
    private struct Viewing: Identifiable {
        let index: Int
        var id: Int {
            index
        }
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(photos.enumerated()), id: \.element.key) { index, photo in
                    Button {
                        viewing = Viewing(index: index)
                    } label: {
                        RemoteImage(url: photo.url, symbol: "photo")
                            .frame(width: size, height: size)
                            .clipShape(.rect(cornerRadius: 8))
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: tr("사진 %d"), index + 1))
                    .accessibilityHint(tr("크게 보기"))
                }
            }
        }
        .fullScreenCover(item: $viewing) { start in
            ReviewPhotoViewer(photos: photos, index: start.index)
        }
    }
}

/// 사진 크게 보기 — 검은 바탕에 한 장씩, 옆으로 넘긴다. 위에 「2/3」 과 닫기.
struct ReviewPhotoViewer: View {
    let photos: [ReviewPhoto]
    @State var index: Int

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(photos.enumerated()), id: \.offset) { offset, photo in
                    page(photo)
                        .tag(offset)
                        .accessibilityLabel(String(format: tr("사진 %d"), offset + 1))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            HStack {
                Text(verbatim: "\(index + 1)/\(photos.count)")
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Capsule().fill(.black.opacity(0.45)))
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(.black.opacity(0.45)))
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("닫기"))
            }
            .padding(.horizontal, 12).padding(.top, 4)
        }
    }

    /// 한 장 — 잘리지 않게 화면 안에 맞춘다(목록의 작은 사진은 네모로 잘려 있다).
    private func page(_ photo: ReviewPhoto) -> some View {
        AsyncImage(url: URL(string: photo.url)) { phase in
            switch phase {
            case let .success(image):
                image.resizable().scaledToFit()
            case .empty:
                ProgressView().tint(.white)
            default:
                // 주소가 만료됐거나(한 시간) 끊겼다.
                VStack(spacing: 8) {
                    Image(systemName: "photo").font(.system(size: 30))
                    Text("사진을 불러오지 못했어요").font(.footnote)
                }
                .foregroundStyle(.white.opacity(0.7))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
