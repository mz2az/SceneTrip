import Foundation
import SceneApiClient

/// 경로여정 탭의 상태.
///
/// **서버가 없다.** 검색 탭의 `SceneData` 가 "서버가 정본" 이라고 적어 둔 것과 정반대
/// 자리다 — 여기서는 이 객체가 정본이고, 앱을 끄면 사라진다. 저장·동기화는 계약이
/// 생긴 뒤에 붙인다(8/11 회의 2-2, 백엔드 담당).
///
/// 화면이 `RouteMock` 을 직접 읽지 않고 전부 이 타입을 거치게 했다. 서버가 서면
/// 이 타입의 본문만 갈아 끼우면 화면은 그대로 남는다.
@MainActor
final class RouteStore: ObservableObject {
    @Published private(set) var courses: [RouteCourse] = []

    /// 찜한 작품. **데모용 로컬 상태다** — 작품 찜은 서버에도 검색 탭에도 아직 없다
    /// (8/11 회의에서 "작품에는 하트" 가 확정됐을 뿐이다).
    @Published private(set) var favoriteWorkIds: Set<Int64> = RouteMock.favoriteWorkIds

    /// 질문 흐름에 뿌리는 작품 목록 — **찜한 것이 먼저, 나머지는 인기도순.**
    ///
    /// 8/11 회의 확정. 이미 관심을 밝힌 작품을 인기 순위에 묻어 두면 사용자가 자기가
    /// 찜한 작품을 목록에서 다시 찾아야 한다.
    var works: [ContentSummary] {
        let all = RouteMock.works
        return all.filter { favoriteWorkIds.contains($0.id) }
            + all.filter { !favoriteWorkIds.contains($0.id) }
    }

    /// 남이 올린 코스. **한 번만 만든다** — 부를 때마다 새로 만들면 코스 id 가 매번
    /// 바뀌어 목록이 자기 자신을 다른 것으로 보고 통째로 다시 그린다.
    let popularCourses: [RouteCourse] = RouteMock.popularCourses()
    let popularStats: [(likes: Int, saves: Int)] = RouteMock.popularStats

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

    /// 코스를 넣거나 덮어쓴다. 편집 화면이 **작업 사본**을 들고 있다가 저장할 때 부른다
    /// — 초안을 바로 저장하지 않는다는 회의 확정을 화면이 아니라 이 흐름이 지킨다.
    func save(_ course: RouteCourse) {
        if let index = courses.firstIndex(where: { $0.id == course.id }) {
            courses[index] = course
        } else {
            courses.append(course)
        }
    }

    func delete(_ course: RouteCourse) {
        courses.removeAll { $0.id == course.id }
    }

    /// 남이 올린 코스를 내 것으로 담는다. **사본**이라 새 id 로 들어간다 —
    /// 원본이 나중에 바뀌어도 내 코스는 그대로다.
    func copyToMine(_ course: RouteCourse) -> RouteCourse {
        let copy = RouteCourse(
            title: course.title,
            startDate: nil,
            pace: course.pace,
            days: course.days.map { RouteDay(stops: $0.stops.map { RouteStop(place: $0.place) }) }
        )
        save(copy)
        return copy
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
    /// **진짜 모델을 부르지 않는다.** 서버도 에이전트도 아직 없다. 데모에서 보여 줄
    /// 것은 "고른 답이 일정으로 돌아온다" 는 흐름이므로, 좌표만으로 그럴듯한 순서를
    /// 만든다 — 가장 북쪽에서 출발해 최근접 이웃으로 사슬을 만들고 일차 수만큼 자른다.
    /// 일차끼리 지리적으로 갈리는 것은 그 결과다.
    ///
    /// **`pace` 를 쓰지 않는다.** 「빡빡/널널」이 일정을 어떻게 바꾸는지는 회의에서
    /// 정하지 않았다(회의록 5장 #3). 정하지 않은 것을 여기서 지어내면 팀이 화면을
    /// 보고 판단할 근거가 사라진다.
    func aiDraft(span: RouteSpan, startDate: Date?, workIds: Set<Int64>, pace: RoutePace) -> RouteCourse {
        let picked = RouteMock.places.filter { place in
            guard !workIds.isEmpty else { return true }
            return (place.contents ?? []).contains { workIds.contains($0.contentId) }
        }
        let chain = Self.chain(from: picked)
        return RouteCourse(
            title: title(for: workIds, span: span),
            startDate: startDate,
            pace: pace,
            days: Self.split(chain, into: span.days),
            madeByAI: true
        )
    }

    /// 코스 이름. 이름을 비워 두면 AI 가 작품 이름으로 지어 준다(목업 설계 메모).
    private func title(for workIds: Set<Int64>, span: RouteSpan) -> String {
        let titles = RouteMock.works.filter { workIds.contains($0.id) }.map(\.title)
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
        return converted.isEmpty ? (RouteMock.cartFallback, true) : (converted, false)
    }
}
