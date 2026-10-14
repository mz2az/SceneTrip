import SwiftUI

/// 상세 화면을 유지하고 서버 순서대로 씬 사진을 넘긴다 (MZ2AZ-420·430).
struct ScenePhotoViewer: View {
    let scene: SceneItem
    let placeName: String

    @Environment(\.dismiss) private var dismiss
    @State private var index = 0

    private var photos: [GalleryPhoto] {
        ScenePhotoRules.photos(scene)
    }

    var body: some View {
        Group {
            if photos.indices.contains(index) {
                ScenePhotoPage(photo: photos[index], title: scene.contentTitle)
                    .id("\(index):\(photos[index].key)")
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .safeAreaInset(edge: .top) { header }
        .safeAreaInset(edge: .bottom) { controls }
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text(verbatim: scene.contentTitle).font(.headline).lineLimit(2)
            Spacer(minLength: 0)
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.headline)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr("닫기"))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .background(.black)
    }

    private var controls: some View {
        VStack(spacing: 8) {
            Text(verbatim: placeName).font(.caption).lineLimit(2)
            if photos.count > 1 {
                HStack(spacing: 20) {
                    Button { index -= 1 } label: {
                        Image(systemName: "chevron.left").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(tr("이전 사진"))
                    .disabled(index == 0)
                    Text(verbatim: String(format: tr("사진 %d / %d"), index + 1, photos.count))
                        .font(.subheadline.monospacedDigit())
                    Button { index += 1 } label: {
                        Image(systemName: "chevron.right").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(tr("다음 사진"))
                    .disabled(index >= photos.count - 1)
                }
                .buttonStyle(.plain)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16).padding(.bottom, 8)
        .background(.black)
    }
}

/// 페이지를 바꾸면 확대·오류 상태와 이전 다운로드가 함께 폐기된다.
private struct ScenePhotoPage: View {
    let photo: GalleryPhoto
    let title: String
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @State private var scale: CGFloat = ScenePhotoRules.minimumScale

    var body: some View {
        ZStack {
            Color.black
            if let image {
                SceneZoomView(image: image, scale: $scale)
                    .accessibilityLabel(String(format: tr("%@ 장면 사진"), title))
                    .accessibilityValue("\(Int((scale * 100).rounded()))%")
                    .accessibilityHint(tr("두 손가락으로 확대하거나 두 번 눌러 크게 볼 수 있어요"))
            } else if failed {
                VStack(spacing: 14) {
                    Image(systemName: "photo").font(.largeTitle)
                    Text("사진을 불러오지 못했어요")
                    Button { attempt += 1 } label: {
                        Label(tr("다시 시도"), systemImage: "arrow.clockwise")
                            .padding(.horizontal, 16).frame(minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                }
                .foregroundStyle(.white)
            } else {
                ProgressView().tint(.white).accessibilityLabel(tr("사진을 불러오는 중"))
            }
        }
        .task(id: attempt) { await load() }
        .safeAreaInset(edge: .bottom) { zoomControls }
    }

    private var zoomControls: some View {
        HStack(spacing: 20) {
            Button { scale = ScenePhotoRules.scale(scale / 2) } label: {
                Image(systemName: "minus.magnifyingglass").frame(width: 44, height: 44)
            }
            .accessibilityLabel(tr("축소", at: "사진"))
            .disabled(image == nil || scale <= ScenePhotoRules.minimumScale)
            Text(verbatim: "\(Int((scale * 100).rounded()))%")
                .font(.subheadline.monospacedDigit()).frame(minWidth: 52)
            Button { scale = ScenePhotoRules.scale(scale * 2) } label: {
                Image(systemName: "plus.magnifyingglass").frame(width: 44, height: 44)
            }
            .accessibilityLabel(tr("확대", at: "사진"))
            .disabled(image == nil || scale >= ScenePhotoRules.maximumScale)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .padding(.horizontal, 16).padding(.bottom, 8)
        .background(.black)
    }

    private func load() async {
        failed = false
        image = PhotoLoader.cached(photo, size: .page)
        if image == nil {
            let fetched = await PhotoLoader.load(photo, size: .page)
            guard !Task.isCancelled else { return }
            image = fetched
        }
        failed = image == nil
    }
}
