import SceneApiClient
import SwiftUI

/// 마이페이지의 팝업 둘 — 내 코스, 찜한 작품 (2026-08-28, 2차: 행을 누르면
/// **바로 아래 상세가 펼쳐진다** — 아코디언, 가이드 시트의 장소 목록과 같은 규칙).
///
/// 여기서 고치지는 않는다 — 코스 편집은 경로여정 탭이 맡고, 「경로여정에서 열기」가
/// 그리로 데려간다(`TabRouter`).
struct MyCoursesSheet: View {
    let courses: [CourseSummary]

    @Environment(\.dismiss) private var dismiss

    @State private var expanded: Int64?
    @State private var details: [Int64: CourseDetail] = [:]

    private let deviceId = InstallIdentity.current

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: "내 코스") { dismiss() }
            if courses.isEmpty {
                ContentUnavailableView(
                    "아직 코스가 없습니다",
                    systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                    description: Text("경로여정 탭에서 첫 코스를 만들어 보세요")
                )
            } else {
                List(courses, id: \.id) { course in
                    VStack(alignment: .leading, spacing: 6) {
                        rowHead(course)
                        if expanded == course.id {
                            detailBlock(course)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.plain)
            }
        }
    }

    private func rowHead(_ course: CourseSummary) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                expanded = expanded == course.id ? nil : course.id
            }
            if details[course.id] == nil {
                Task {
                    details[course.id] = try? await CoursesAPI.getCourse(
                        xDeviceId: deviceId, courseId: course.id
                    )
                }
            }
        } label: {
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
                Image(systemName: expanded == course.id ? "chevron.up" : "chevron.down")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func detailBlock(_ course: CourseSummary) -> some View {
        if let detail = details[course.id] {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(detail.days, id: \.dayNumber) { day in
                    HStack(alignment: .top, spacing: 6) {
                        Text("\(day.dayNumber)일차")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Color(PinImage.deep))
                            .frame(width: 38, alignment: .leading)
                        Text(day.items.map(\.name).joined(separator: " → "))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Button {
                    // 탭이 바뀌고 편집 화면이 열린다. 시트는 스스로 닫는다.
                    TabRouter.shared.openCourse(course.id)
                    dismiss()
                } label: {
                    Label("경로여정에서 열기", systemImage: "arrow.up.right")
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color.accentColor.opacity(0.12))
                        )
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
        } else {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("일정을 받아오는 중입니다").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

struct LikedWorksSheet: View {
    let works: [ContentSummary]

    /// 작품 목록을 못 받았을 때 그 이유. 있으면 「없습니다」 대신 이것을 보여
    /// 준다 — 없는 것과 못 받은 것은 다른 일이다.
    var failure: String?

    @Environment(\.dismiss) private var dismiss

    @State private var expanded: Int64?

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: "찜한 작품") { dismiss() }
            if let failure {
                ContentUnavailableView(
                    "작품 목록을 받지 못했습니다",
                    systemImage: "wifi.exclamationmark",
                    description: Text(failure).font(.caption2)
                )
            } else if works.isEmpty {
                ContentUnavailableView(
                    "찜한 작품이 없습니다",
                    systemImage: "heart",
                    description: Text("작품검색 탭에서 하트를 눌러 보세요")
                )
            } else {
                List(works, id: \.id) { work in
                    VStack(alignment: .leading, spacing: 6) {
                        workRow(work)
                        if expanded == work.id {
                            workDetail(work)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.plain)
            }
        }
    }

    private func workRow(_ work: ContentSummary) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                expanded = expanded == work.id ? nil : work.id
            }
        } label: {
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
                Image(systemName: expanded == work.id ? "chevron.up" : "chevron.down")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func workDetail(_ work: ContentSummary) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            if let genres = work.genres, !genres.isEmpty {
                Text(genres.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(Color.accentColor)
            }
            Text("촬영지 \(work.placeCount)곳")
                .font(.caption2).foregroundStyle(.secondary)
            Text("촬영지는 작품검색 탭에서 지도로 볼 수 있어요")
                .font(.caption2).foregroundStyle(.tertiary)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
    }
}

/// 두 팝업이 같은 머리줄을 쓴다 — 제목 가운데, 오른쪽 작은 X(앱 규칙).
struct ProfileSheetHeader: View {
    let title: String
    let close: () -> Void

    var body: some View {
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
}
