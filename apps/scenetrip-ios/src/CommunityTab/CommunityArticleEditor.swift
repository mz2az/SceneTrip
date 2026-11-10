import PhotosUI
import SceneApiClient
import SwiftUI
import UIKit

/// 블로그형 본문 편집 — 글 조각과 사진을 쓴 순서대로 (MZ2AZ-547 K11, 웹 `community/ArticleEditor.tsx`).
///
/// 「사진 넣기」 는 마지막으로 글을 쓰던 자리(커서)에서 그 글 조각을 둘로 나누고 사이에 사진을 넣는다. 글을 쓰지 않았으면 맨 끝에.
/// 사진은 0.3초 꾹 눌러 위아래로 옮기고, 설명을 달고, ✕ 로 뺀다. 첫 사진이 목록의 대표 사진이다.
/// 저장 형식은 그대로다 — 이 목록에서 본문·사진 위치를 계산해 `bodyBlocks` 를 만든다(`CommunityDraft`).
struct CommunityArticleEditor: View {
    @Binding var items: [CommunityDraftItem]
    @Binding var meta: [UUID: CommunityPhotoPlacement]
    @ObservedObject var photos: ReviewPhotoDraft
    var locked = false
    /// 사진을 고르기 전에 — 비로그인이면 false 를 돌려 고르기를 막는다(사진은 올릴 때 로그인이 필요하다).
    var canPick: () -> Bool = { true }

    /// 마지막으로 글을 쓰던 조각과 커서(UTF-16) — 「사진 넣기」 가 여기를 나눈다.
    @State private var caret: (id: UUID, at: Int)?
    /// 사진을 고르는 중에 정해 둔 넣을 자리(그 항목 뒤). 사진 칸이 생기면 그 자리에 놓는다.
    @State private var insertAfter: Int?
    @State private var picking = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var dragging: UUID?
    @State private var dragOffset: CGFloat = 0
    @State private var frames: [UUID: CGRect] = [:]

