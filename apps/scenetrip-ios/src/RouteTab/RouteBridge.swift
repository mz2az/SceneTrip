import Foundation
import SceneApiClient

/// 계약 타입 ↔ 화면 타입 사이의 번역 (MZ2AZ-262).
///
/// 화면(`RouteCourse`·`RouteStop`)과 계약(`CourseDetail`·`CourseItem`)을 **한곳에서만**
/// 잇는다. 화면 코드가 계약 타입을 직접 만지면 서버가 필드를 하나 바꿀 때마다 화면
/// 여러 곳이 함께 깨진다.
///
/// ## 서버가 정본이다
///
/// 코스가 서버에 저장되므로 `id` 도 서버가 준다. 화면 타입의 `UUID` 는 SwiftUI 가
/// 목록을 그릴 때 쓰는 것이고, **서버 id 는 따로 들고 다닌다** — 편집 완료(`PUT`)에서
/// 그 값을 돌려보내야 하기 때문이다.
///
/// ## 아이템 id 를 잃으면 방문 체크가 날아간다
///
/// 계약이 못 박아 뒀다 — *"편집 완료(`PUT`)에서 이 값을 그대로 돌려보내야 한다.
/// 빠뜨리면 서버가 새 장소로 보아 방문 체크가 날아간다."* 그래서 `RouteStop` 이
/// `serverItemId` 를 들고 다니고, 편집 중에 순서를 바꾸거나 체류 시간을 고쳐도
/// 그 값은 따라다닌다.
enum RouteBridge {
    // MARK: 서버 → 화면

    static func course(from detail: CourseDetail) -> RouteCourse {
        RouteCourse(
            serverId: detail.id,
            title: detail.title,
            startDate: detail.startDate,
            pace: pace(from: detail.pace),
            days: detail.days.map { day in
                RouteDay(stops: day.items.map(stop(from:)))
            },
            madeByAI: detail.origin == .ai,
            isRunning: detail.status == .active
        )
    }

    /// 목록 카드용. 일차 속까지는 안 받으므로 `days` 가 비어 있고, 대신 서버가 세어 준
    /// 장소 수를 들고 온다 — 카드에 「7곳」을 그리는 데 상세를 부를 이유가 없다.
    static func course(from summary: CourseSummary) -> RouteCourse {
        RouteCourse(
            serverId: summary.id,
            title: summary.title,
            startDate: summary.startDate,
            pace: pace(from: summary.pace),
            days: Array(repeating: RouteDay(), count: max(summary.dayCount, 1)),
            madeByAI: summary.origin == .ai,
            isRunning: summary.status == .active,
            placeCountFromServer: summary.placeCount
        )
    }

    private static func stop(from item: CourseItem) -> RouteStop {
        RouteStop(
            place: PlaceSummary(
                id: item.placeId ?? -item.id, // 직접 찍은 핀은 촬영지 id 가 없다.
                name: item.name,
                type: item.category,
                address: item.address,
                latitude: item.latitude,
                longitude: item.longitude,
                imageUrl: item.imageUrl
            ),
            serverItemId: item.id,
            stayMinutes: item.dwellMinutes,
            isPinned: item.source == .custompin,
            visited: item.visitedAt != nil
        )
    }

    // MARK: 화면 → 서버

    /// 편집 완료로 보낼 몸통.
    ///
    /// **보낸 것이 코스의 전부다.** 빠진 아이템은 지운 것으로 처리되고, `days` 배열
    /// 길이가 곧 기간이 된다 — 5일 코스에 3개를 보내면 4·5일차 장소가 삭제된다.
    static func replace(from course: RouteCourse) -> CourseReplace {
        CourseReplace(
            title: course.title,
            startDate: course.startDate,
            days: course.days.map { day in
                CourseDayInput(items: day.stops.map(item(from:)))
            }
        )
    }

    private static func item(from stop: RouteStop) -> CourseItemInput {
        CourseItemInput(
            id: stop.serverItemId,
            placeId: stop.isPinned ? nil : stop.place.id,
            customPin: stop.isPinned ? CustomPinInput(
                name: stop.place.name,
                category: pinCategory(from: stop.place.type),
                latitude: stop.place.latitude,
                longitude: stop.place.longitude
            ) : nil,
            // **옮기기만 할 때도 현재 값을 실어야 한다.** 비우면 서버가 장소 유형별
            // 기본값으로 덮어써서 사용자가 정한 체류 시간이 사라진다.
            dwellMinutes: stop.stayMinutes
        )
    }

    /// 직접 찍은 핀의 분류. 계약은 닫힌 다섯 갈래이고 화면은 한국어 이름을 쓴다.
    private static func pinCategory(from text: String?) -> PinCategory {
        switch text {
        case "숙소": .lodging
        case "음식점·카페": .food
        case "명소·자연": .attraction
        case "거리·다리": .street
        default: .building
        }
    }

    private static func pace(from value: CoursePace?) -> RoutePace {
        value == .loose ? .loose : .tight
    }

    // MARK: 날짜

    //
    // 변환 코드가 없다. 계약의 `startDate` 는 시각 없는 날짜(`format: date`)인데
    // 생성된 클라이언트가 `Date` 로 **양방향 다** 처리해 준다. 앞서 여기에
    // `yyyy-MM-dd` 포매터를 뒀다가 걷어냈다 — 같은 일을 두 벌로 하면 어느 쪽이
    // 시간대를 어떻게 다루는지가 갈린다.
}
