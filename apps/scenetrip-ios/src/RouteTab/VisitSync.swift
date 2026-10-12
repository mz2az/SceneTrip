import SceneApiClient
import SwiftUI

/// 방문 저장의 성공·실패를 표시한다. 실패를 성공 스탬프로 바꾸지 않는다 (MZ2AZ-225).
@MainActor
final class VisitSync: ObservableObject {
    @Published private(set) var saving: Set<Int64> = []
    @Published private(set) var failed: Set<Int64> = []
    private let write: (Int64, Int64) async throws -> Void

    init(write: @escaping (Int64, Int64) async throws -> Void = { course, item in
        _ = try await CoursesAPI.updateCourseItemVisit(
            xInstallId: InstallIdentity.current, courseId: course, itemId: item,
            visitUpdate: VisitUpdate(visited: true)
        )
    }) {
        self.write = write
    }

    func save(courseId: Int64, itemId: Int64) async -> Bool {
        guard !saving.contains(itemId) else { return false }
        saving.insert(itemId)
        failed.remove(itemId)
        defer { saving.remove(itemId) }
        do {
            try await write(courseId, itemId)
            return true
        } catch {
            failed.insert(itemId)
            return false
        }
    }

    func isPending(_ itemId: Int64?) -> Bool {
        guard let itemId else { return false }
        return saving.contains(itemId) || failed.contains(itemId)
    }
}
