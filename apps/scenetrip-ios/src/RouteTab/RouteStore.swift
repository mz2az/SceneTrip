import Foundation
import SceneApiClient

/// 경로여정 탭의 상태.
///
/// **서버가 정본이다** (MZ2AZ-229·230). 코스를 만들고 고치고 지우는 것이 전부
/// `CoursesAPI` 로 나가고, 이 객체는 화면이 읽을 사본을 들고 있을 뿐이다.
///
/// 앞서 이 자리는 "서버가 없다 — 이 객체가 정본이고 앱을 끄면 사라진다" 였다.
/// 그때 화면이 `RouteMock` 을 직접 읽지 않고 전부 이 타입을 거치게 해 두었기 때문에,
/// 서버가 선 지금 **이 타입의 본문만 갈아 끼우고 화면은 그대로 두었다.**
///
/// ## 서버가 꺼져 있으면
///
/// 목록이 빈다. 예시 값으로 메우지 않는다 — 코스는 사용자가 만든 것이라, 없는데
/// 있는 척하면 「내가 만든 게 어디 갔지」가 된다. 장바구니(`cartPlaces`)만은 예시를
/// 쓰는데 그쪽은 남이 채워 주는 값이라 성격이 다르다.
@MainActor
final class RouteStore: ObservableObject {
    @Published private(set) var courses: [RouteCourse] = []

    /// 마지막 호출이 실패했나. 화면이 「불러오지 못했습니다」를 띄우는 데 쓴다.
    @Published private(set) var failure: ApiFailure?

    /// 목록을 처음 받아오는 중인가.
    @Published private(set) var loading = false

    private let deviceId = InstallIdentity.current

    /// 질문 흐름과 AI 초안이 쓰는 **서버의 진짜 장소·작품.**
    ///
    /// 앞서 여기가 `RouteMock` 이었는데, 코스를 서버에 저장하기 시작하면서 그것이
    /// 곧바로 깨졌다 — 목 장소의 id 는 201~208 이고 서버에는 그런 장소가 없어서
    /// 코스를 만들 때 장소를 채우는 요청이 통째로 실패했다(실측: `placeId: 201` →
    /// 500, `placeId: 155` → 200). **저장할 것은 서버에 있는 것이어야 한다.**
    @Published private(set) var places: [PlaceSummary] = []
    @Published private(set) var works: [ContentSummary] = []

    /// 찜한 작품. **데모용 로컬 상태다** — 작품 찜은 서버에도 검색 탭에도 아직 없다
    /// (8/11 회의에서 "작품에는 하트" 가 확정됐을 뿐이다).
    @Published private(set) var favoriteWorkIds: Set<Int64> = RouteMock.favoriteWorkIds

    /// 질문 흐름에 뿌리는 작품 목록 — **찜한 것이 먼저, 나머지는 인기도순.**
    ///
    /// 8/11 회의 확정. 이미 관심을 밝힌 작품을 인기 순위에 묻어 두면 사용자가 자기가
    /// 찜한 작품을 목록에서 다시 찾아야 한다.
    var sortedWorks: [ContentSummary] {
        works.filter { favoriteWorkIds.contains($0.id) }
            + works.filter { !favoriteWorkIds.contains($0.id) }
    }

    /// 남이 올린 코스 — **이제 서버에서 온다** (`GET /market/courses`, 2026-08-27).
    ///
    /// 8/24 까지는 지어낸 목록이었다. 코스를 공유하는 API 가 없어서 화면을 보여 주려고
    /// 서버 장소로 코스를 지어 냈는데, 백엔드가 마켓 API 를 올리면서 그럴 이유가
    /// 사라졌다. 지어내던 코드(`buildPopular`)는 걷어냈다.
    ///
    /// 좋아요·담긴 수도 서버 값이다 — 앞서 `RouteMock.popularStats` 로 박아 둔
    /// 값이었다.
    @Published private(set) var marketCourses: [MarketCourseSummary] = []