    private static let space = "community-article-editor"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    itemView(item, index: index)
                        .background(GeometryReader { proxy in
                            Color.clear.preference(key: ItemFrames.self, value: [item.id: proxy.frame(in: .named(Self.space))])
                        })
                }
            }
            .coordinateSpace(name: Self.space)
            .onPreferenceChange(ItemFrames.self) { frames = $0 }

            HStack(spacing: 8) {
                Button {
                    guard canPick() else { return }
                    let (next, spot) = CommunityDraft.split(items, caret: caret, newId: UUID.init)
                    items = next
                    insertAfter = spot
                    picking = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "photo").font(.system(size: 15))
                        Text("사진 넣기").font(.system(size: 15, weight: .semibold))
                        Text("\(photos.slots.count)/\(CommunityRules.photoLimit)")
                            .font(.system(size: 13)).monospacedDigit().foregroundStyle(.secondary)
                    }
                    .foregroundStyle(Color(PinImage.deep))
                    .padding(.horizontal, 14)
                    .frame(minHeight: 40)
                    .background(Capsule().fill(Color(.systemGray6)))
                }
                .buttonStyle(.plain)
                .disabled(photos.remaining <= 0)
                .opacity(photos.remaining <= 0 ? 0.5 : 1)
                .accessibilityIdentifier("community-insert-photo")
                Text("글을 쓰던 자리에 들어가요. 사진은 꾹 눌러 옮길 수 있어요.")
                    .font(.system(size: 12)).foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .disabled(locked)
        .opacity(locked ? 0.6 : 1)
        .photosPicker(isPresented: $picking, selection: $picked, maxSelectionCount: max(1, photos.remaining),
                      matching: .images, photoLibrary: .shared())
        .onChange(of: picked) { _, chosen in
            guard !chosen.isEmpty else { return }
            photos.add(chosen)
            picked = []
        }
        .onChange(of: picking) { _, open in
            // 아무것도 고르지 않고 닫았으면 정해 둔 자리를 버린다.
            if !open, picked.isEmpty, photos.slots.allSatisfy({ slot in items.contains { $0.id == slot.id } }) {
                insertAfter = nil
            }
        }
        .onChange(of: photos.slots.map(\.id), initial: true) { _, ids in sync(ids) }
    }

    /// 사진 칸과 목록을 맞춘다 — 새로 생긴 칸은 정해 둔 자리에, 빠진 칸은 목록에서도 뺀다.
    private func sync(_ slotIds: [UUID]) {
        let present = Set(CommunityDraft.photoOrder(items))
        let added = slotIds.filter { !present.contains($0) }
        var next = CommunityDraft.dropMissingPhotos(items, slotIds: slotIds)
        if !added.isEmpty {
            next = CommunityDraft.insertPhotos(next, after: min(insertAfter ?? next.count - 1, next.count - 1), ids: added)
            insertAfter = nil
        }
        next = CommunityDraft.normalize(next, newId: UUID.init)
        if next != items {
            items = next
        }
        meta = meta.filter { slotIds.contains($0.key) }
    }

    @ViewBuilder private func itemView(_ item: CommunityDraftItem, index: Int) -> some View {
        switch item {
        case let .text(id, text):
            CommunityDraftTextView(
                text: Binding(get: { text }, set: { value in
                    items = items.map { $0.id == id ? .text(id: id, text: value) : $0 }
                }),
                placeholder: index == 0 ? tr("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요") : tr("이어서 쓰기…"),
                onCaret: { caret = (id, $0) }
            )
            .accessibilityIdentifier("community-draft-text-\(index)")
        case let .photo(id):
            if let slot = photos.slots.first(where: { $0.id == id }) {
                CommunityDraftPhotoBlock(
                    slot: slot,
                    cover: CommunityDraft.photoOrder(items).first == id,
                    meta: Binding(get: { meta[id] ?? CommunityPhotoPlacement(afterParagraph: 0) }, set: { meta[id] = $0 }),
                    onRemove: { photos.remove(id) },
                    onRetry: { photos.retry(id) }
                )
                .scaleEffect(dragging == id ? 1.02 : 1)
                .offset(y: dragging == id ? dragOffset : 0)
                .zIndex(dragging == id ? 2 : 0)
                .shadow(color: .black.opacity(dragging == id ? 0.18 : 0), radius: 10, y: 4)
                .gesture(reorderGesture(id))
                .accessibilityIdentifier("community-draft-photo-\(index)")
            }
        }
    }

    /// 0.3초 꾹 누른 뒤 끌면 옮긴다. 놓은 자리의 가운데가 넘은 항목들 뒤(앞)로 간다.
    private func reorderGesture(_ id: UUID) -> some Gesture {
        LongPressGesture(minimumDuration: 0.3)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space)))
            .onChanged { value in
                guard case let .second(true, drag) = value else { return }
                if dragging != id {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    dragging = id
                }
                dragOffset = drag?.translation.height ?? 0
            }
            .onEnded { value in
                defer {
                    withAnimation(.easeOut(duration: 0.16)) {
                        dragging = nil
                        dragOffset = 0
                    }
                }
                guard case let .second(true, drag) = value, let drag,
                      let from = items.firstIndex(where: { $0.id == id }), let frame = frames[id] else { return }
                let center = frame.midY + drag.translation.height
                var to = from
                for (index, item) in items.enumerated() where item.id != id {
                    guard let other = frames[item.id] else { continue }
                    if index > from, center > other.midY {
                        to = index
                    } else if index < from, center < other.midY, to == from || index < to {
                        to = index
                    }
                }
                guard to != from else { return }
                withAnimation(.easeOut(duration: 0.16)) {
                    items = CommunityDraft.normalize(CommunityDraft.moveItem(items, from: from, to: to), newId: UUID.init)
                }
            }
    }
}

