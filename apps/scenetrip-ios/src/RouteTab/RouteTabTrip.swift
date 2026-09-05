import SwiftUI

/// 코스 목록의 **데모 코스** (계획 trip-mode.md §7). 「이어서 길찾기」는 홈 카드에 있다.
///
/// `RouteTabView.swift` 에서 떼어 냈다(파일 길이 한도). 목록 화면과 만나는 곳이
/// 「여행 중」 섹션의 행 하나와 `.task` 의 한 줄뿐이라 자르는 선이 깨끗하다.
extension RouteTabView {
    /// 데모 주행(`-demoCourse 1`) — 저장소의 데모 코스가 이 설치본에 없으면 만들고,
    /// 여행 중으로 바꾼 뒤 그 코스를 열 쪽지를 남긴다. 프로세스당 한 번.
    /// 실행 인자 없이는 아무 것도 하지 않는다(계획 trip-mode.md §7).
    static var demoCourseEnsured = false

    func ensureDemoCourse() async {
        guard DemoDrive.wantsDemoCourse, !Self.demoCourseEnsured else { return }
        Self.demoCourseEnsured = true
        guard let file = DemoCourse.load() else { return }
        if let existing = store.courses.first(where: { $0.title == file.title }) {
            router.pendingCourseId = existing.serverId
            return
        }
        guard let saved = await store.save(DemoCourse.course(from: file)) else { return }
        await store.setRunning(saved, true, dayNo: 1)
        router.pendingCourseId = saved.serverId
    }
}
