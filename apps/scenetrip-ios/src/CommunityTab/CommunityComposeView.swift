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
    @ObservedObject private var auth = AuthStore.shared
    @StateObject private var photos = ReviewPhotoDraft(purpose: .post, limit: CommunityRules.photoLimit)
    @StateObject private var submission = CommunitySubmission()

    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var story = ""
    @State private var course: PostCourse?
    @State private var pickingCourse = false
    @State private var checkingPosts = false
    @State private var confirmingDiscard = false
    @State private var composingEpoch = AuthStore.shared.epoch

    private var canPost: Bool {
        !submission.working && !submission.uncertain && !submission.completed && photos.canSave && auth.signedIn
            && composingEpoch == auth.epoch
            && CommunityRules.canPost(title: title, body: story, photoCount: photos.slots.count)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                form
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("여행후기 쓰기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") {
                        if !title.isEmpty || !story.isEmpty || !photos.slots.isEmpty || course != nil {
                            confirmingDiscard = true
                        } else {
                            dismiss()
                        }
                    }.disabled(submission.working)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("올리기") {
                        Task { await submit() }
                    }
                    .disabled(!canPost)
                }
            }
            .sheet(isPresented: $pickingCourse) {
                CoursePickSheet { course = $0 }
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $checkingPosts) { MyPostsSheet() }
            .signInSheet()
            .interactiveDismissDisabled(submission.working || !title.isEmpty || !story.isEmpty || !photos.slots.isEmpty)
            .onDisappear { photos.cancel() }
            .onAppear { photos.resume() }
            .onChange(of: auth.epoch) { _, _ in photos.cancel() }
            .alert(tr("쓰던 후기를 버릴까요?"), isPresented: $confirmingDiscard) {
                Button("버리기", role: .destructive) { dismiss() }
                Button("계속 쓰기", role: .cancel) {}
            }
        }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 18) {
            status
            ReviewPhotoStrip(draft: photos, locked: submission.working || submission.uncertain)
                .padding(.horizontal, 20)
            editor
            courseCard.padding(.horizontal, 16).padding(.top, 6)
            if let count = course?.excludedPins, count > 0 {
                Text(String(format: tr("직접 찍은 핀 %d곳은 공유되지 않습니다"), count))
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 20)
            }
            Text(String(format: tr("제목 %d/100 · 본문 %d/5000"), title.count, story.count))
                .font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 20)
        }.padding(.vertical, 16)
    }

    @ViewBuilder private var status: some View {
        if let message = submission.message {
            Text(message).font(.footnote).foregroundStyle(.red).padding(.horizontal, 20)
        }
        if submission.uncertain {
            Button("게시 여부 확인") { Task { await check() } }
                .disabled(submission.working).padding(.horizontal, 20)
            Button("내 글 목록 보기") { checkingPosts = true }
                .disabled(submission.working).padding(.horizontal, 20)
        }
        if !auth.signedIn {
            Button("로그인한 뒤에 쓸 수 있어요") { auth.promptSignIn() }.padding(.horizontal, 20)
        } else if composingEpoch != auth.epoch {
            Text("계정이 바뀌었어요. 이 초안을 닫고 새로 작성해 주세요")
                .font(.footnote).foregroundStyle(.red).padding(.horizontal, 20)
        }
    }

    private var editor: some View {
        VStack(alignment: .leading, spacing: 18) {
            TextField("제목", text: $title, axis: .vertical)
                .font(.title2.weight(.bold)).lineLimit(1 ... 3)
            Divider()
            TextField("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요", text: $story, axis: .vertical)
                .font(.body).lineSpacing(5).lineLimit(8...)
        }.padding(.horizontal, 20).disabled(submission.working || submission.uncertain)
    }

    // MARK: 사진

    /// 가로로 넘기는 사진 줄. 첫 칸은 늘 「사진 추가」다 — 첫 장이 대표 사진이 된다.
    private func submit() async {
        guard canPost else { return }
        let input = TripPostCreate(title: CommunityRules.normalized(title), body: CommunityRules.normalized(story),
                                   photoKeys: photos.keys, courseId: course?.serverId)
        if let saved = await submission.submit(input, expectedCourse: course) {
            finish(saved)
        } else if submission.photoInvalid {
            photos.rejectUploaded()
        }
    }

    private func check() async {
        if let saved = await submission.check() {
            finish(saved)
        }
    }

    private func finish(_ saved: TripPostDetail) {
        store.accept(saved)
        AppAnalytics.log(.postReview(photoCount: saved.photoUrls.count, hasCourse: saved.course != nil))
        dismiss()
    }

    // MARK: 코스

    /// 다녀온 코스 붙이기. 붙이면 일차·장소 수가 카드에 보인다.
    @ViewBuilder private var courseCard: some View {
        if let course {
            HStack(spacing: 12) {
                PostCourseBadge()
                VStack(alignment: .leading, spacing: 2) {
                    Text(course.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(String(format: tr("%d일 · %d곳"), course.days.count, course.placeCount - (course.excludedPins ?? 0)))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    self.course = nil
                } label: {
                    Image(systemName: "xmark").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("코스 떼기")
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemGray6)))
            .disabled(submission.working || submission.uncertain)
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
            .disabled(submission.working || submission.uncertain)
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
    @State private var message: String?

    private let installId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let message {
                    VStack(spacing: 12) {
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                        Button("다시 시도") { Task { await reload() } }
                    }.padding(20)
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
            .task { await reload() }
        }
    }

    private func reload() async {
        loading = true
        message = nil
        defer { loading = false }
        do { courses = try await CoursesAPI.listCourses(xInstallId: installId).items }
        catch { message = CommunityRules.failureText(error) }
    }

    private func pick(_ item: CourseSummary) async {
        fetching = item.id
        defer { fetching = nil }
        do {
            let detail = try await CoursesAPI.getCourse(xInstallId: installId, courseId: item.id)
            onPick(PostCourse(from: RouteBridge.course(from: detail)))
            dismiss()
        } catch {
            message = CommunityRules.failureText(error)
        }
    }
}
