import SceneApiClient
import SwiftUI

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
