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

    /// 코스 카드에 넣을 코스의 상세를 받는 중인가.
    @Published private(set) var loading = false

    /// 작품 목록조차 못 받았나 — 화면이 「불러오지 못했습니다」 를 띄우는 기준.
    @Published private(set) var failed = false

    private let installId = InstallIdentity.current

    /// 선반이 **작품 전부가 아니라 받은 범위에서** 골라졌나 — 훑다가 뒤쪽을 못 받았거나 상한(`scanLimit`)에
    /// 걸렸다. 화면에는 쓰지 않는다(선반은 그대로 그린다). 다음에 홈을 열면 처음부터 다시 훑는다.
    @Published private(set) var shelfIsPartial = false

    /// 코스 카드의 모습. 값만 보는 규칙이라 시험이 선다(`HomeTripCardTests`).
    enum TripCard: Equatable {
        /// 코스가 있다 — 제목·진행·단추 둘.
        case filled
        /// 아직 모른다 — 「내 여행을 불러오는 중…」.
        case loading
        /// 물었는데 못 받았다 — 「내 여행을 불러오지 못했어요」.
        case unavailable
        /// 코스가 **정말 없다** — 「코스 만들기」.
        case create
    }

    /// **코스가 있는지 모르는 동안에는 「코스 만들기」 를 권하지 않는다** (MZ2AZ-372).
    ///
    /// 전에는 코스 목록이 오기 전에도(다른 조회에 밀려 몇 초씩) 「여행을 시작해 볼까요? · 코스 만들기」 가
    /// 떴다 — 코스가 있는 사람에게. 그 단추는 일정짜기(유료)의 입구다. 「없다」 는 목록을 받아 본 뒤에만 말한다.
    nonisolated static func tripCard(
        hasTrip: Bool, courseList: RouteStore.CourseListState, hasCourses: Bool, tripsLoading: Bool
    ) -> TripCard {
        if hasTrip {
            return .filled
        }
        switch courseList {
        case .unknown: return .loading
        case .failed: return .unavailable
        // 목록은 왔고 코스가 있는데 카드가 아직 없으면 그 코스의 상세를 받는 중이다.
        case .loaded: return hasCourses && tripsLoading ? .loading : .create
        }
    }

    /// 코스 카드 — 코스 목록이 오는 대로 부른다. 나머지(`loadRest`)를 기다리지 않는다.
    func loadTrips(courses: [RouteCourse]) async {
        loading = true
        trips = await Self.trips(from: courses, installId: installId)
        loading = false
    }

    /// **코스와 무관한 셋을 나란히 받고, 온 것부터 그린다** (MZ2AZ-372).
    ///
    /// 전에는 넷을 나란히 보내 놓고도 **작품 응답을 먼저 기다린 뒤** 나머지를 대입했고, 그 앞에서는 화면이
    /// 코스 목록·촬영지 200·작품 30·장바구니를 차례로 다 기다린 뒤에야 이것을 불렀다. 작품이 한 번의 호출이던
    /// 때는 티가 안 났는데, 선반을 고르려고 작품을 끝까지 훑게 되면서 「오늘의 성지」·스탬프까지 그 뒤로
    /// 밀렸다. 이제 각자 받는 대로 제 자리에 넣는다 — 선반만 훑기가 끝난 뒤에 찬다.
    func loadRest() async {
        async let todayJob: Void = loadToday()
        async let stampsJob: Void = loadStamps()
        async let shelfJob: Void = loadShelf()
        _ = await (todayJob, stampsJob, shelfJob)
    }

    private func loadShelf() async {
        let scan = await Self.scanWorks()
        if scan.works.isEmpty, !scan.complete {
            failed = works.isEmpty
            return
        }
        works = Self.shelf(from: scan.works)
        shelfIsPartial = !scan.complete
        failed = false
    }

    private func loadToday() async {
        guard let list = try? await PlacesAPI.listPlaces(limit: 60), !list.items.isEmpty else { return }
        // **사진 있는 곳에서 고른다.** 카드 윗단이 96pt 그림 자리인데 사진이 없으면
        // 그라데이션만 남아 빈 띠로 보인다. 사진이 하나도 없을 때만 전체에서 고른다.
        let withPhoto = list.items.filter { Self.hasPhoto($0) }
        let pool = withPhoto.isEmpty ? list.items : withPhoto
        // **연중 몇 번째 날인가로 고른다.** 무작위면 화면을 다시 그릴 때마다 성지가
        // 바뀌어 「오늘의」 라는 말이 거짓이 된다. 하루가 지나면 다음 곳.
        let day = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1
        today = pool[day % pool.count]
    }

    private func loadStamps() async {
        stamps = await VisitStamp.collect(installId: installId)
    }

    // MARK: 「지금 뜨는 작품」 — 촬영지 많은 순

    /// 선반에 올리는 수.
    nonisolated static let shelfCount = 20
    /// 정렬하려고 훑는 작품 수의 상한. 넘으면 그 안에서만 고른다.
    nonisolated static let scanLimit = 500

    /// 작품을 **끝까지 훑어** 받는다 — 선반은 「촬영지 많은 순」 인데 서버에 그 정렬이 없다(MZ2AZ-372).
    ///
    /// 전에는 서버 순서의 앞 20편만 받아 그 안에서 정렬했다. 서버 순서가 사실상 「나중에 올린 순」 이라
    /// (인기 점수가 전부 같다) 선반 맨 앞이 6곳짜리 작품이고 19곳짜리는 아예 없었다. 지금은 전부를 보고 고른다.
    ///
    /// **임시다.** 작품이 `scanLimit` 을 넘으면 다시 틀린다 — 서버가 촬영지 수 정렬을 주면 그 한 번으로 바꾼다
    /// (계획 `catalog-scale.md` §6).
    ///
    /// `complete` 가 거짓이면 **받은 범위에서만** 고른 것이다 — 뒤쪽 쪽을 못 받았거나(공통 계층이 이미
    /// 세 번 다시 보낸 뒤다) 상한에 걸렸다. 그래도 받은 것으로 선반을 그린다(빈 선반보다 낫다).
    private static func scanWorks() async -> (works: [ContentSummary], complete: Bool) {
        var all: [ContentSummary] = []
        var paging = Paging()
        while paging.hasMore, paging.offset < scanLimit {
            guard let page = try? await ContentsAPI.listContents(limit: 100, offset: paging.offset) else {
                return (all, false)
            }
            all = Paging.merged(all, with: page.items, by: \.id)
            paging = paging.advanced(received: page.items.count, total: page.total)
        }
        return (all, !paging.hasMore)
    }

    /// 촬영지 많은 순으로 `shelfCount` 편. 같은 수면 서버가 준 순서(인기순)를 지킨다.
    nonisolated static func shelf(from works: [ContentSummary]) -> [ContentSummary] {
        works.enumerated()
            .sorted { ($0.element.placeCount, $1.offset) > ($1.element.placeCount, $0.offset) }
            .prefix(shelfCount)
            .map(\.element)
    }

    /// 여행 중인 코스를 앞에, 나머지는 목록 순서대로 — 최대 셋. 상세는 나란히 받는다.
    /// 코스가 없으면 빈 배열 — 카드는 「코스 만들기」 로 바뀐다.
    /// 화면에 실제로 뜨는 사진이 있는가.
    ///
    /// 주소가 있다고 그림이 나오는 것은 아니다 — 시드에 호스트 없는 상대 경로가 섞여
    /// 있어(`place_image` 82행 중 70행) `AsyncImage` 가 조용히 빈 자리를 남긴다.
    /// 그래서 **절대 http(s) 주소**만 사진으로 친다.
    /// 값만 보는 순수 함수라 `nonisolated` 다 — 모델이 `@MainActor` 이지만 이것까지
    /// 주 스레드에 묶을 이유가 없고, 묶으면 시험에서 부르지 못한다.
    nonisolated static func hasPhoto(_ place: PlaceSummary) -> Bool {
        guard let raw = place.imageUrl, let url = URL(string: raw), let scheme = url.scheme else {
            return false
        }
        return (scheme == "http" || scheme == "https") && url.host != nil
    }

    private static func trips(from courses: [RouteCourse], installId: UUID) async -> [HomeTrip] {
        let ordered = courses.filter(\.isRunning) + courses.filter { !$0.isRunning }
        let picks = Array(ordered.prefix(3))
        return await withTaskGroup(of: (Int, HomeTrip?).self) { group in
            for (index, course) in picks.enumerated() {
                group.addTask { await (index, trip(of: course, installId: installId)) }
            }
            var slots = [HomeTrip?](repeating: nil, count: picks.count)
            for await (index, trip) in group {
                slots[index] = trip
            }
            return slots.compactMap { $0 }
        }
    }

    private static func trip(of pick: RouteCourse, installId: UUID) async -> HomeTrip? {
        guard let serverId = pick.serverId else { return nil }

        guard let detail = try? await CoursesAPI.getCourse(xInstallId: installId, courseId: serverId) else {
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
