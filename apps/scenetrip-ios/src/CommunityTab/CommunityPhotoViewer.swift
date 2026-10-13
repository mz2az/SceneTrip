import SceneApiClient
import SwiftUI

struct CommunityPhotoViewer: View {
    let image: UIImage
    let block: TripPostBlock
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = ScenePhotoRules.minimumScale

    var body: some View {
        SceneZoomView(image: image, scale: $scale)
            .accessibilityLabel(block.caption ?? tr("후기 사진"))
            .accessibilityValue("\(Int((scale * 100).rounded()))%")
            .accessibilityHint(tr("두 손가락으로 확대하거나 두 번 눌러 크게 볼 수 있어요"))
            .background(Color.black.ignoresSafeArea())
            .safeAreaInset(edge: .top) {
                HStack {
                    Text(block.caption ?? tr("후기 사진")).font(.headline).lineLimit(2)
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").frame(width: 44, height: 44)
                    }.buttonStyle(.plain).accessibilityLabel(tr("닫기"))
                }.padding(.horizontal, 16).foregroundStyle(.white).background(.black)
            }
            .safeAreaInset(edge: .bottom) { footer }
            .preferredColorScheme(.dark)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if block.aiGenerated == true {
                Label(tr("AI 변환 이미지"), systemImage: "sparkles").font(.caption)
            }
            if let credit = block.credit, !credit.isEmpty {
                ScrollView {
                    Text(verbatim: credit).font(.caption2).fixedSize(horizontal: false, vertical: true)
                }.frame(maxHeight: 96)
            }
            HStack(spacing: 20) {
                Button { scale = ScenePhotoRules.scale(scale / 2) } label: {
                    Image(systemName: "minus.magnifyingglass").frame(width: 44, height: 44)
                }.disabled(scale <= ScenePhotoRules.minimumScale).accessibilityLabel(tr("축소", at: "사진"))
                Text(verbatim: "\(Int((scale * 100).rounded()))%")
                    .font(.subheadline.monospacedDigit()).frame(minWidth: 52)
                Button { scale = ScenePhotoRules.scale(scale * 2) } label: {
                    Image(systemName: "plus.magnifyingglass").frame(width: 44, height: 44)
                }.disabled(scale >= ScenePhotoRules.maximumScale).accessibilityLabel(tr("확대", at: "사진"))
            }.buttonStyle(.plain)
        }.padding(.horizontal, 16).padding(.bottom, 8).foregroundStyle(.white).background(.black)
    }
}
