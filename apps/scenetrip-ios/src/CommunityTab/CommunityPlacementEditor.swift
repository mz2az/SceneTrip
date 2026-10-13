import SceneApiClient
import SwiftUI

struct CommunityPlacementEditor: View {
    let story: String
    let slots: [ReviewPhotoDraft.Slot]
    @Binding var placements: [UUID: CommunityPhotoPlacement]
    var locked = false

    private var paragraphs: [String] {
        CommunityArticle.paragraphs(story)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("사진 배치").font(.headline)
            Text("빈 줄로 문단을 나누고 사진을 넣을 위치를 골라 주세요")
                .font(.footnote).foregroundStyle(.secondary)
            ForEach(Array(slots.enumerated()), id: \.element.id) { index, slot in
                controls(slot: slot, index: index)
            }
            if paragraphs.count > CommunityArticle.paragraphLimit {
                Text("본문은 64개 문단까지 쓸 수 있어요").font(.footnote).foregroundStyle(.red)
            }
        }.disabled(locked)
    }

    private func controls(slot: ReviewPhotoDraft.Slot, index: Int) -> some View {
        let placement = binding(slot.id, index: index)
        return VStack(alignment: .leading, spacing: 10) {
            Text(String(format: tr("사진 %d"), index + 1)).font(.subheadline.weight(.semibold))
            Picker(tr("사진 위치"), selection: placement.afterParagraph) {
                Text("본문 시작").tag(0)
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { offset, text in
                    Text(String(format: tr("%d번째 문단 뒤"), offset + 1) + " · " + text.prefix(24)).tag(offset + 1)
                }
            }.pickerStyle(.menu)
            TextField(tr("사진 설명"), text: placement.caption, axis: .vertical)
                .font(.subheadline)
            Text(String(format: tr("사진 설명 %d/%d"), placement.wrappedValue.captionLength, CommunityPhotoPlacement.captionLimit))
                .font(.caption2).foregroundStyle(placement.wrappedValue.captionLength > CommunityPhotoPlacement.captionLimit ? Color.red : .secondary)
            TextField(tr("사진 출처 또는 이용 조건"), text: placement.credit, axis: .vertical)
                .font(.footnote)
            Text(String(format: tr("출처 %d/%d"), placement.wrappedValue.creditLength, CommunityPhotoPlacement.creditLimit))
                .font(.caption2).foregroundStyle(placement.wrappedValue.creditLength > CommunityPhotoPlacement.creditLimit ? Color.red : .secondary)
            Toggle(tr("AI 변환 사진"), isOn: placement.aiGenerated).font(.footnote)
        }.padding(14).background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private func binding(_ id: UUID, index: Int) -> Binding<CommunityPhotoPlacement> {
        Binding(get: {
            var placement = placements[id] ?? CommunityPhotoPlacement(afterParagraph: min(index + 1, paragraphs.count))
            placement.afterParagraph = min(max(0, placement.afterParagraph), paragraphs.count)
            return placement
        }, set: { placements[id] = $0 })
    }
}

struct CommunityArticlePreview: View {
    let title: String
    let blocks: [TripPostBlock]
    let slots: [ReviewPhotoDraft.Slot]
    let course: PostCourse?
    @Environment(\.dismiss) private var dismiss

    private var sources: [CommunityPhotoSource] {
        slots.map(CommunityPhotoSource.init(slot:))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let cover = sources.first {
                        CommunityArticlePhoto(source: cover,
                                              block: blocks.first { $0.kind == .photo && $0.photoIndex == 0 }
                                                  ?? TripPostBlock(kind: .photo, photoIndex: 0), representative: true)
                    }
                    Text(verbatim: title).font(.title2.weight(.bold))
                    CommunityArticleView(blocks: blocks, photos: sources)
                    if let course {
                        Label(course.title, systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                            .font(.headline).padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
                    }
                }.padding(20)
            }.navigationTitle("미리보기").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("닫기") { dismiss() } } }
        }
    }
}
