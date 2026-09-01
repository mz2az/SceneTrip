import Foundation
import SceneApiClient

/// 홈의 「내 여행 이어가기」 카드가 그리는 것 — 코스 하나와 그 진행.
struct HomeTrip {
    let course: RouteCourse
    /// 서버에 `visitedAt` 이 찍힌 정지점 수. **지어낸 값이 아니다** — 여행 중 성지
    /// 반경에 들거나 「여기 도착함」을 눌러 남은 기록이다(`VisitStamp` 와 같은 근거).
    let visited: Int
    let total: Int
    /// 진행 중인 일차(1부터). 서버의 `currentDayNo`, 없으면 1.
    let dayNo: Int
    let dayStops: [RouteStop]
    /// 「이어서 길찾기」가 향할 곳 — 진행 중 일차의 첫 미방문 정지점. 다 돌았으면 첫 곳.
    let nextStop: RouteStop?
}

/// 홈이 서버에서 받아 오는 것들 (계획 `mobile-home-tab.md` §2).
///
/// **네 요청을 나란히 보낸다.** 줄줄이 기다리면 코스 상세(스탬프용)가 느릴 때 작품
/// 선반까지 비어 있다 — 마이페이지가 같은 이유로 같은 모양이다(2026-08-28 버그).
/// 하나가 실패해도 나머지는 그린다 — 홈은 서버가 꺼져 있어도 죽지 않는다.
@MainActor
final class HomeTabModel: ObservableObject {
    /// 「지금 뜨는 작품」 — 촬영지 많은 순.
    @Published private(set) var works: [ContentSummary] = []

    /// 「오늘의 성지」 — 하루 동안 같은 곳.
    @Published private(set) var today: PlaceSummary?

    /// 「내 여행 이어가기」에 넘길 코스들 — 여행 중인 것 먼저, 최대 셋. 카드가
    /// 옆으로 넘겨 가며 보여 준다(2026-09-02 사용자 요청 — 하나만 떡하니 있으면
    /// 나머지 코스는 있는지도 모른다).
    @Published private(set) var trips: [HomeTrip] = []

    @Published private(set) var stamps: [VisitStamp] = []

    @Published private(set) var loading = false

    /// 작품 목록조차 못 받았나 — 화면이 「불러오지 못했습니다」 를 띄우는 기준.
    @Published private(set) var failed = false

    private let deviceId = InstallIdentity.current

    func load(courses: [RouteCourse]) async {
        loading = true
        defer { loading = false }

        let worksTask = Task { try? await ContentsAPI.listContents(limit: 20) }
        let placesTask = Task { try? await PlacesAPI.listPlaces(limit: 60) }
        let stampsTask = Task { await VisitStamp.collect(deviceId: deviceId) }
        let tripsTask = Task { await Self.trips(from: courses, deviceId: deviceId) }

        if let list = await worksTask.value {
            works = list.items.sorted { $0.placeCount > $1.placeCount }
            failed = false
        } else {
            failed = works.isEmpty
        }

        if let list = await placesTask.value, !list.items.isEmpty {
            // **연중 몇 번째 날인가로 고른다.** 무작위면 화면을 다시 그릴 때마다 성지가
            // 바뀌어 「오늘의」 라는 말이 거짓이 된다. 하루가 지나면 다음 곳.
            let day = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1
            today = list.items[day % list.items.count]
        }

        stamps = await stampsTask.value
        trips = await tripsTask.value
    }

    /// 여행 중인 코스를 앞에, 나머지는 목록 순서대로 — 최대 셋. 상세는 나란히 받는다.
    /// 코스가 없으면 빈 배열 — 카드는 「코스 만들기」 로 바뀐다.
    private static func trips(from courses: [RouteCourse], deviceId: UUID) async -> [HomeTrip] {
        let ordered = courses.filter(\.isRunning) + courses.filter { !$0.isRunning }
        let picks = Array(ordered.prefix(3))
        return await withTaskGroup(of: (Int, HomeTrip?).self) { group in
            for (index, course) in picks.enumerated() {
                group.addTask { await (index, trip(of: course, deviceId: deviceId)) }
            }
            var slots = [HomeTrip?](repeating: nil, count: picks.count)
            for await (index, trip) in group {
                slots[index] = trip
            }
            return slots.compactMap { $0 }
        }
    }

    private static func trip(of pick: RouteCourse, deviceId: UUID) async -> HomeTrip? {
        guard let serverId = pick.serverId else { return nil }

        guard let detail = try? await CoursesAPI.getCourse(xDeviceId: deviceId, courseId: serverId) else {
            // 상세를 못 받아도 카드는 뜬다 — 제목과 곳수는 목록에 있다.
            return HomeTrip(
                course: pick, visited: 0, total: pick.placeCount,
                dayNo: 1, dayStops: [], nextStop: nil
            )
        }

        let course = RouteBridge.course(from: detail)
        let items = detail.days.flatMap(\.items)
        let dayNo = max(1, min(detail.currentDayNo ?? 1, max(detail.days.count, 1)))
        let dayIndex = dayNo - 1
        let dayStops = course.days.indices.contains(dayIndex) ? course.days[dayIndex].stops : []
        let dayItems = detail.days.indices.contains(dayIndex) ? detail.days[dayIndex].items : []

        // 브리지는 일차 안의 순서를 지키므로 i 번째 아이템 = i 번째 정지점이다.
        var next = dayStops.first
        for (index, item) in dayItems.enumerated() where item.visitedAt == nil {
            if index < dayStops.count {
                next = dayStops[index]
            }
            break
        }

        return HomeTrip(
            course: course,
            visited: items.filter { $0.visitedAt != nil }.count,
            total: items.count,
            dayNo: dayNo,
            dayStops: dayStops,
            nextStop: next
        )
    }
}
