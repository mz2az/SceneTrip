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
                let stops = day.items.map(stop(from:))
                return RouteDay(stops: stops, estimate: RouteDayEstimate(stops: stops, totalMinutes: day.totalMinutes))
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

    /// 항목의 갈래 — 서버의 `source` 를 화면의 `RouteStop.Kind` 로 (MZ2AZ-380).
    ///
    /// `source: poi` 인데 `poiId` 가 없는 항목은 계약상 없다. 와도 줄은 그린다 — 이름·좌표만 가진 개인 핀으로.
    static func kind(of item: CourseItem) -> RouteStop.Kind {
        switch item.source {
        case .place: .place
        case .poi: item.poiId.map(RouteStop.Kind.poi) ?? .pin
        case .custompin: .pin
        }
    }

    static func stop(from item: CourseItem) -> RouteStop {
        let kind = kind(of: item)
        // 촬영지가 아니면 촬영지 id 가 없다 — 편의시설의 id 는 `kind` 가 든다(`RouteStop.placeId`·`poiId`).
        let placeId: Int64 = switch kind {
        case .place: item.placeId ?? -item.id
        case .poi: RouteStop.noPlaceId
        case .pin: -item.id
        }
        return RouteStop(
            place: PlaceSummary(
                id: placeId,
                name: item.name,
                type: item.category,
                address: item.address,
                latitude: item.latitude,
                longitude: item.longitude,
                imageUrl: item.imageUrl
            ),
            serverItemId: item.id,
            stayMinutes: item.dwellMinutes,
            kind: kind,
            poiText: item.source == .poi ? RouteStop.PoiText(
                displayName: item.displayName, nameRoman: item.nameRoman,
                categoryLabel: item.categoryLabel, displayAddress: item.displayAddress
            ) : nil,
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
                // 초안에 `placeId` 가 없던 줄은 저장할 수 없다 — 이름으로 대체하지 않는다
                // (계약 `GuidePlanStop.placeId`, MZ2AZ-321). 화면에는 「저장 안 됨」으로 남아 있다.
                CourseDayInput(items: day.stops.filter { !$0.placeMissing }.map(item(from:)))
            }
        )
    }

    /// 줄이 서버에서 가리키는 것 — **셋 중 정확히 하나**(계약 `CourseItemInput`, 1.8.0).
    ///
    /// 둘 이상이거나 하나도 없으면 서버가 400 으로 코스 저장 전체를 물린다. 그래서 갈래를 값으로 만들어
    /// 「둘 다 실린 몸통」 을 만들 길을 없앤다(`item` 이 이것만 본다).
    enum ItemTarget: Equatable {
        case place(Int64)
        case poi(Int64)
        case pin(CustomPinInput)
    }

    static func target(of stop: RouteStop) -> ItemTarget {
        switch stop.kind {
        case .place:
            .place(stop.place.id)
        case let .poi(poiId):
            .poi(poiId)
        case .pin:
            .pin(CustomPinInput(
                name: stop.place.name,
                category: pinCategory(from: stop.place.type),
                latitude: stop.place.latitude,
                longitude: stop.place.longitude
            ))
        }
    }

    static func item(from stop: RouteStop) -> CourseItemInput {
        var placeId: Int64?
        var poiId: Int64?
        var customPin: CustomPinInput?
        switch target(of: stop) {
        case let .place(id): placeId = id
        case let .poi(id): poiId = id
        case let .pin(pin): customPin = pin
        }
        return CourseItemInput(
            id: stop.serverItemId,
            placeId: placeId,
            poiId: poiId,
            customPin: customPin,
            // **옮기기만 할 때도 현재 값을 실어야 한다.** 비우면 서버가 장소 유형별
            // 기본값으로 덮어써서 사용자가 정한 체류 시간이 사라진다.
            dwellMinutes: stop.stayMinutes
        )
    }

    // MARK: 바뀐 것

    /// 저장할 때의 코스 이름. 앞뒤 공백을 떼고, 비었으면 「직접 짜기」의 기본 이름으로 둔다 —
    /// 비운 채 저장하면 목록에 이름 없는 코스가 생긴다.
    static func savedTitle(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? tr("내 코스", at: "코스 제목") : trimmed
    }

    /// 지금 「저장」을 누르면 **서버에 갈 모양** — `replace` 에 이름 다듬기까지 얹은 것.
    ///
    /// 편집 화면이 열릴 때 이것을 떠 두고 「취소」 때 다시 떠서 견준다(MZ2AZ-369). 화면 타입
    /// (`RouteCourse`)끼리 견주지 않는 이유가 있다 — 거기에는 서버에 안 가는 것(다녀옴 표시,
    /// 여행 중 여부, SwiftUI 용 `UUID`, 저장 안 되는 초안 줄)이 섞여 있어, 스탬프 하나 찍힌
    /// 것이나 「코스 시작」까지 「바꾼 내용」으로 잡힌다. 나가는 몸통으로 보면 저장했을 때
    /// 달라지는 것만 남는다.
    static func outgoing(from course: RouteCourse) -> CourseReplace {
        var body = replace(from: course)
        body.title = savedTitle(course.title)
        return body
    }

    /// 화면을 연 뒤 **저장하면 달라질 것**이 생겼는가. 바꿨다가 되돌렸으면 없는 것이다.
    static func changed(from opened: CourseReplace, to course: RouteCourse) -> Bool {
        outgoing(from: course) != opened
    }

    /// 아직 만들지 않은 코스가 **빈 틀**인가 — 저장될 장소가 한 곳도 없고 이름도 기본 이름 그대로.
    ///
    /// 새 코스는 「열 때의 모습」이 아니라 이것으로 본다(MZ2AZ-369). 열 때와 견주면 AI 초안을
    /// 손대지 않고 「취소」할 때 묻지 않는데, 그때 사라지는 것은 마법사 다섯 단계의 답과 초안
    /// 전부다. 반대로 일차 수·떠나는 날만 정한 빈 틀은 잃어도 되는 선택이라 세지 않는다.
    static func isBlank(_ course: RouteCourse) -> Bool {
        let body = outgoing(from: course)
        return body.days.allSatisfy(\.items.isEmpty) && body.title == savedTitle("")
    }

    /// 직접 찍은 핀의 분류. 계약은 닫힌 다섯 갈래이고 화면은 한국어 이름을 쓴다.
    ///
    /// **서버에서 다시 읽은 핀은 계약의 값(`food`)으로 온다** — 그것도 받는다. 한국어 이름만 받던 때는
    /// 저장해 둔 코스를 열어 다시 저장하면 핀의 분류가 전부 `building` 으로 바뀌었다(MZ2AZ-372 에서 발견).
    static func pinCategory(from text: String?) -> PinCategory {
        switch PlaceType.pinKind(text) {
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