private struct ItemFrames: PreferenceKey {
    static let defaultValue: [UUID: CGRect] = [:]
    static func reduce(value: inout [UUID: CGRect], nextValue: () -> [UUID: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

/// 본문 속 사진 하나 — 4:3 크게, 왼쪽 위 「대표」, 오른쪽 위 ✕, 아래 설명. 출처·AI 표시는 접어 둔다.
private struct CommunityDraftPhotoBlock: View {
    let slot: ReviewPhotoDraft.Slot
    let cover: Bool
    @Binding var meta: CommunityPhotoPlacement
    let onRemove: () -> Void
    let onRetry: () -> Void
    @State private var more = false

    var body: some View {
        VStack(spacing: 6) {
            canvas
            TextField(tr("사진 설명 (선택)"), text: $meta.caption, axis: .vertical)
                .font(.system(size: 14)).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .onChange(of: meta.caption) { _, value in
                    if value.utf16.count > CommunityPhotoPlacement.captionLimit {
                        meta.caption = String(value.prefix(CommunityPhotoPlacement.captionLimit))
                    }
                }
                .accessibilityLabel(tr("사진 설명"))
            if more || !meta.credit.isEmpty || meta.aiGenerated {
                VStack(alignment: .leading, spacing: 6) {
                    TextField(tr("출처·이용 조건 (남의 사진이면)"), text: $meta.credit, axis: .vertical)
                        .font(.system(size: 13))
                        .accessibilityLabel(tr("출처"))
                    Toggle(tr("AI 로 바꾼 사진이에요"), isOn: $meta.aiGenerated)
                        .font(.system(size: 13)).foregroundStyle(.secondary)
                        .tint(Color(PinImage.deep))
                }
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
            } else {
                Button {
                    more = true
                } label: {
                    Text("출처·AI 표시 더하기").font(.system(size: 12)).foregroundStyle(.tertiary).frame(minHeight: 32)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var canvas: some View {
        Color(.systemGray5)
            .aspectRatio(4 / 3, contentMode: .fit)
            .overlay {
                if let image = slot.preview {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if let url = slot.remoteUrl.flatMap(URL.init(string:)) {
                    AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
                }
            }
            .overlay { status }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(alignment: .topLeading) {
                if cover {
                    Text("대표").font(.system(size: 11, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Capsule().fill(.black.opacity(0.6)))
                        .padding(8)
                }
            }
            .overlay(alignment: .topTrailing) {
                Button(action: onRemove) {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(Circle().fill(.black.opacity(0.55)))
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("사진 빼기"))
            }
    }

    @ViewBuilder private var status: some View {
        switch slot.state {
        case .waiting, .uploading:
            ZStack {
                Color.black.opacity(0.35)
                ProgressView().tint(.white)
            }
        case let .failed(failure):
            ZStack {
                Color.black.opacity(0.55)
                if PhotoUploadRules.retryable(failure) {
                    Button(action: onRetry) {
                        VStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise").font(.system(size: 20, weight: .semibold))
                            Text("다시 시도").font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundStyle(.white).padding(12)
                    }
                    .buttonStyle(.plain)
                } else {
                    Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 22)).foregroundStyle(.white)
                }
            }
        case .attached, .uploaded:
            EmptyView()
        }
    }
}

/// 글 조각 — 쓰는 만큼 늘어나는 테두리 없는 칸. 커서 자리(UTF-16)를 알린다 — SwiftUI 글 칸은 iOS 17 에서 커서를 주지 않는다.
struct CommunityDraftTextView: View {
    @Binding var text: String
    let placeholder: String
    let onCaret: (Int) -> Void

    var body: some View {
        CaretTextView(text: $text, onCaret: onCaret)
            .overlay(alignment: .topLeading) {
                if text.isEmpty {
                    Text(placeholder).font(.system(size: 17)).foregroundStyle(Color(.placeholderText))
                        .allowsHitTesting(false)
                }
            }
            .accessibilityLabel(placeholder)
    }
}

private struct CaretTextView: UIViewRepresentable {
    @Binding var text: String
    let onCaret: (Int) -> Void

    static let minHeight: CGFloat = 54

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.font = .systemFont(ofSize: 17)
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.delegate = context.coordinator
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.textColor = .label
        view.text = text
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.parent = self
        if view.text != text {
            // 바깥에서 바꾼 글(사진 넣기로 나뉨)을 넣는 동안의 커서 알림은 화면 갱신 중이라 받지 않는다.
            context.coordinator.updating = true
            view.text = text
            context.coordinator.updating = false
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context _: Context) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        guard width > 0, width.isFinite else { return nil }
        let size = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: max(size.height, Self.minHeight))
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: CaretTextView
        var updating = false

        init(parent: CaretTextView) {
            self.parent = parent
        }

        func textViewDidChange(_ view: UITextView) {
            parent.text = view.text
            parent.onCaret(view.selectedRange.location)
        }

        func textViewDidChangeSelection(_ view: UITextView) {
            guard !updating else { return }
            parent.onCaret(view.selectedRange.location)
        }

        func textViewDidBeginEditing(_ view: UITextView) {
            parent.onCaret(view.selectedRange.location)
        }
    }
}
