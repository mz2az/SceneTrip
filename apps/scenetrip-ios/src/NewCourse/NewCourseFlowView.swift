import SwiftUI

/// 새 코스 만들기 덮개의 뿌리 — 무엇으로 시작했는지에 따라 흐름 하나를 띄운다 (MZ2AZ-547 P3).
///
/// 홈(`RootTabs`)이 `TabRouter.Cover.newCourse` 로 띄운다. 도시로 만든 코스는 저장 뒤 `TabRouter.courseCreated` 가 덮개를
/// 경로여정으로 갈아 끼워 계획 화면을 연다. 작품으로·담은 곳으로는 이 덮개 안에서 저장 전 초안 편집(isNew)으로 바뀐다 —
/// 편집 화면의 닫기가 이 덮개를 닫는다.
struct NewCourseFlowView: View {
    let start: NewCourseStart
    let onClose: () -> Void

    var body: some View {
        switch start {
        case let .city(key):
            CityQuestionView(startCity: key, onClose: onClose)
        case .works:
            WorksPlanView(onClose: onClose)
        case .saved:
            SavedPlanView(onClose: onClose)
        }
    }
}
