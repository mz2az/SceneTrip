import PhotosUI
import SceneApiClient
import SwiftUI

/// 여행후기 쓰기 — **블로그 글쓰기처럼** (MZ2AZ-351).
///
/// 위에서부터 사진 → 제목 → 본문 → 다녀온 코스. 폼의 칸을 채우는 느낌이 아니라 **글을 쓰는
/// 종이**여야 한다 — 그래서 테두리 없는 큰 제목과 넓은 본문이 화면의 대부분이다.
///
/// 코스를 붙이면 일차·장소까지 글에 사본으로 담긴다. 읽는 사람이 그 코스를 보고 내 코스로
/// 담을 수 있다(`CommunityPostView`).
struct CommunityComposeView: View {
    @ObservedObject private var store = CommunityStore.shared

    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var story = ""
    @State private var picked: [PhotosPickerItem] = []
    @State private var photos: [UIImage] = []
    @State private var course: PostCourse?
    @State private var pickingCourse = false

    private static let photoLimit = 8

    private var canPost: Bool {
        !title.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    photoStrip

                    TextField("제목", text: $title, axis: .vertical)
                        .font(.title2.weight(.bold))
                        .lineLimit(1 ... 3)
                        .padding(.horizontal, 20)

                    Divider().padding(.horizontal, 20)

                    TextField("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요", text: $story, axis: .vertical)
                        .font(.body)
                        .lineSpacing(5)
                        .lineLimit(8...)
                        .padding(.horizontal, 20)

                    courseCard
                        .padding(.horizontal, 16)
                        .padding(.top, 6)
                }
                .padding(.vertical, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("여행후기 쓰기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("올리기") {
                        store.add(
                            title: title.trimmingCharacters(in: .whitespaces),
                            body: story.trimmingCharacters(in: .whitespacesAndNewlines),
                            photos: photos, course: course
                        )
                        dismiss()
                    }
                    .disabled(!canPost)
                }
            }
            .onChange(of: picked) { _, items in
                Task { await load(items) }
            }
            .sheet(isPresented: $pickingCourse) {
                CoursePickSheet { course = $0 }
                    .presentationDetents([.medium, .large])
            }
        }
    }

    // MARK: 사진

    /// 가로로 넘기는 사진 줄. 첫 칸은 늘 「사진 추가」다 — 첫 장이 대표 사진이 된다.
    private var photoStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                PhotosPicker(
                    selection: $picked, maxSelectionCount: Self.photoLimit, matching: .images
                ) {
                    VStack(spacing: 6) {
                        Image(systemName: "photo.badge.plus").font(.system(size: 22))
                        Text("\(photos.count)/\(Self.photoLimit)").font(.caption2)
                    }
                    .foregroundStyle(.secondary)
                    .frame(width: 92, height: 92)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemGray6)))
                }
                .accessibilityLabel("사진 추가")

                ForEach(Array(photos.enumerated()), id: \.offset) { index, photo in
                    Image(uiImage: photo)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 92, height: 92)
                        .clipShape(.rect(cornerRadius: 14))
                        .overlay(alignment: .bottomLeading) {
                            if index == 0 {
                                Text("대표")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Capsule().fill(.black.opacity(0.55)))
                                    .padding(6)
                            }
                        }
                        .overlay(alignment: .topTrailing) {
                            Button {
                                remove(at: index)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 20))
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.55))
                                    .padding(4)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("사진 빼기")
                        }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func load(_ items: [PhotosPickerItem]) async {
        var loaded: [UIImage] = []
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                loaded.append(image)
            }
        }
        photos = loaded
    }

    private func remove(at index: Int) {
        guard photos.indices.contains(index) else { return }
        photos.remove(at: index)
        if picked.indices.contains(index) {
            picked.remove(at: index)
        }
    }

    // MARK: 코스

    /// 다녀온 코스 붙이기. 붙이면 일차·장소 수가 카드에 보인다.
    @ViewBuilder private var courseCard: some View {
        if let course {
            HStack(spacing: 12) {
                PostCourseBadge()
                VStack(alignment: .leading, spacing: 2) {
                    Text(course.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(String(format: tr("%d일 · %d곳"), course.days.count, course.placeCount))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    self.course = nil
                } label: {
                    Image(systemName: "xmark").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("코스 떼기")
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemGray6)))
        } else {
            Button {
                pickingCourse = true
            } label: {
                HStack(spacing: 12) {
                    PostCourseBadge()
                    VStack(alignment: .leading, spacing: 2) {
                        Text("다녀온 코스 붙이기").font(.subheadline.weight(.semibold))
                        Text("읽는 사람이 이 코스를 보고 그대로 담아 갈 수 있어요")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemGray6)))
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }
}

/// 코스 표시 — 피노 색 네모 안의 경로 그림. 글쓰기·글 보기·목록이 같이 쓴다.
struct PostCourseBadge: View {
    var size: CGFloat = 34

    var body: some View {
        Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
            .font(.system(size: size * 0.47))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.24).fill(Color(PinImage.deep)))
    }
}

/// 내 코스 고르기. 고르면 **그 코스의 속(일차·장소)을 받아** 글에 붙일 사본을 만든다.
private struct CoursePickSheet: View {
    let onPick: (PostCourse) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var courses: [CourseSummary] = []
    @State private var loading = true
    @State private var fetching: Int64?

    private let installId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if courses.isEmpty {
                    ContentUnavailableView(
                        "붙일 코스가 없습니다",
                        systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                        description: Text("홈의 코스 만들기에서 먼저 코스를 만들어 보세요")
                    )
                } else {
                    List(courses, id: \.id) { item in
                        Button {
                            Task { await pick(item) }
                        } label: {
                            HStack(spacing: 12) {
                                PostCourseBadge(size: 30)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title).font(.subheadline.weight(.medium)).lineLimit(1)
                                    Text(String(format: tr("%d일 · %d곳"), item.dayCount, item.placeCount))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if fetching == item.id {
                                    ProgressView()
                                }
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .disabled(fetching != nil)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("내 코스")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
            .task {
                courses = await (try? CoursesAPI.listCourses(xInstallId: installId).items) ?? []
                loading = false
            }
        }
    }

    private func pick(_ item: CourseSummary) async {
        fetching = item.id
        defer { fetching = nil }
        guard let detail = try? await CoursesAPI.getCourse(xInstallId: installId, courseId: item.id) else {
            return
        }
        onPick(PostCourse(from: RouteBridge.course(from: detail)))
        dismiss()
    }
}
