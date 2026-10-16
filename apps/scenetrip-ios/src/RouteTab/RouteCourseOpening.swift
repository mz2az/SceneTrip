import Combine

/// 목록 요약은 편집하지 않는다. 상세 조회 성공만 편집 화면의 표시 상태로 전달한다.
@MainActor
final class RouteCourseOpening: ObservableObject {
    @Published var editing: RouteCourse?
    @Published private(set) var failedCourse: RouteCourse?
    @Published private(set) var isLoading = false

    func open(_ course: RouteCourse, using store: RouteStore) async {
        guard !isLoading else { return }
        isLoading = true
        editing = nil
        failedCourse = nil
        defer { isLoading = false }
        guard let detail = await store.detail(course) else {
            failedCourse = course
            return
        }
        editing = detail
    }

    func retry(using store: RouteStore) async {
        guard let course = failedCourse else { return }
        await open(course, using: store)
    }
}
