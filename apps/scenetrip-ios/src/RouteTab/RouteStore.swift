import Combine
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

    private let installId = InstallIdentity.current

    /// 질문 흐름과 AI 초안이 쓰는 **서버의 진짜 장소·작품.**
    ///
    /// 앞서 여기가 `RouteMock` 이었는데, 코스를 서버에 저장하기 시작하면서 그것이
    /// 곧바로 깨졌다 — 목 장소의 id 는 201~208 이고 서버에는 그런 장소가 없어서
    /// 코스를 만들 때 장소를 채우는 요청이 통째로 실패했다(실측: `placeId: 201` →
    /// 500, `placeId: 155` → 200). **저장할 것은 서버에 있는 것이어야 한다.**
    @Published private(set) var places: [PlaceSummary] = []
    @Published private(set) var works: [ContentSummary] = []

    /// 찜한 작품. **앱에 하나뿐인 `LikeStore` 를 그대로 본다**(2026-09-17).
    ///
    /// 앞서 여기에 목업 값으로 시작하는 임시 목록을 따로 들고 있었다 — 그때는 검색 탭에
    /// 하트가 없었다. 검색 탭과 마이페이지에 하트가 생긴 뒤에도 그대로여서, **작품검색에서
    /// 누른 하트가 AI 일정짜기의 작품 목록에 안 보였다**(사용자 지적). 찜은 한 곳에만 둔다.
    var favoriteWorkIds: Set<Int64> {
        LikeStore.shared.contentIds
    }

    /// `LikeStore` 가 바뀌면 이 객체를 보는 화면(마법사의 하트·정렬)도 다시 그려져야 한다.
    private var likesWatch: AnyCancellable?

    init() {
        // 하트(`contentIds`)와 찜한 작품의 요약(`works`) 어느 쪽이 바뀌어도 다시 그린다.
        likesWatch = LikeStore.shared.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
    }

    /// 질문 흐름에 뿌리는 작품 목록 — **찜한 것이 먼저, 나머지는 인기도순.**
    ///
    /// 8/11 회의 확정. 이미 관심을 밝힌 작품을 인기 순위에 묻어 두면 사용자가 자기가
    /// 찜한 작품을 목록에서 다시 찾아야 한다.
    ///
    /// **찜한 작품은 찜 목록에서 가져온다**(`LikeStore.works`, MZ2AZ-372). 전에는 인기 상위 30편
    /// (`works`) 안에서 찜한 것을 앞으로 옮기기만 해서, 그 30편 밖의 작품을 찜하면 여기에 아예 없었다.
    var sortedWorks: [ContentSummary] {
        let liked = LikeStore.shared.likedWorks
        let likedIds = Set(liked.map(\.id))
        return liked + works.filter { !likedIds.contains($0.id) }
    }

    // MARK: 촬영지가 나온 작품

    /// 촬영지 → 그곳이 나온 작품 제목. 코스 줄의 「어느 작품에 나온 곳인가」 에 쓴다.
    ///
    /// 계약의 코스 아이템에는 작품이 없어 따로 알아내야 한다. 전에는 받아 둔 촬영지 목록(`places`)에서
    /// 찾았는데, 그 목록은 **인기 상위 200곳뿐**이라 촬영지가 486곳이 되자 그 밖의 곳은 작품 줄이 비었다
    /// (MZ2AZ-372 — 「전부 받아 거기서 찾기」 와 같은 꼴). 이제 목록에 없는 곳은 그 곳의 상세를 받아 채운다.
    ///
    /// **열쇠가 있으면 답을 받은 것이다** — 값이 빈 배열이면 「작품이 정말 없다」 는 답이다. 못 받은 곳은
    /// 열쇠가 없어 다음에 다시 묻는다(`PlaceWorks.toAsk`).
    @Published private(set) var placeWorks: [Int64: [String]] = [:]
    private var placeWorksAsking: Set<Int64> = []

    func workTitles(ofPlace id: Int64) -> [String] {
        if let known = placeWorks[id] {
            return known
        }
        return (places.first { $0.id == id }?.contents ?? []).map(\.title)
    }

    /// 코스에 든 촬영지 가운데 아직 작품을 모르는 곳만 묻는다. 한 곳에 한 번이고, 한꺼번에 몰아 보내지 않는다.
    ///
    /// 작품은 상세의 **`scenes`** 에 있다 — 상세의 `contents` 는 서버가 비워 준다(`PlaceWorks`).
    func loadWorkTitles(for stops: [RouteStop]) async {
        let wanted = PlaceWorks.toAsk(
            stops.compactMap(\.placeId), // 편의시설·개인 핀은 촬영지가 아니다 — 물을 작품이 없다.
            listed: Set(places.map(\.id)),
            answered: Set(placeWorks.keys),
            asking: placeWorksAsking
        )
        placeWorksAsking.formUnion(wanted)
        for id in wanted {
            // 못 받았으면 답을 적지 않는다 — 다음에 다시 묻는다.
            if let detail = try? await PlacesAPI.getPlace(placeId: id) {
                placeWorks[id] = PlaceWorks.titles(scenes: detail.scenes, contents: detail.contents)
            }
            placeWorksAsking.remove(id)
        }
    }

    func isFavorite(_ workId: Int64) -> Bool {
        favoriteWorkIds.contains(workId)
    }

    func toggleFavorite(_ workId: Int64) {
        LikeStore.shared.toggle(workId)
    }

    func clearFailure() {
        failure = nil
    }

    // MARK: 서버

    /// 코스 목록을 **받아 봤는가** — 홈의 코스 카드가 이것으로 「받는 중」 과 「코스 없음」 을 가른다.
    enum CourseListState: Equatable {
        /// 아직 한 번도 답을 못 받았다. 코스가 있는지 모른다.
        case unknown
        /// 받았다 — `courses` 가 비었으면 정말 없는 것이다.
        case loaded
        /// 물었는데 못 받았다(그리고 전에 받아 둔 것도 없다). 여전히 있는지 모른다.
        case failed
    }

    @Published private(set) var courseList = CourseListState.unknown

    /// 코스 목록과 초안의 재료를 받아온다. 탭이 뜰 때와 저장·삭제 뒤에 부른다.
    ///
    /// 둘을 차례로 받고 **다 끝나야 돌아온다** — 경로 탭과 마법사가 그 완료에 기댄다. 코스 목록만
    /// 급한 곳(홈의 코스 카드)은 `refreshCourses` 와 `refreshMaterials` 를 따로 부른다(MZ2AZ-372).
    func refresh() async {
        await refreshCourses()
        await refreshMaterials()
    }

    /// 코스 목록만. 재료(촬영지 200 · 작품 30)를 기다리지 않는다.
    func refreshCourses() async {
        loading = courses.isEmpty
        defer { loading = false }
        do {
            let list = try await CoursesAPI.listCourses(xInstallId: installId)
            courses = list.items.map(RouteBridge.course(from:))
            courseList = .loaded
            failure = nil
        } catch {
            // 전에 받아 둔 목록이 있으면 그것이 화면에 남는다 — 「모른다」 로 되돌리지 않는다.
            if courseList != .loaded {
                courseList = .failed
            }
            failure = ApiFailure(error)
        }
    }

    /// 초안에 쓸 재료. 코스 목록과 따로 받는 이유는 **하나가 실패해도 다른 쪽은
    /// 살리기** 위해서다 — 장소를 못 받았다고 이미 만든 코스까지 안 보이면 안 된다.
    func refreshMaterials() async {
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
    }

    /// 코스 하나의 속을 받아온다. 목록 카드에는 일차 속이 없으므로 편집 화면을 열 때
    /// 부른다 — 목록에서 전부 받아 두면 코스가 많을 때 첫 화면이 느려진다.
    func detail(_ course: RouteCourse) async -> RouteCourse? {
        guard let serverId = course.serverId else { return course }
        do {
            let detail = try await CoursesAPI.getCourse(xInstallId: installId, courseId: serverId)
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
    /// `origin` 은 분석용 출처(`review` 등) — 안 주면 AI 초안인지로 정한다.
    @discardableResult
    func save(_ course: RouteCourse, origin: String? = nil) async -> RouteCourse? {
        do {
            let saved: CourseDetail
            if let serverId = course.serverId {
                saved = try await CoursesAPI.replaceCourse(
                    xInstallId: installId,
                    courseId: serverId,
                    courseReplace: RouteBridge.replace(from: course)
                )
            } else {
                // 만들기와 내용 채우기가 두 번에 나뉜다 — 계약이 만들 때는 기간과
                // 출처만 받고, 장소는 편집 완료로 넣게 돼 있다.
                let created = try await CoursesAPI.createCourse(
                    xInstallId: installId,
                    courseCreate: CourseCreate(
                        dayCount: course.days.count,
                        title: course.title,
                        origin: course.madeByAI ? .ai : ._self,
                        pace: course.pace == .loose ? .loose : .tight
                    )
                )
                do {
                    saved = try await CoursesAPI.replaceCourse(
                        xInstallId: installId,
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
                    try? await CoursesAPI.deleteCourse(xInstallId: installId, courseId: created.id)
                    throw error
                }
                // 핵심 지표 — 코스가 **새로** 만들어졌다. 고쳐 저장한 것은 세지 않는다 (MZ2AZ-353).
                AppAnalytics.log(.createCourse(
                    origin: origin ?? (course.madeByAI ? "ai" : "self"),
                    dayCount: course.days.count, placeCount: course.placeCount
                ))
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
            try await CoursesAPI.deleteCourse(xInstallId: installId, courseId: serverId)
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
                xInstallId: installId,
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
            title: tr("내 코스", at: "코스 제목"),
            startDate: startDate,
            days: (0 ..< span.days).map { _ in RouteDay() }
        )
    }

    /// 「AI 로 짜기」 — **백엔드 창구가 짠다** (`POST /guide/plan`, MZ2AZ-321).
    ///
    /// 앞서 여기는 앱 안의 규칙(`RoutePlanner`)으로 인기순+지리로 골랐다. 이제 일정은 에이전트의
    /// 코스 엔진 한 곳에서만 나온다 — 챗봇에 「도깨비로 1박 2일」이라고 말하든 이 마법사로 보내든
    /// 같은 일정이어야 한다(계약 설명). 모델은 안 부르므로 키 없이도 0.02초에 온다.
    ///
    /// **저장하지 않는다.** 응답은 초안이고 편집 화면에 그려진다. 저장은 「완료」뿐이다.
    ///
    /// 속도는 앱의 둘(빡빡·널널)을 계약의 셋 중 양끝(`packed`·`relaxed`)으로 보낸다. 작품을 하나도
    /// 안 골랐으면 인기 작품 셋의 제목을 보낸다 — 계약이 `titles` 를 최소 하나 요구한다.
    func guideDraft(
        span: RouteSpan,
        startDate: Date?,
        workIds: Set<Int64>,
        pace: RoutePace,
        near: (lat: Double, lng: Double)? = nil
    ) async -> Result<RouteCourse, RouteGuideFailure> {
        var titles = sortedWorks.filter { workIds.contains($0.id) }.map(\.title)
        if titles.isEmpty {
            titles = sortedWorks.prefix(3).map(\.title)
        }
        guard !titles.isEmpty else { return .failure(.badRequest) }
        let request = GuidePlanRequest(
            titles: Array(titles.prefix(5)),
            days: span.days,
            pace: pace == .loose ? .relaxed : .packed,
            latitude: near?.lat,
            longitude: near?.lng
        )
        do {
            let reply = try await GuideAPI.planWithGuide(guidePlanRequest: request)
            AppAnalytics.log(.generatePlan(dayCount: span.days, titleCount: request.titles.count))
            return .success(RouteGuidePlan.course(
                from: reply.plan,
                title: title(for: workIds, span: span),
                startDate: startDate,
                pace: pace
            ))
        } catch {
            return .failure(RouteGuideFailure(error))
        }
    }

    /// 코스 이름. 이름을 비워 두면 AI 가 작품 이름으로 지어 준다(목업 설계 메모).
    private func title(for workIds: Set<Int64>, span: RouteSpan) -> String {
        let titles = sortedWorks.filter { workIds.contains($0.id) }.map(\.title)
        guard let first = titles.first else { return String(format: tr("인기 촬영지 %@"), span.label) }
        let name = titles.count == 1 ? first : String(format: tr("%@ 외 %d"), first, titles.count - 1)
        return "\(name) \(span.label)"
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
