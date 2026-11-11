import PhotosUI
import SceneApiClient
import SwiftUI

/// 글쓰기 — **블로그 글쓰기처럼** (MZ2AZ-351, 블로그형 MZ2AZ-547 K10·K11).
///
/// 커뮤니티 탭에서 오른쪽으로 밀려 들어오는 새 화면이다. 머리줄 아래 게시판 칩(처음 값은 보던 게시판)으로 올릴 게시판을 고른다
/// (`TripPostCreate.board`). 제목 → 본문(글 조각과 사진을 쓴 순서대로, `CommunityArticleEditor`) → 다녀온 코스.
/// 폼의 칸을 채우는 느낌이 아니라 **글을 쓰는 종이**여야 한다 — 그래서 테두리 없는 큰 제목과 넓은 본문이 화면의 대부분이다.
///
/// 로그인한 사람만 연다 — 커뮤니티 「글쓰기」 는 비로그인이면 로그인 창만 띄운다(계획 §11).
/// 쓰는 도중 로그아웃·세션 만료에 대비해 사진 넣기·올리기도 로그인을 다시 본다.
/// 코스를 붙이면 일차·장소까지 글에 사본으로 담긴다. 읽는 사람이 그 코스를 보고 내 코스로 담을 수 있다(`CommunityPostView`).
struct CommunityComposeView: View {
    @ObservedObject private var store = CommunityStore.shared
    @ObservedObject private var auth = AuthStore.shared
    @StateObject private var photos = ReviewPhotoDraft(purpose: .post, limit: CommunityRules.photoLimit)
    @StateObject private var submission = CommunitySubmission()

    @Environment(\.dismiss) private var dismiss

    @State private var board: TripPostBoard
    @State private var title = ""
    /// 글 조각과 사진을 쓴 순서대로 — 본문과 사진 위치는 여기서 계산한다(`CommunityDraft`).
    @State private var items: [CommunityDraftItem] = [.text(id: UUID(), text: "")]
    /// 사진마다 설명·출처·AI 표시. 위치(`afterParagraph`)는 목록이 정한다.
    @State private var meta: [UUID: CommunityPhotoPlacement] = [:]
    @State private var course: PostCourse?
    @State private var pickingCourse = false
    @State private var checkingPosts = false
    @State private var confirmingDiscard = false
    @State private var composingEpoch = AuthStore.shared.epoch
    @State private var previewing = false

    init(board: TripPostBoard = .review) {
        _board = State(initialValue: board)
    }

    private var story: String {
        CommunityDraft.story(items)
    }

    private var placements: [UUID: CommunityPhotoPlacement] {
        CommunityDraft.placements(items, meta: meta)
    }

    private var locked: Bool {
        submission.working || submission.uncertain
    }

    private var hasDraft: Bool {
        !title.isEmpty || !story.isEmpty || !photos.slots.isEmpty || course != nil
    }

    /// 내용이 올릴 만한가 — 로그인은 따로 본다(비로그인이면 「올리기」 가 로그인으로 안내한다).
    private var contentReady: Bool {
        !submission.working && !submission.uncertain && !submission.completed && photos.canSave
            && composingEpoch == auth.epoch
            && CommunityRules.canPost(title: title, body: story, photoCount: photos.slots.count)
            && CommunityArticle.canCompose(story) && CommunityArticle.plainBody(story).utf16.count <= CommunityRules.bodyLimit
            && placements.values.allSatisfy(\.metadataValid)
    }

