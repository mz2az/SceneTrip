import SceneApiClient
import SwiftUI

/// 글쓰기 (2026-08-28).
///
/// 말머리를 고르고, 제목·본문을 적고, 내 코스를 붙인다. **사진은 아직 못 붙인다** —
/// 올릴 서버가 없는데 붙이는 시늉만 하면 글과 함께 사라진다. 자리는 흐리게 두고
/// 이유를 적는다.
struct CommunityComposeView: View {
    var onSubmit: (CommunityPost.Board, String, String, String?) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var board: CommunityPost.Board = .course
    @State private var title = ""
    @State private var story = ""
    @State private var courseTitle: String?
    @State private var myCourses: [CourseSummary] = []

    private let deviceId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            Form {
                Section("말머리") {
                    Picker("말머리", selection: $board) {
                        ForEach(CommunityPost.Board.allCases) { item in
                            Text(item.rawValue).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("글") {
                    TextField("제목", text: $title)
                    TextField("여행 이야기를 들려주세요", text: $story, axis: .vertical)
                        .lineLimit(5 ... 10)
                }

                Section("내 코스 붙이기") {
                    if myCourses.isEmpty {
                        Text("붙일 코스가 없습니다")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Picker("코스", selection: $courseTitle) {
                            Text("안 붙임").tag(String?.none)
                            ForEach(myCourses, id: \.id) { course in
                                Text(course.title).tag(String?.some(course.title))
                            }
                        }
                    }
                }

                Section {
                    Label("사진은 게시판 서버가 열리면 붙일 수 있어요", systemImage: "camera")
                        .font(.caption).foregroundStyle(.tertiary)
                }
            }
            .navigationTitle("글쓰기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("올리기") {
                        onSubmit(
                            board,
                            title.trimmingCharacters(in: .whitespaces),
                            story.trimmingCharacters(in: .whitespaces),
                            courseTitle
                        )
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .task {
                if let list = try? await CoursesAPI.listCourses(xDeviceId: deviceId) {
                    myCourses = list.items
                }
            }
        }
    }
}
