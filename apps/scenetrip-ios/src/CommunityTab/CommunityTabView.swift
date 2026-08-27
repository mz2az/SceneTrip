import SceneApiClient
import SwiftUI

/// 커뮤니티 — **임시판** (2026-08-28).
///
/// 커뮤니티 기획(후기·사진·댓글)은 아직 없다. 그런데 이미 있는 진짜 공유물이
/// 하나 있다 — **코스 마켓**(여행자들이 올린 코스, 실서버). 임시판은 그것을
/// 피드로 보여 주고, 없는 것(후기·사진)은 「준비 중」으로 정직하게 적는다.
///
/// 담기는 경로여정 탭의 마켓이 맡는다 — 같은 기능을 두 군데 두면 어느 쪽이
/// 진짜인지 흐려진다. 여기는 **구경과 좋아요**까지다.
struct CommunityTabView: View {
    @State private var courses: [MarketCourseSummary] = []
    @State private var loaded = false
    @State private var notice: String?

    private let deviceId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("여행자들이 올린 코스를 구경하는 자리입니다. 후기·사진은 준비 중이에요.")
                        .font(.caption).foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4))
                }

                Section("여행자들의 코스") {
                    if !loaded {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("불러오는 중입니다").font(.caption).foregroundStyle(.secondary)
                        }
                    } else if courses.isEmpty {
                        Text("아직 올라온 코스가 없습니다")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(courses, id: \.id) { course in
                        card(course)
                    }
                }

                Section("준비 중") {
                    HStack(spacing: 12) {
                        Image(systemName: "camera").foregroundStyle(.gray).frame(width: 26)
                        Text("성지 인증샷 · 여행 후기").font(.subheadline)
                        Spacer()
                        Text("준비 중").font(.subheadline).foregroundStyle(.tertiary)
                    }
                    .foregroundStyle(.tertiary)
                }
            }
            .navigationTitle("커뮤니티")
            .navigationBarTitleDisplayMode(.inline)
            .task { await refresh() }
            .refreshable { await refresh() }
            .overlay(alignment: .bottom) {
                if let notice {
                    Text(notice)
                        .font(.caption)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(.thinMaterial))
                        .padding(.bottom, 12)
                        .task {
                            try? await Task.sleep(for: .seconds(2.5))
                            self.notice = nil
                        }
                }
            }
        }
    }

    private func card(_ course: MarketCourseSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(course.title).font(.subheadline.weight(.semibold))
                    Text("\(course.dayCount)일 · \(course.placeCount)곳")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    Task { await toggleLike(course) }
                } label: {
                    HStack(spacing: 3) {
                        Image(systemName: course.liked ? "heart.fill" : "heart")
                            .foregroundStyle(course.liked ? .red : .secondary)
                        Text("\(course.likeCount)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
            if let tags = course.contents, !tags.isEmpty {
                Text(tags.map(\.title).prefix(3).joined(separator: " · "))
                    .font(.caption2).foregroundStyle(Color.accentColor)
            }
            HStack(spacing: 10) {
                Label("\(course.saveCount)명이 담음", systemImage: "square.and.arrow.down")
                    .font(.caption2).foregroundStyle(.tertiary)
                Spacer()
                Text("담기는 경로여정 탭에서")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
    }

    private func refresh() async {
        // 좋아요 순 — 커뮤니티는 「무엇이 사랑받나」를 보는 자리다. 마켓 화면
        // (담김 순)과 정렬을 달리해 같은 목록의 다른 얼굴을 보여 준다.
        if let list = try? await MarketAPI.listMarketCourses(
            xDeviceId: deviceId, sort: .likes, limit: 30
        ) {
            courses = list.items
        }
        loaded = true
    }

    private func toggleLike(_ course: MarketCourseSummary) async {
        do {
            if course.liked {
                try await MarketAPI.unlikeMarketCourse(xDeviceId: deviceId, marketCourseId: course.id)
            } else {
                try await MarketAPI.likeMarketCourse(xDeviceId: deviceId, marketCourseId: course.id)
            }
            await refresh()
        } catch {
            // 비회원은 서버가 401 로 막는다(계약 SignInRequired). 왜 안 되는지 말한다.
            notice = "좋아요는 로그인이 생기면 쓸 수 있어요"
        }
    }
}