    var body: some View {
        VStack(spacing: 0) {
            boardChip
            ScrollView {
                form
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .navigationTitle(tr("글쓰기"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        // 쓰던 글을 밀어서 잃지 않게 — 뒤로는 「취소」 하나로만(쓰던 것이 있으면 버릴지 묻는다).
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("취소") {
                    if hasDraft {
                        confirmingDiscard = true
                    } else {
                        dismiss()
                    }
                }.disabled(submission.working)
            }
            ToolbarItem(placement: .confirmationAction) {
                HStack {
                    Button("미리보기") { previewing = true }
                        .disabled(!CommunityArticle.canCompose(story))
                    Button("올리기") { Task { await submit() } }
                        .fontWeight(.semibold)
                        .disabled(!contentReady)
                        .accessibilityIdentifier("community-submit")
                }
            }
        }
        .sheet(isPresented: $pickingCourse) {
            CoursePickSheet { course = $0 }
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $checkingPosts) { MyPostsSheet() }
        .sheet(isPresented: $previewing) {
            CommunityArticlePreview(title: title, blocks: articleBlocks, slots: photos.slots, course: course)
        }
        .onDisappear { photos.cancel() }
        .onAppear { photos.resume() }
        .onChange(of: auth.epoch) { _, _ in photos.cancel() }
        // 사진 칸 순서를 본문에 놓인 순서와 맞춘다 — 첫 사진이 목록의 대표 사진이고, bodyBlocks 의 photoIndex 가 이 순서를 가리킨다.
        .onChange(of: CommunityDraft.photoOrder(items)) { _, order in
            if !order.isEmpty {
                photos.reorder(order)
            }
        }
        .alert(tr("쓰던 글을 버릴까요?"), isPresented: $confirmingDiscard) {
            Button("버리기", role: .destructive) { dismiss() }
            Button("계속 쓰기", role: .cancel) {}
        }
    }

    /// 머리줄 아래 게시판 고르기 — 보라 옅은 칩 하나(「여행후기 ⌄」). 누르면 바로 아래로 목록이 펼쳐진다(고른 것에 ✓).
    private var boardChip: some View {
        HStack {
            Menu {
                Picker(tr("게시판"), selection: $board) {
                    ForEach(CommunityFeedRules.boards, id: \.self) { item in
                        Text(tr(CommunityFeedRules.boardLabelKey(item))).tag(item)
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(tr(CommunityFeedRules.boardLabelKey(board))).font(.system(size: 13, weight: .bold))
                    Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold))
                }
                .foregroundStyle(Color(PinImage.deep))
                .padding(.leading, 11).padding(.trailing, 9)
                .frame(height: 28)
                .background(Capsule().fill(Color(PinImage.deep).opacity(0.1)))
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .disabled(locked)
            .accessibilityLabel(String(format: tr("게시판: %@"), tr(CommunityFeedRules.boardLabelKey(board))))
            .accessibilityIdentifier("community-board-picker")
            Spacer()
        }
        .padding(.horizontal, 20).padding(.top, 6)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 18) {
            status
            VStack(alignment: .leading, spacing: 18) {
                TextField("제목", text: $title, axis: .vertical)
                    .font(.title2.weight(.bold)).lineLimit(1 ... 3)
                    .onChange(of: title) { _, value in
                        // 제목은 한 줄 글이다 — 줄바꿈은 빈칸으로.
                        if value.contains("\n") {
                            title = value.replacingOccurrences(of: "\n", with: " ")
                        }
                    }
                Divider()
                CommunityArticleEditor(
                    items: $items, meta: $meta, photos: photos, locked: locked,
                    canPick: {
                        guard auth.signedIn else {
                            auth.promptSignIn()
                            return false
                        }
                        return true
                    }
                )
                if CommunityArticle.paragraphs(story).count > CommunityArticle.paragraphLimit {
                    Text("본문은 64개 문단까지 쓸 수 있어요").font(.footnote).foregroundStyle(.red)
                }
            }
            .padding(.horizontal, 20)
            .disabled(locked)
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

    // MARK: 사진

    private var articleBlocks: [TripPostBlock] {
        CommunityArticle.blocks(body: story, photoIds: photos.slots.map(\.id), placements: placements)
    }

    private func submit() async {
        guard auth.signedIn else {
            auth.promptSignIn()
            return
        }
        guard contentReady else { return }
        let input = TripPostCreate(title: CommunityRules.normalized(title), body: CommunityArticle.plainBody(story),
                                   photoKeys: photos.keys, courseId: course?.serverId, bodyBlocks: articleBlocks,
                                   board: board)
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

    /// 매번 읽는다 — 설치 식별자는 바뀔 수 있다(MZ2AZ-521).
    private var installId: UUID {
        InstallIdentity.current
    }

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