    /// 마켓을 한 번이라도 불러 봤는가. 안 불러 본 것과 「비어 있다」를 갈라야
    /// 화면이 「아직 없습니다」를 언제 띄울지 안다.
    @Published private(set) var marketLoaded = false

    func isFavorite(_ workId: Int64) -> Bool {
        favoriteWorkIds.contains(workId)
    }

    func toggleFavorite(_ workId: Int64) {
        if favoriteWorkIds.contains(workId) {
            favoriteWorkIds.remove(workId)
        } else {
            favoriteWorkIds.insert(workId)
        }
    }

    func clearFailure() {
        failure = nil
    }

    // MARK: 서버

    /// 코스 목록을 받아온다. 탭이 뜰 때와 저장·삭제 뒤에 부른다.
    func refresh() async {
        loading = courses.isEmpty
        defer { loading = false }
        do {
            let list = try await CoursesAPI.listCourses(xDeviceId: deviceId)
            courses = list.items.map(RouteBridge.course(from:))
            failure = nil
        } catch {
            failure = ApiFailure(error)
        }

        // 초안에 쓸 재료. 코스 목록과 따로 받는 이유는 **하나가 실패해도 다른 쪽은
        // 살리기** 위해서다 — 장소를 못 받았다고 이미 만든 코스까지 안 보이면 안 된다.
        do {
            if places.isEmpty {
                // **넉넉히 받는다.** 60건만 받았더니 그 안에 도깨비 촬영지가 몰려 있어
                // 다른 작품은 촬영지가 없는 것처럼 보였다(실측: 눈물의 여왕 67곳인데
                // 60건 안에는 3곳뿐). 촬영지 전체가 155건이라 한 번에 받아도 된다.
                places = try await PlacesAPI.listPlaces(limit: 200).items
            }
            if works.isEmpty {
                works = try await ContentsAPI.listContents(limit: 30).items
            }
        } catch {
            failure = ApiFailure(error)
        }

        if !marketLoaded {
            await refreshMarket()
        }
    }

    /// 마켓 목록을 받아온다. **코스 목록과 따로 실패한다** — 마켓이 안 떠도 내 코스는
    /// 보여야 한다.
    func refreshMarket(sort: MarketSort = .saves) async {
        do {
            marketCourses = try await MarketAPI.listMarketCourses(
                xDeviceId: deviceId, sort: sort, limit: 30
            ).items
            marketLoaded = true
        } catch {
            failure = ApiFailure(error)
        }
    }

    /// 마켓 코스를 내 코스로 담는다 (`POST /market/courses/{id}/saves`).
    ///
    /// **서버가 사본을 만들어 준다** — 앞서 앱이 장소를 하나씩 넣어 코스를 새로
    /// 만들었는데, 이제 한 번 부르면 끝이고 담긴 수도 서버가 센다.
    ///
    /// 가입 사용자만 할 수 있다(계약 `SignInRequired`). 비회원이면 `401` 이 오고
    /// 화면이 그 사실을 알린다.
    func saveFromMarket(_ course: MarketCourseSummary) async -> Bool {
        do {
            _ = try await MarketAPI.saveMarketCourse(
                xDeviceId: deviceId, marketCourseId: course.id
            )
            failure = nil
            await refresh()
            return true
        } catch {
            failure = ApiFailure(error)
            return false
        }
    }

    /// 좋아요를 켜고 끈다. 가입 사용자만 할 수 있다.
    func toggleMarketLike(_ course: MarketCourseSummary) async {
        do {
            if course.liked {
                try await MarketAPI.unlikeMarketCourse(
                    xDeviceId: deviceId, marketCourseId: course.id
                )
            } else {
                try await MarketAPI.likeMarketCourse(
                    xDeviceId: deviceId, marketCourseId: course.id
                )
            }
            failure = nil
            await refreshMarket()
        } catch {
            failure = ApiFailure(error)
        }
    }

