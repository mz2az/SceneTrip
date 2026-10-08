import PhotosUI
import SwiftUI

/// 사진 고르기 줄의 부품 — 여행후기 쓰기와 리뷰 쓰기가 같이 쓴다.
///
/// 고르는 창은 시스템의 `PhotosPicker` 다. 앱 밖에서 돌아 **사진 보관함 권한을 묻지 않고**,
/// 앱은 사용자가 고른 사진만 받는다.
enum PhotoPick {
    /// 한 칸의 크기와 모서리.
    static let tile: CGFloat = 92
    static let corner: CGFloat = 14

    /// 고른 것의 파일. 못 받으면 nil(아이클라우드에서 못 내려받음 등).
    static func file(from item: PhotosPickerItem) async -> Data? {
        try? await item.loadTransferable(type: Data.self)
    }

    /// 고른 것을 그림으로 — 긴 변 `longest` 까지만 풀고 투명은 흰 바탕(`PhotoShrink.image`). 못 읽으면 nil.
    static func image(from item: PhotosPickerItem, longest: CGFloat) async -> UIImage? {
        guard let file = await file(from: item) else { return nil }
        // 푸는 일은 무겁다 — 화면 밖에서 한다.
        return await Task.detached(priority: .userInitiated) {
            PhotoShrink.image(from: file, longest: longest)
        }.value
    }
}

/// 줄의 첫 칸 「사진 추가」 — 누르면 보관함이 열린다. 아래에 「3/10」.
struct PhotoAddTile: View {
    @Binding var selection: [PhotosPickerItem]
    /// 지금 줄에 있는 장수와 상한.
    let count: Int
    let limit: Int
    /// 이번에 고를 수 있는 장수. 고른 것을 통째로 쥐는 화면은 상한을, 고를 때마다 덧붙이는 화면은 남은 자리를 준다.
    let maxSelection: Int

    var body: some View {
        PhotosPicker(selection: $selection, maxSelectionCount: max(1, maxSelection), matching: .images) {
            VStack(spacing: 6) {
                Image(systemName: "photo.badge.plus").font(.system(size: 22))
                Text(verbatim: "\(count)/\(limit)").font(.caption2)
            }
            .foregroundStyle(.secondary)
            .frame(width: PhotoPick.tile, height: PhotoPick.tile)
            .background(RoundedRectangle(cornerRadius: PhotoPick.corner).fill(Color(.systemGray6)))
        }
        .disabled(maxSelection < 1)
        .opacity(maxSelection < 1 ? 0.5 : 1)
        .accessibilityLabel(tr("사진 추가"))
        .accessibilityValue(Text(verbatim: "\(count)/\(limit)"))
    }
}

/// 사진 오른쪽 위의 「빼기」.
struct PhotoRemoveBadge: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 20))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .black.opacity(0.55))
                .padding(4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tr("사진 빼기"))
    }
}
