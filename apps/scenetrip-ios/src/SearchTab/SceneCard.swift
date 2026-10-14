import SwiftUI

/// 실제 사진의 확대와 장면 설명 열기를 각각 제공한다 (MZ2AZ-420).
struct SceneCard: View {
    let scene: SceneItem
    let onPhoto: () -> Void
    let onDetails: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            photo
            Button(action: onDetails) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(scene.contentTitle).font(.caption.weight(.bold))
                        .foregroundStyle(Color.accentColor)
                    Text(scene.sceneDescription ?? tr("장면 설명이 아직 없습니다"))
                        .font(.subheadline)
                        .foregroundStyle(scene.sceneDescription == nil ? .secondary : .primary)
                        .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                    HStack {
                        Spacer()
                        Image(systemName: "text.alignleft").font(.caption2).foregroundStyle(.tertiary)
                    }
                }
                .padding(10).frame(height: 96, alignment: .top)
            }
            .buttonStyle(.plain)
        }
        .frame(width: 190)
        .background(Color(.systemBackground))
        .clipShape(.rect(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(.systemGray5)))
    }

    @ViewBuilder private var photo: some View {
        if ScenePhotoRules.photo(scene) != nil {
            Button(action: onPhoto) {
                SceneImage(scene: scene)
                    .frame(width: 190, height: 110)
                    .background(Color(.systemBackground))
                    .overlay(alignment: .bottomLeading) { ScenePhotoCount(scene: scene) }
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.caption).foregroundStyle(.white)
                            .padding(6).background(.black.opacity(0.5), in: Circle()).padding(6)
                    }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(format: tr("%@ 장면 사진 크게 보기"), scene.contentTitle))
        } else {
            SceneImage(scene: scene).frame(width: 190, height: 110)
        }
    }
}

/// 미연결·다운로드 실패는 빈 영역이다. 포스터로 대체하지 않는다 (MZ2AZ-394).
private struct SceneImage: View {
    let scene: SceneItem
    var size: PhotoLoader.Size = .tile
    @State private var image: UIImage?
    @State private var shown: String?

    private var photo: GalleryPhoto? {
        ScenePhotoRules.photo(scene)
    }

    var body: some View {
        Color.clear.overlay {
            if shown == photo?.key, let image {
                Image(uiImage: image).resizable().scaledToFit()
            }
        }
        .clipped()
        .task(id: photo?.key) {
            image = nil
            shown = photo?.key
            guard let photo else { return }
            let loaded = await PhotoLoader.load(photo, size: size)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

/// 두 줄로 잘린 장면 설명의 전문. 실제 사진이 있으면 여기서도 확대할 수 있다.
struct ScenePopup: View {
    let scene: SceneItem
    let placeName: String
    @Environment(\.dismiss) private var dismiss
    @State private var viewing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                photo
                Text(scene.contentTitle).font(.caption.weight(.bold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                    .foregroundStyle(Color.accentColor)
                Text(placeName).font(.headline)
                if let description = scene.sceneDescription {
                    Text(description).font(.subheadline)
                }
            }
            .padding(18)
        }
        .overlay(alignment: .topTrailing) { closeButton.padding(.top, 18).padding(.trailing, 18) }
        .fullScreenCover(isPresented: $viewing) {
            ScenePhotoViewer(scene: scene, placeName: placeName)
        }
    }

    @ViewBuilder private var photo: some View {
        if ScenePhotoRules.photo(scene) != nil {
            Button { viewing = true } label: {
                SceneImage(scene: scene, size: .page).frame(height: 200).frame(maxWidth: .infinity)
                    .background(Color(.systemBackground))
                    .clipShape(.rect(cornerRadius: 12))
                    .overlay(alignment: .bottomLeading) { ScenePhotoCount(scene: scene) }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(format: tr("%@ 장면 사진 크게 보기"), scene.contentTitle))
        } else {
            SceneImage(scene: scene).frame(height: 200).frame(maxWidth: .infinity)
        }
    }

    private var closeButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                .frame(width: 28, height: 28).background(Circle().fill(.black.opacity(0.45)))
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tr("닫기"))
    }
}

private struct ScenePhotoCount: View {
    let scene: SceneItem

    var body: some View {
        let count = ScenePhotoRules.photos(scene).count
        if count > 1 {
            Text(verbatim: String(format: tr("사진 %d / %d"), 1, count))
                .font(.caption2.bold()).foregroundStyle(.white)
                .padding(6).background(.black.opacity(0.65), in: Capsule()).padding(6)
                .allowsHitTesting(false)
        }
    }
}