    /// 코스 하나의 속을 받아온다. 목록 카드에는 일차 속이 없으므로 편집 화면을 열 때
    /// 부른다 — 목록에서 전부 받아 두면 코스가 많을 때 첫 화면이 느려진다.
    func detail(_ course: RouteCourse) async -> RouteCourse? {
        guard let serverId = course.serverId else { return course }
        do {
            let detail = try await CoursesAPI.getCourse(xDeviceId: deviceId, courseId: serverId)
            failure = nil
            return RouteBridge.course(from: detail)
        } catch {
            failure = ApiFailure(error)
            return nil
        }
    }

    /// 코스를 넣거나 덮어쓴다. 편집 화면이 **작업 사본**을 들고 있다가 저장할 때 부른다
    /// — 초안을 바로 저장하지 않는다는 회의 확정을 화면이 아니라 이 흐름이 지킨다.
    ///
    /// 서버 id 가 없으면 만들고(`POST`), 있으면 통째로 덮어쓴다(`PUT`).
    /// **덮어쓰기는 보낸 것이 전부다** — 빠진 아이템은 지운 것이 된다.
    @discardableResult
    func save(_ course: RouteCourse) async -> RouteCourse? {
        do {
            let saved: CourseDetail
            if let serverId = course.serverId {
                saved = try await CoursesAPI.replaceCourse(
                    xDeviceId: deviceId,
                    courseId: serverId,
                    courseReplace: RouteBridge.replace(from: course)
                )
            } else {
                // 만들기와 내용 채우기가 두 번에 나뉜다 — 계약이 만들 때는 기간과
                // 출처만 받고, 장소는 편집 완료로 넣게 돼 있다.
                let created = try await CoursesAPI.createCourse(
                    xDeviceId: deviceId,
                    courseCreate: CourseCreate(
                        dayCount: course.days.count,
                        title: course.title,
                        origin: course.madeByAI ? .ai : ._self,
                        pace: course.pace == .loose ? .loose : .tight
                    )
                )
                do {
                    saved = try await CoursesAPI.replaceCourse(
                        xDeviceId: deviceId,
                        courseId: created.id,
                        courseReplace: RouteBridge.replace(from: course)
                    )
                } catch {
                    // **채우기가 실패하면 만든 코스를 되돌린다.**
                    //
                    // 두 번에 나뉜 탓에 앞은 성공하고 뒤가 실패할 수 있다. 그대로 두면
                    // 장소 없는 껍데기가 목록에 남고, 사용자가 저장을 다시 누를 때마다
                    // 하나씩 더 쌓인다(실측: 세 번 눌러 빈 코스 3개).
                    //
                    // 되돌리기가 실패해도 원래 오류를 알린다 — 사용자에게 중요한 것은
                    // 「저장이 안 됐다」이지 「치우다 실패했다」가 아니다.
                    try? await CoursesAPI.deleteCourse(xDeviceId: deviceId, courseId: created.id)
                    throw error
                }
            }
            failure = nil
            let result = RouteBridge.course(from: saved)
            await refresh()
            return result
        } catch {
            failure = ApiFailure(error)
            return nil
        }
    }

    func delete(_ course: RouteCourse) async {
        guard let serverId = course.serverId else {
            courses.removeAll { $0.id == course.id }
            return
        }
        do {
            try await CoursesAPI.deleteCourse(xDeviceId: deviceId, courseId: serverId)
            failure = nil
        } catch {
            failure = ApiFailure(error)
        }
        await refresh()
    }

