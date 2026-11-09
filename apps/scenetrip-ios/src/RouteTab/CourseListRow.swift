import SwiftUI
import UIKit

// 코스 목록(「내 여행」)의 묶음·줄 (MZ2AZ-547 P7 L2–L4, 웹 `features/proto/ProtoCourse.tsx`).
// 판단은 `CourseListRules.swift`, 여기는 그리기만 한다.

/// 줄마다 사진·작품을 채우려고 받은 코스 상세. 목록 요약(`CourseSummary`)에는 일차 속이 없다.
///
/// 한 코스에 한 번만 받는다 — 줄이 다시 그려질 때마다 부르지 않게 열쇠(`CourseListRules.key`)로 기억한다.
/// 편집 화면에서 돌아오면(`reset`) 잊는다 — 고친 사진·작품이 다시 보여야 한다.
@MainActor
final class CourseListDetails: ObservableObject {
    @Published private(set) var byKey: [String: RouteCourse] = [:]
    private var asking: Set<String> = []

    func detail(_ course: RouteCourse) -> RouteCourse? {
        byKey[CourseListRules.key(course)]
    }

    func load(_ course: RouteCourse, using store: RouteStore) async {
        let key = CourseListRules.key(course)
        guard course.serverId != nil, byKey[key] == nil, !asking.contains(key) else { return }
        asking.insert(key)
        defer { asking.remove(key) }
        // 상세를 못 받으면 사진·작품만 비운 채 둔다 — 목록은 요약만으로도 그려진다. 다음에 다시 묻는다.
        guard let detail = await store.peekDetail(course) else { return }
        byKey[key] = detail
        // 작품 줄은 「받아 둔 촬영지 목록」 에 없는 곳만 묻는다 — 코스마다 앞의 세 곳만(호출이 코스 수 × 장소 수로 불지 않게).
        await store.loadWorkTitles(for: Array(detail.stops.prefix(3)))
    }

    func reset() {
        byKey = [:]
    }
}

/// 묶음 하나 — 위 작은 제목(개수), 흰 칸(모서리 12) 안에 줄들, 줄 사이 얇은 선은 사진 오른쪽부터.
struct CourseListGroup<Row: View>: View {
    let label: String
    let courses: [RouteCourse]
    @ViewBuilder var row: (RouteCourse, Bool) -> Row

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                Text(verbatim: label)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text(verbatim: "\(courses.count)")
                    .font(.system(size: 13))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 4)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                ForEach(Array(courses.enumerated()), id: \.element.id) { index, course in
                    row(course, index == 0)
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// 코스 한 줄 — 사진 60 · 이름 16 · 「N일차 · N박 N일 · N곳」 · 작품 최대 2, 오른쪽 「⋯」.
///
/// 누르면 열고, **꾹(0.2초) 누르면 여러 개 고르기**가 켜진다. 고르는 동안은 누르기가 체크가 되고 「⋯」 이 숨는다.
/// 지우기는 「⋯」 메뉴에 둔다 — 밀어서 지우기는 아무도 찾지 못했다(웹 2026-11-08 사용자 결정, iOS 2026-08-24 지적).
struct CourseListRow: View {
    let course: RouteCourse
    let first: Bool
    let photo: String?
    let works: [String]
    let picking: Bool
    let checked: Bool
    /// 「⋯」 메뉴가 떠 있나 — 확인용 실행 인자(`-courseList menu`)가 바깥에서 띄울 수 있게 묶는다.
    @Binding var menuOpen: Bool
    let onOpen: () -> Void
    let onHold: () -> Void
    let onToggle: () -> Void
    let onDelete: () -> Void

    private static let thumb: CGFloat = 60
    private static let pad: CGFloat = 12

    var body: some View {
        HStack(spacing: 0) {
            content
                .contentShape(.rect)
                .onTapGesture {
                    if picking {
                        onToggle()
                    } else {
                        onOpen()
                    }
                }
                .onLongPressGesture(minimumDuration: CourseListRules.holdSeconds) {
                    guard !picking else { return }
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onHold()
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(picking ? (checked ? [.isButton, .isSelected] : .isButton) : .isButton)
                .accessibilityAction(named: Text("여러 개 고르기")) {
                    if !picking {
                        onHold()
                    }
                }
            if !picking {
                menuButton
            }
        }
        .padding(.leading, Self.pad)
        // 줄 사이 선 — 글자 칸(사진 오른쪽)부터 끝까지.
        .overlay(alignment: .top) {
            if !first {
                Rectangle().fill(Color(.separator)).frame(height: 0.5)
                    .padding(.leading, Self.pad + (picking ? 36 : 0) + Self.thumb + 12)
            }
        }
        .background(checked ? NewCourseStyle.purple.opacity(0.13) : Color.clear)
    }

    private var content: some View {
        HStack(spacing: 12) {
            if picking {
                checkCircle
            }
            NewCoursePhoto(url: photo)
                .frame(width: Self.thumb, height: Self.thumb)
                .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
                .padding(.vertical, Self.pad)
                .accessibilityHidden(true)
            // 글자 칸 — 위 선이 여기서부터 시작한다.
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: course.title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(verbatim: CourseListText.subtitle(
                    isRunning: course.isRunning,
                    currentDayNo: course.currentDayNo,
                    dayCount: course.days.count,
                    placeCount: course.placeCount
                ))
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                if !works.isEmpty {
                    Text(verbatim: works.joined(separator: " · "))
                        .font(.system(size: 12))
                        .foregroundStyle(NewCourseStyle.purple)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(.vertical, Self.pad)
        }
    }

    private var checkCircle: some View {
        ZStack {
            if checked {
                Circle().fill(NewCourseStyle.purple)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white)
            } else {
                Circle().strokeBorder(Color(.systemGray3), lineWidth: 1.5)
            }
        }
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)
    }

    /// 「⋯」 — 누르면 작은 메뉴(「삭제」 하나). 확인 창은 목록이 띄운다.
    private var menuButton: some View {
        Button {
            menuOpen = true
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.tertiary)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .padding(.trailing, 2)
        .accessibilityLabel(Text(verbatim: String(format: tr("%@ 메뉴"), course.title)))
        .popover(isPresented: $menuOpen, attachmentAnchor: .point(.bottom), arrowEdge: .top) {
            Button(role: .destructive) {
                menuOpen = false
                onDelete()
            } label: {
                HStack(spacing: 10) {
                    Text("삭제")
                        .font(.system(size: 16, weight: .semibold))
                    Spacer(minLength: 0)
                    Image(systemName: "trash")
                        .font(.system(size: 16))
                }
                .foregroundStyle(.red)
                .padding(.horizontal, 16)
                .frame(width: 200, height: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .padding(.vertical, 6)
            .presentationCompactAdaptation(.popover)
        }
    }
}
