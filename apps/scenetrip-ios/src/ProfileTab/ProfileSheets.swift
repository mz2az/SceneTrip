import SceneApiClient
import SwiftUI

/// 마이페이지의 팝업 둘 — 내 코스, 찜한 작품 (2026-08-28).
///
/// 여기는 **보는 자리**다. 코스를 고치는 것은 경로여정 탭, 찜을 푸는 것은 작품검색
/// 탭이 맡는다 — 같은 편집을 두 군데 두면 어느 쪽이 진짜인지 흐려진다.
struct MyCoursesSheet: View {
    let courses: [CourseSummary]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            sheetHeader("내 코스") { dismiss() }
            if courses.isEmpty {
                ContentUnavailableView(
                    "아직 코스가 없습니다",
                    systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                    description: Text("경로여정 탭에서 첫 코스를 만들어 보세요")
                )
            } else {
                List(courses, id: \.id) { course in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(course.title).font(.subheadline.weight(.medium))
                            Text(
                                "\(course.dayCount)일"
                                    + (course.startDate.map {
                                        " · " + $0.formatted(date: .abbreviated, time: .omitted)
                                    } ?? "")
                            )
                            .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if course.status == .active {
                            Text("여행 중")
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .background(Capsule().fill(Color.green.opacity(0.15)))
                                .foregroundStyle(.green)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.plain)
                Text("코스를 고치려면 경로여정 탭으로 가세요")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .padding(.vertical, 8)
            }
        }
    }
}

struct LikedWorksSheet: View {
    let works: [ContentSummary]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            sheetHeader("찜한 작품") { dismiss() }
            if works.isEmpty {
                ContentUnavailableView(
                    "찜한 작품이 없습니다",
                    systemImage: "heart",
                    description: Text("작품검색 탭에서 하트를 눌러 보세요")
                )
            } else {
                List(works, id: \.id) { work in
                    HStack(spacing: 10) {
                        RemoteImage(url: work.posterUrl, symbol: "film")
                            .frame(width: 34, height: 46)
                            .clipShape(.rect(cornerRadius: 5))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(work.title).font(.subheadline.weight(.medium))
                            Text([work.broadcaster, work.releaseYear.map(String.init)]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "heart.fill")
                            .font(.caption).foregroundStyle(.red)
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.plain)
            }
        }
    }
}

/// 두 팝업이 같은 머리줄을 쓴다 — 제목 가운데, 오른쪽 작은 X(앱 규칙).
private func sheetHeader(_ title: String, close: @escaping () -> Void) -> some View {
    ZStack {
        Text(title).font(.headline)
        HStack {
            Spacer()
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }
    .padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 6)
}
