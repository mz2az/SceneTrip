import SwiftUI

/// 상세 화면을 그대로 둔 채 실제 씬 사진 한 장을 크게 연다 (MZ2AZ-420).
struct ScenePhotoViewer: View {
    let scene: SceneItem
    let placeName: String

    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @State private var scale: CGFloat = ScenePhotoRules.minimumScale

    var body: some View {
        Group {
            if let photo = ScenePhotoRules.photo(scene) {
                canvas
                    .task(id: attempt) { await load(photo) }
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .safeAreaInset(edge: .top) { header }
        .safeAreaInset(edge: .bottom) { controls }
        .preferredColorScheme(.dark)
    }

    private var canvas: some View {
        ZStack {
            Color.black
            if let image {
                SceneZoomView(image: image, scale: $scale)
                    .accessibilityLabel(String(format: tr("%@ 장면 사진"), scene.contentTitle))
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
                ProgressView().tint(.white)
                    .accessibilityLabel(tr("사진을 불러오는 중"))
            }
        }
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
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16).padding(.bottom, 8)
        .background(.black)
    }

    private func load(_ photo: GalleryPhoto) async {
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
