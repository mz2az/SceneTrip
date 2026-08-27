import SceneApiClient
import SwiftUI

/// 마이페이지 — **임시판** (2026-08-28).
///
/// 로그인이 아직 없다(비회원 승격은 MZ2AZ-256). 그래서 이 화면은 「내 계정」이
/// 아니라 **「이 설치본에 쌓인 것」**을 보여 준다 — 찜한 작품, 내 코스, 사용법.
/// 로그인이 서면 머리만 계정으로 갈아 끼우고 아래 목록은 그대로 간다.
///
/// 지어낸 숫자는 없다 — 찜은 기기 저장소(`LikeStore`), 코스 수는 서버에서 센다.
/// 아직 못 하는 것(로그인·알림·언어)은 흐리게 두고 「준비 중」이라고 적는다 —
/// 눌리는데 아무 일도 없는 것이 제일 나쁘다.
struct ProfileTabView: View {
    @StateObject private var likes = LikeStore()

    @State private var courseCount: Int?
    @State private var runningTitle: String?
    @State private var replaying = false

    private let deviceId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            List {
                Section {
                    header
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section("내 여행") {
                    row(symbol: "point.topleft.down.to.point.bottomright.curvepath",
                        tint: Color(PinImage.deep), title: "내 코스",
                        value: courseCount.map { "\($0)개" } ?? "…")
                    if let runningTitle {
                        row(symbol: "figure.walk", tint: .green,
                            title: "여행 중", value: runningTitle)
                    }
                    row(symbol: "heart.fill", tint: .red, title: "찜한 작품",
                        value: "\(likes.contentIds.count)개")
                }

                Section("도움") {
                    Button {
                        replaying = true
                    } label: {
                        row(symbol: "questionmark.circle", tint: .blue,
                            title: "사용법 다시 보기", value: "")
                    }
                    .buttonStyle(.plain)
                }

                Section("준비 중") {
                    // 자리를 미리 보여 준다 — 없는 척하다 갑자기 생기는 것보다
                    // 「여기 온다」가 보이는 쪽이 낫다.
                    row(symbol: "person.crop.circle.badge.plus", tint: .gray,
                        title: "로그인 · 계정", value: "준비 중")
                        .foregroundStyle(.tertiary)
                    row(symbol: "globe", tint: .gray,
                        title: "언어 (English · 日本語)", value: "준비 중")
                        .foregroundStyle(.tertiary)
                    row(symbol: "bell", tint: .gray, title: "알림", value: "준비 중")
                        .foregroundStyle(.tertiary)
                }

                Section {
                    Text("설치 식별자 \(deviceId.uuidString.prefix(8))…")
                        .font(.caption2).foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("마이페이지")
            .navigationBarTitleDisplayMode(.inline)
            .task { await load() }
            .refreshable { await load() }
            .fullScreenCover(isPresented: $replaying) {
                OnboardingView { replaying = false }
            }
        }
    }

    /// 피노와 비회원 안내. 로그인이 서면 이 자리가 계정 카드가 된다.
    private var header: some View {
        VStack(spacing: 8) {
            PinoMascot(width: 96)
            Text("비회원으로 여행 중")
                .font(.headline)
            Text("로그인이 생기면 코스와 찜을 계정으로 옮겨 드릴게요")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private func row(
        symbol: String, tint: Color, title: String, value: String
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15))
                .foregroundStyle(tint)
                .frame(width: 26)
            Text(title).font(.subheadline)
            Spacer()
            Text(value).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func load() async {
        // 실패하면 …로 남는다 — 마이페이지가 서버 때문에 죽으면 안 된다.
        guard let list = try? await CoursesAPI.listCourses(xDeviceId: deviceId) else { return }
        courseCount = list.items.count
        runningTitle = list.items.first { $0.status == .active }?.title
    }
}
