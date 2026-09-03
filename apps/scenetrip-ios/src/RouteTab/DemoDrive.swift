import Foundation
import SceneApiClient

/// **데모 주행** — 중간발표 영상용 가상 GPS (계획 trip-mode.md §7, 2026-09-02).
///
/// 영상에는 「걸어가는 GPS」가 필요한데 시뮬레이터도 실기기도 그것을 찍을 방법이 없다.
/// 그래서 실행 인자로만 켜지는 가상 위치를 둔다 — 길찾기 화면이 위치 서비스 대신 이것이
/// 내는 좌표를 받고, 도착 판정·스탬프·다음 성지는 **실제 규칙 그대로** 돈다.
/// `isOn` 이 아니면 아무 것도 하지 않는다. 실제 사용자에게는 존재하지 않는 기능이다.
///
/// 켜는 법: `just ios-demo [until] [dwell]` →
/// `-demoCourse 1 -navStop 1 -demoDrive 3 -tripDwellSeconds 5 -footprintOn 1`.
enum DemoDrive {
    typealias Point = (latitude: Double, longitude: Double)

    /// 몇 번 성지까지 가는가. 0 이면 꺼짐.
    static var untilStop: Int {
        UserDefaults.standard.integer(forKey: "demoDrive")
    }

    static var isOn: Bool {
        untilStop > 0
    }

    /// 초당 몇 m 움직이는가. 기본 12 — 도보의 열 배. 영상 길이 때문이다(1→3번이 약 2분).
    static var metersPerSecond: Double {
        let raw = UserDefaults.standard.double(forKey: "demoSpeed")
        return raw > 0 ? raw : 12
    }

    /// 한 걸음의 간격(초). 0.4초면 지도의 파문이 끊기지 않고 움직인다.
    static let tick: TimeInterval = 0.4

    /// 이 반경(m) 안에 들면 걷기를 멈추고 머무름(스탬프)을 기다린다. 도착 판정 반경(100 m)
    /// 보다 안쪽이라 판정이 확실히 걸린다.
    static let stopWithinMeters = 30.0

    /// 데모 코스(`resources/demo/demo-course.json`)를 서버에 만들어 열 것인가.
    static var wantsDemoCourse: Bool {
        UserDefaults.standard.bool(forKey: "demoCourse")
    }

    /// 출발점 — 첫 성지에서 남쪽으로 약 250 m. 영상이 「걸어오는 것」으로 시작하게.
    static func start(near stop: RouteStop) -> Point {
        (stop.place.latitude - 0.00225, stop.place.longitude - 0.0006)
    }

    static func meters(_ from: Point, _ to: Point) -> Double {
        RouteGeometry.kilometers(
            PlaceSummary(id: 0, name: "", latitude: from.latitude, longitude: from.longitude),
            PlaceSummary(id: 0, name: "", latitude: to.latitude, longitude: to.longitude)
        ) * 1000
    }

    /// `from` 에서 `to` 쪽으로 `meters` 만큼. 남은 거리가 그보다 짧으면 `to` 에 선다.
    static func moved(_ from: Point, toward to: Point, meters: Double) -> Point {
        let total = self.meters(from, to)
        guard total > meters, total > 0 else { return to }
        let ratio = meters / total
        return (
            from.latitude + (to.latitude - from.latitude) * ratio,
            from.longitude + (to.longitude - from.longitude) * ratio
        )
    }

    /// 경로선(`legs.path`, [경도, 위도] 순)을 따라 한 걸음. `index` 는 지금 지나고 있는 꼭짓점.
    /// 경로선이 없거나 다 지났으면 목적지로 직선.
    static func step(
        from position: Point, along path: [Point], index: inout Int,
        toward target: Point, meters budget: Double
    ) -> Point {
        var position = position
        var left = budget
        while left > 0, index + 1 < path.count {
            let next = path[index + 1]
            let gap = meters(position, next)
            if gap <= left {
                position = next
                left -= gap
                index += 1
            } else {
                position = moved(position, toward: next, meters: left)
                left = 0
            }
        }
        if left > 0 {
            position = moved(position, toward: target, meters: left)
        }
        return position
    }
}

/// 저장소에 든 데모 코스(`resources/demo/demo-course.json`) — 팀원 누구의 맥에서도 같은
/// 영상이 나오게 코스 하나를 함께 둔다. 장소 id 는 `seed/candidates.csv`(v3) 의 것이다.
enum DemoCourse {
    struct File: Decodable {
        struct Stop: Decodable {
            let placeId: Int64?
            let name: String
            let category: String?
            let address: String?
            let latitude: Double
            let longitude: Double
            let stayMinutes: Int
        }

        let title: String
        let pace: String?
        let days: [[Stop]]
    }

    static func load() -> File? {
        let bundle = Bundle.main
        let url = bundle.url(forResource: "demo-course", withExtension: "json")
            ?? bundle.url(forResource: "demo-course", withExtension: "json", subdirectory: "resources/demo")
            ?? bundle.url(forResource: "demo-course", withExtension: "json", subdirectory: "demo")
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(File.self, from: data)
    }

    /// 파일 → 저장 전 코스. `RouteStore.save` 가 서버에 만든다.
    static func course(from file: File) -> RouteCourse {
        RouteCourse(
            title: file.title,
            pace: file.pace == "loose" ? .loose : .tight,
            days: file.days.map { stops in
                RouteDay(stops: stops.map { stop in
                    RouteStop(
                        place: PlaceSummary(
                            id: stop.placeId ?? 0, name: stop.name, type: stop.category,
                            address: stop.address, latitude: stop.latitude, longitude: stop.longitude
                        ),
                        stayMinutes: stop.stayMinutes,
                        isPinned: stop.placeId == nil
                    )
                })
            }
        )
    }
}
