import SwiftUI

/// 여행후기 읽기 (MZ2AZ-351) — 블로그 글처럼 **사진이 먼저, 그 아래 글, 끝에 다녀온 코스.**
///
/// 붙은 코스는 이름만이 아니라 **일차별 장소까지** 보인다. 「내 코스로 담기」를 누르면 같은
/// 코스가 내 것으로 하나 생긴다 — 후기를 읽고 「나도 이대로 가야지」가 한 번에 되어야 한다.
struct CommunityPostView: View {
    let post: CommunityPost

    @Environment(\.dismiss) private var dismiss

    @State private var saving = false
    /// 담은 코스의 서버 id. 담고 나면 단추가 「코스 보기」로 바뀐다.
    @State private var savedCourseId: Int64?
    @State private var saveFailed = false
    /// 긴 코스는 일차마다 몇 곳만 보이고 접어 둔다 — 26곳짜리를 다 펼치면 담기 단추가 화면 밖으로 밀린다.
    @State private var expanded = false

    private static let previewStops = 4

    private func shown(_ stops: [PostCourse.Stop]) -> [PostCourse.Stop] {
        expanded ? stops : Array(stops.prefix(Self.previewStops))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let photos = post.photos, !photos.isEmpty {
                    PostPhotoPager(names: photos)
                }

                VStack(alignment: .leading, spacing: 16) {
                    Text(post.title)
                        .font(.title2.weight(.bold))
                        .frame(maxWidth: .infinity, alignment: .leading)

                    authorRow

                    if !post.body.isEmpty {
                        Text(post.body)
                            .font(.body)
                            .lineSpacing(6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    if let course = post.course {
                        courseCard(course)
                    } else if let courseTitle = post.courseTitle {
                        // 옛 글 — 코스 이름만 붙어 있다.
                        HStack(spacing: 10) {
                            PostCourseBadge(size: 30)
                            Text(courseTitle).font(.subheadline.weight(.semibold))
                            Spacer()
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.systemGray6)))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 18)
            }
        }
        .overlay(alignment: .topTrailing) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    // 밝은 사진·흰 본문 위에서도 보이게 진하게 깐다.
                    .background(Circle().fill(.black.opacity(0.62)))
                    .overlay(Circle().strokeBorder(.white.opacity(0.5), lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(14)
            .accessibilityLabel("닫기")
        }
        .alert("코스를 담지 못했어요. 잠시 뒤 다시 해 주세요", isPresented: $saveFailed) {
            Button("확인", role: .cancel) {}
        }
    }

    private var authorRow: some View {
        HStack(spacing: 10) {
            Text(String((post.author ?? tr("나")).prefix(1)))
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Circle().fill(Color(PinImage.deep)))
            VStack(alignment: .leading, spacing: 1) {
                Text(post.author ?? tr("나")).font(.subheadline.weight(.medium))
                Text(post.createdAt.formatted(
                    .dateTime.year().month().day().hour().minute().locale(AppLanguage.currentLocale)
                ))
                .font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer()
        }
    }

    // MARK: 붙은 코스

    private func courseCard(_ course: PostCourse) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                PostCourseBadge()
                VStack(alignment: .leading, spacing: 2) {
                    Text("이 후기의 코스").font(.caption2).foregroundStyle(.secondary)
                    Text(course.title).font(.headline).lineLimit(2)
                    Text(String(format: tr("%d일 · %d곳"), course.days.count, course.placeCount))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }

            ForEach(Array(course.days.enumerated()), id: \.offset) { dayIndex, stops in
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(format: tr("%d일차"), dayIndex + 1))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color(PinImage.deep))
                    ForEach(Array(shown(stops).enumerated()), id: \.offset) { index, stop in
                        HStack(alignment: .top, spacing: 10) {
                            Text("\(index + 1)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .frame(width: 20, height: 20)
                                .background(Circle().fill(Color(PinImage.light)))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(stop.name).font(.subheadline)
                                if let address = stop.address, !address.isEmpty {
                                    Text(address).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    if !expanded, stops.count > Self.previewStops {
                        Text(String(format: tr("외 %d곳"), stops.count - Self.previewStops))
                            .font(.caption).foregroundStyle(.secondary)
                            .padding(.leading, 30)
                    }
                }
            }
            if !expanded, course.days.contains(where: { $0.count > Self.previewStops }) {
                Button(tr("장소 모두 보기")) { expanded = true }
                    .font(.caption.weight(.semibold))
            }

            // 내 글이면 담기가 없다 — 이미 내 코스다. 눌리면 같은 코스가 하나 더 생길 뿐이다.
            if !post.isMine {
                saveButton(course)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color(.systemGray6)))
    }

    @ViewBuilder private func saveButton(_ course: PostCourse) -> some View {
        if let savedCourseId {
            Button {
                dismiss()
                TabRouter.shared.openCourse(savedCourseId)
            } label: {
                Label("담았어요 · 코스 보기", systemImage: "checkmark")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .foregroundStyle(Color(PinImage.deep))
                    .background(Capsule().strokeBorder(Color(PinImage.deep), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        } else {
            Button {
                Task { await save(course) }
            } label: {
                HStack(spacing: 8) {
                    if saving {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "square.and.arrow.down")
                    }
                    Text("내 코스로 담기")
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .background(Capsule().fill(Color(PinImage.deep)))
            }
            .buttonStyle(.plain)
            .disabled(saving)
        }
    }

    /// 사본을 내 코스로 저장한다. 저장소를 새로 하나 만들어 쓴다 — 저장만 하고 버린다.
    /// 코스 화면은 열릴 때 서버에서 다시 읽으므로 담은 코스가 거기에 보인다.
    private func save(_ course: PostCourse) async {
        saving = true
        defer { saving = false }
        if let saved = await RouteStore().save(course.asNewCourse(), origin: "review"), let id = saved.serverId {
            savedCourseId = id
        } else {
            saveFailed = true
        }
    }
}

/// 사진 넘겨 보기 — 화면 폭을 꽉 채우고 옆으로 넘긴다.
struct PostPhotoPager: View {
    let names: [String]

    var body: some View {
        TabView {
            ForEach(names, id: \.self) { name in
                if let photo = CommunityStore.photo(name) {
                    Image(uiImage: photo)
                        .resizable()
                        .scaledToFill()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                } else {
                    Color(.systemGray5)
                        .overlay(Image(systemName: "photo").foregroundStyle(.secondary))
                }
            }
        }
        .tabViewStyle(.page(indexDisplayMode: names.count > 1 ? .always : .never))
        .frame(height: 300)
        .background(Color(.systemGray5))
    }
}