    /// 「코스 시작 / 여행 종료」. 상태가 `active` 일 때만 방문 체크와 길찾기가 열린다.
    /// - Parameter dayNo: 지금 몇 일차인가. **`active` 로 바꿀 때 반드시 보낸다** —
    ///   빠뜨리면 서버가 `400 여행 중으로 바꾸려면 currentDayNo 가 필요합니다` 를
    ///   준다(2026-08-24 실측). 앞서 안 보내고 있어서 「코스 시작」이 늘 실패했고,
    ///   화면에는 오류가 잠깐 떴다 사라졌다 — `refresh()` 가 뒤이어 `failure` 를
    ///   지웠기 때문이다.
    ///
    ///   `upcoming` 으로 되돌릴 때는 보내지 않는다. 예정 코스에 「지금 몇 일차」는
    ///   뜻이 없고, 계약도 그렇게 적어 두었다.
    func setRunning(_ course: RouteCourse, _ running: Bool, dayNo: Int = 1) async {
        guard let serverId = course.serverId else { return }
        do {
            _ = try await CoursesAPI.updateCourseProgress(
                xDeviceId: deviceId,
                courseId: serverId,
                courseProgress: CourseProgress(
                    status: running ? .active : .upcoming,
                    currentDayNo: running ? dayNo : nil
                )
            )
            failure = nil
        } catch {
            failure = ApiFailure(error)
            // **실패했으면 되돌린다.** 화면은 이미 「여행 종료」로 바뀌어 있는데
            // 서버는 예정 그대로다 — 그 어긋남을 두면 다음 저장이 엉뚱하게 나간다.
            await refresh()
            return
        }
        await refresh()
    }

    // MARK: 초안 만들기

    /// 「직접 짜기」 — 빈 일차만 있는 코스.
    func emptyCourse(span: RouteSpan, startDate: Date?) -> RouteCourse {
        RouteCourse(
            title: "내 코스",
            startDate: startDate,
            days: (0 ..< span.days).map { _ in RouteDay() }
        )
    }

    /// 「AI 로 짜기」 — 고른 작품의 촬영지로 초안을 만든다.
    ///
    /// **로컬 모델이 고른다** (`RoutePlanner`). 앞서 여기는 고른 작품의 촬영지를
    /// 전부 담았는데, 눈물의 여왕은 67곳이라 1박 2일이면 하루 34곳이 됐다 —
    /// 그렇게 도는 여행자는 없다.
    ///
    /// 모델이 없으면 인기순으로 상한만큼 고른다. **어느 쪽이든 하루 상한을 코드가
    /// 지킨다** — 모델이 규칙을 어겨도 34곳이 나오지 않는다.
    ///
    /// **`pace` 가 이제 일을 한다.** 빡빡 5곳 / 널널 3곳. 회의가 항목만 정하고 로직을
    /// 열어 뒀는데(회의록 Open Issue 3), 하루 몇 곳인가는 그중 가장 눈에 보이는
    /// 값이라 여기서 먼저 정한다.
    func aiDraft(
        span: RouteSpan,
        startDate: Date?,
        workIds: Set<Int64>,
        pace: RoutePace,
        near: (lat: Double, lng: Double)? = nil
    ) async -> RouteCourse {
        // 고른 작품의 촬영지만 고른다. **하나도 없으면 인기 장소로 채운다** —
        // 빈 코스를 내놓으면 사용자는 앱이 고장 난 줄 안다.
        //
        // 실제로 0곳이 되는 경우는 드물다. 앞서 「케이팝 데몬 헌터스는 촬영지가
        // 0곳」이라고 판단한 적이 있는데 **틀렸다** — 촬영지 목록을 60건만 받아 보고
        // 내린 결론이었고, 그 60건에 도깨비가 몰려 있었을 뿐이다(실제 11곳).
        let matched = places.filter { place in
            guard !workIds.isEmpty else { return true }
            return (place.contents ?? []).contains { workIds.contains($0.contentId) }
        }
        let pool = matched.isEmpty ? places : matched
        let titles = works.filter { workIds.contains($0.id) }.map(\.title)
        let planned = await RoutePlanner.plan(
            places: pool,
            days: span.days,
            pace: pace,
            workTitles: titles,
            near: near
        )
        // 고른 뒤에 순서를 잡는다 — 무엇을 갈지는 모델이, 어느 순서로 갈지는
        // 거리 계산이 정한다.
        let chain = RouteGeometry.optimized(planned.stops)
        return RouteCourse(
            title: title(for: workIds, span: span),
            startDate: startDate,
            pace: pace,
            days: Self.split(chain, into: span.days),
            madeByAI: true,
            // 고른 작품에 촬영지가 없어 인기 장소로 대신 채웠나. 편집 화면이
            // 이 사실을 사용자에게 알린다 — 모르고 저장하면 "내가 고른 작품이
            // 아닌데" 가 된다.
            filledFromPopular: matched.isEmpty && !workIds.isEmpty
        )
    }

