import PhotosUI
import SwiftUI

/// 리뷰 쓰기의 사진 줄 (MZ2AZ-363) — 첫 칸 「사진 추가」, 그 뒤로 붙어 있던 사진과 새로 고른 사진.
///
/// 칸마다 올리는 중(돌아가는 표시)·실패(다시 시도)·멈춰 기다림(멈춤 표시)이 보이고, 어느 칸이든 뺄 수 있다.
/// 줄 아래 한 줄은 진행(「사진 올리는 중 2/3」)이거나, 못 올린 사진이 몇 번째이고 왜인지다.
struct ReviewPhotoStrip: View {
    @ObservedObject var draft: ReviewPhotoDraft
    /// 리뷰를 저장하는 중이다 — 사진을 더하거나 빼지 못한다.
    let locked: Bool

    /// 고르는 창과 잇는 값. 받자마자 `draft` 로 넘기고 비운다 — 고를 때마다 덧붙인다.
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    PhotoAddTile(
                        selection: $picked, count: draft.slots.count, limit: ReviewPhotoRules.limit,
                        maxSelection: draft.remaining
                    )
                    ForEach(Array(draft.slots.enumerated()), id: \.element.id) { index, slot in
                        tile(slot, number: index + 1)
                    }
                }
            }
            // 폼의 옆 여백 밖으로도 넘어가 보이게 — 잘린 것이 아니라 넘길 것이 더 있어 보인다.
            .scrollClipDisabled()
            status
        }
        .disabled(locked)
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            draft.add(items)
            picked = []
        }
    }

    private func tile(_ slot: ReviewPhotoDraft.Slot, number: Int) -> some View {
        picture(slot)
            .frame(width: PhotoPick.tile, height: PhotoPick.tile)
            .overlay { veil(slot) }
            .clipShape(.rect(cornerRadius: PhotoPick.corner))
            .overlay(alignment: .topTrailing) {
                PhotoRemoveBadge { draft.remove(slot.id) }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(String(format: tr("사진 %d"), number))
            .accessibilityValue(spoken(slot.state))
    }

    @ViewBuilder
    private func picture(_ slot: ReviewPhotoDraft.Slot) -> some View {
        if let preview = slot.preview {
            // 미리보기는 올라갈 파일과 같은 길로 그린 것이다 — 투명한 PNG 도 흰 바탕으로 보인다.
            Color.clear.overlay { Image(uiImage: preview).resizable().scaledToFill() }
        } else if let url = slot.remoteUrl {
            RemoteImage(url: url, symbol: "photo")
        } else {
            Color(.systemGray5)
        }
    }

    /// 사진 위에 덮는 상태 — 올리는 중이면 어둡게 하고 돌리고, 실패면 다시 시도.
    @ViewBuilder
    private func veil(_ slot: ReviewPhotoDraft.Slot) -> some View {
        switch slot.state {
        case .attached, .uploaded:
            EmptyView()
        case .waiting where draft.stalled > 0:
            // 앞 사진이 실패해 줄이 멈췄다 — 도는 표시를 보이면 올라가는 줄 안다.
            Color.black.opacity(0.35).overlay {
                Image(systemName: "pause.fill").font(.system(size: 18)).foregroundStyle(.white)
            }
        case .waiting, .uploading:
            Color.black.opacity(0.35).overlay { ProgressView().tint(.white) }
        case let .failed(failure):
            Color.black.opacity(0.55).overlay {
                if PhotoUploadRules.retryable(failure) {
                    Button {
                        draft.retry(slot.id)
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise").font(.system(size: 18, weight: .semibold))
                            Text("다시 시도").font(.caption2.weight(.semibold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                } else {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 20)).foregroundStyle(.white)
                }
            }
        }
    }

    private func spoken(_ state: ReviewPhotoState) -> String {
        switch state {
        case .attached, .uploaded: ""
        case .waiting where draft.stalled > 0: tr("기다리는 중")
        case .waiting, .uploading: tr("올리는 중")
        case let .failed(failure): PhotoUploadRules.text(failure)
        }
    }

    /// 줄 아래 한 줄 — 실패가 먼저, 다음이 진행, 아무 일도 없으면 안내.
    @ViewBuilder
    private var status: some View {
        let failures = draft.slots.enumerated().compactMap { index, slot -> (number: Int, text: String)? in
            guard case let .failed(failure) = slot.state else { return nil }
            return (index + 1, PhotoUploadRules.text(failure))
        }
        if !failures.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(failures, id: \.number) { failure in
                    // 몇 번째 사진이 왜 안 됐는지 — 「사진 2: 사진이 너무 커요…」
                    Text(verbatim: String(format: tr("사진 %d"), failure.number) + ": " + failure.text)
                }
                if draft.stalled > 0 {
                    // 저장이 왜 막혔는지 — 남은 사진이 멈춰 있다.
                    Text(String(format: tr("남은 사진 %d장은 기다리고 있어요. 「다시 시도」 를 누르면 이어서 올려요"), draft.stalled))
                        .foregroundStyle(.secondary)
                }
            }
            .font(.footnote).foregroundStyle(.red)
        } else if ReviewPhotoRules.busy(draft.states) {
            let progress = draft.progress
            Text(String(format: tr("사진 올리는 중 %d/%d"), progress.current, progress.total))
                .font(.footnote).foregroundStyle(.secondary)
        } else {
            Text(String(format: tr("사진은 %d장까지. 촬영 위치 정보는 올리기 전에 지워요"), ReviewPhotoRules.limit))
                .font(.caption).foregroundStyle(.tertiary)
        }
    }
}