    /// 코스 이름. 이름을 비워 두면 AI 가 작품 이름으로 지어 준다(목업 설계 메모).
    private func title(for workIds: Set<Int64>, span: RouteSpan) -> String {
        let titles = works.filter { workIds.contains($0.id) }.map(\.title)
        guard let first = titles.first else { return "인기 촬영지 \(span.label)" }
        let name = titles.count == 1 ? first : "\(first) 외 \(titles.count - 1)"
        return "\(name) \(span.label)"
    }

    /// 가장 북쪽에서 출발해 매번 가장 가까운 곳으로 이어 붙인다.
    private static func chain(from places: [PlaceSummary]) -> [RouteStop] {
        guard let start = places.max(by: { $0.latitude < $1.latitude }) else { return [] }
        let stops = [RouteStop(place: start)]
            + places.filter { $0.id != start.id }.map { RouteStop(place: $0) }
        return RouteGeometry.optimized(stops)
    }

    /// 사슬을 일차 수만큼 고르게 자른다.
    ///
    /// 장소가 일차보다 적으면 **빈 일차가 남는다.** 그것을 감추려고 억지로 채우지
    /// 않는다 — 목 데이터가 14곳뿐이라 5박 6일을 고르면 실제로 빌 수 있고, 빈 일차가
    /// 보여야 팀이 "장소가 모자랄 때 어떻게 보이는가" 를 판단할 수 있다.
    private static func split(_ stops: [RouteStop], into days: Int) -> [RouteDay] {
        guard days > 0 else { return [] }
        // 고르게 나눈다. 넣을 것을 이미 `RoutePlanner` 가 상한만큼 추렸으므로
        // 여기서는 자르지 않고 그대로 편다.
        let perDay = max(1, Int(ceil(Double(stops.count) / Double(days))))
        return (0 ..< days).map { index in
            let lower = min(index * perDay, stops.count)
            let upper = min(lower + perDay, stops.count)
            return RouteDay(stops: Array(stops[lower ..< upper]))
        }
    }

    // MARK: 장바구니

    /// 장바구니의 장소를 코스에 담을 수 있는 형태로 바꾼다.
    ///
    /// **장바구니는 검색 탭에서 이어진다** — 같은 기기 UUID 를 쓰므로 `CartStore` 를
    /// 새로 만들어도 서버에 있는 그 장바구니가 온다. 좌표가 없는 항목은 지도에 찍을 수
    /// 없으므로 버린다(계약상 `latitude`·`longitude` 가 필수가 아니다).
    ///
    /// 서버가 꺼져 있으면 장바구니가 비는데, 그때는 예시 값으로 대신한다 — 이 데모는
    /// 서버 없이도 떠야 팀이 화면을 볼 수 있다.
    /// 장바구니가 비었을 때 대신 보여 줄 것. **서버의 인기 장소**를 쓴다.
    ///
    /// 앞서 여기서 목 장소를 돌려줬는데, 그것을 코스에 담으면 서버가 외래키 위반으로
    /// 거부했다(실측: `course_item_place_id_fkey`). 화면에 보이는 것은 **담을 수 있는
    /// 것**이어야 한다.
    func cartSample() -> [PlaceSummary] {
        Array(places.prefix(6))
    }

    static func cartPlaces(_ items: [CartItem]) -> (places: [PlaceSummary], isSample: Bool) {
        let converted = items.compactMap { item -> PlaceSummary? in
            guard let lat = item.latitude, let lng = item.longitude else { return nil }
            return PlaceSummary(
                id: item.placeId,
                name: item.name,
                type: nil,
                address: item.address,
                latitude: lat,
                longitude: lng,
                imageUrl: item.imageUrl,
                contents: item.sourceContentId.map { id in
                    [ContentRef(contentId: id, title: item.sourceContentTitle ?? "")]
                }
            )
        }
        return converted.isEmpty ? ([], true) : (converted, false)
    }
}
