import Foundation
import SceneApiClient

// 경로여정(코스) 탭이 쓰는 값 타입.
//
// **서버 계약이 아직 없다.** `contracts/openapi/scene-api-v1.yaml` 에 경로 관련
// 스키마가 하나도 없고 백엔드가 따로 만드는 중이다(8/11 회의 2-2). 그래서 여기 있는
// 타입은 **화면이 쓰는 로컬 모형**이지 계약이 아니다 — 계약이 서면 이 파일은 생성
// 모델로 갈린다. 프론트가 계약을 앞질러 만들면 두 벌이 되고, 어느 쪽이 정본인지
// 아무도 모르게 된다.
//
// 장소만은 검색 탭과 **같은 `PlaceSummary`** 를 그대로 쓴다. 코스에 담기는 장소는
// 장바구니에서 이어지는 그 장소이고, 지도 핀·행 표시도 검색 탭과 같은 부품
// (`PlaceRow`·`PinImage`)을 쓰기 때문이다. 타입을 새로 만들면 그 부품을 못 쓴다.

// MARK: - 코스

struct RouteCourse: Identifiable, Hashable {
    let id = UUID()

    /// 서버가 준 코스 id. **아직 저장 전이면 nil 이다.**
    ///
    /// `id`(UUID)는 SwiftUI 가 목록을 그리는 데 쓰고, 이것은 서버와 말할 때 쓴다.
    /// 둘을 하나로 합칠 수 없다 — 저장 전에는 서버 id 가 없고, SwiftUI 는 그때도
    /// 이 코스를 목록에서 가려낼 수 있어야 한다.
    var serverId: Int64?

    var title: String

    /// 떠나는 날. **비어 있어도 된다.**
    ///
    /// 8/11 회의 확정: *"날짜를 안 넣어도 다음으로 넘어갈 수 있게"*. 날짜를 아예
    /// 없애지 않은 이유도 회의에 있다 — 요일에 따라 문을 닫는 곳이 있어서다.
    /// 돌아오는 날은 **묻지 않는다**. 일차 수에서 따라 나온다(`endDate`).
    var startDate: Date?

    /// 「빡빡하게 / 널널하게」. **일정을 바꾸지 않는다** — 아래 `RoutePace` 주석 참고.
    var pace: RoutePace = .tight

    var days: [RouteDay]

    /// AI 가 짠 초안인가. 편집 화면 맨 위의 파란 띠를 띄울지 정한다 — 회의 확정
    /// *"AI로 초안을 만들고 바로 저장시키는 게 아니고, 거기서 수정할 수 있게"*.
    var madeByAI = false

    /// 「코스 시작」 을 눌러 여행 중인가. 여행 중에만 「여기서 길 찾기」가 나온다.
    var isRunning = false

    /// 고른 작품에 촬영지가 없어 **인기 장소로 대신 채웠나.**
    ///
    /// 서버 데이터가 작품마다 고르지 않다 — 도깨비는 57곳인데 어떤 작품은 0곳이다.
    /// 그때 빈 코스를 내놓는 대신 인기 장소로 채우되, 사용자가 모르고 지나가지
    /// 않게 편집 화면이 알린다. **저장하면 사라지는 값이다** (서버에 없다).
    var filledFromPopular = false

    /// 일차를 하루보다 적게, 15일보다 많게 만들지 않는다. 목업의 ＋/− 잠금과 같은 값.
    static let dayLimit = 1 ... 15

    /// 서버가 세어 준 장소 수. **목록 카드에서만 쓴다.**
    ///
    /// 목록은 일차 속을 받지 않으므로(`CourseSummary`) `stops` 가 비어 있다. 카드에
    /// 「7곳」을 그리려고 상세를 부르면 코스 수만큼 호출이 는다.
    var placeCountFromServer: Int?

    var stops: [RouteStop] {
        days.flatMap(\.stops)
    }

    /// 카드에 적을 장소 수. 상세를 받았으면 실제 개수, 목록만 받았으면 서버 값이다.
    var placeCount: Int {
        stops.isEmpty ? (placeCountFromServer ?? 0) : stops.count
    }

    /// 「당일치기」·「1박 2일」. **몇 밤을 자는지**로 말한다 — "여행 일수 2일" 이라고
    /// 쓰면 사용자가 밤 수를 다시 세어야 한다(목업 설계 메모).
    var spanLabel: String {
        RouteSpan(days: days.count).label
    }

    /// 돌아오는 날 = 떠나는 날 + (일차 − 1). 사용자에게 묻지 않고 계산한다.
    var endDate: Date? {
        guard let startDate else { return nil }
        return Calendar.current.date(byAdding: .day, value: days.count - 1, to: startDate)
    }

    /// 「8월 22일 (금) – 8월 24일 (일)」. 날짜를 안 정했으면 nil 이고, 그 자리에는
    /// 기간(`spanLabel`)만 남는다.
    var dateLabel: String? {
        guard let startDate, let endDate else { return nil }
        let text = RouteFormat.day(startDate)
        return days.count == 1 ? text : "\(text) – \(RouteFormat.day(endDate))"
    }

    func date(ofDay index: Int) -> Date? {
        guard let startDate else { return nil }
        return Calendar.current.date(byAdding: .day, value: index, to: startDate)
    }
}

struct RouteDay: Identifiable, Hashable {
    let id = UUID()
    var stops: [RouteStop] = []
}

/// 코스에 담긴 장소 하나.
///
/// `PlaceSummary` 를 그대로 안고 체류 시간만 얹는다. 같은 장소를 서로 다른 일차에
/// 두 번 담을 수 있으므로 **장소 id 가 아니라 자기 id 로 구분**한다.
struct RouteStop: Identifiable, Hashable {
    let id = UUID()
    var place: PlaceSummary

    /// 서버가 준 아이템 id. **편집 완료에서 이 값을 그대로 돌려보내야 한다** —
    /// 빠뜨리면 서버가 새 장소로 보아 방문 체크(`visitedAt`)가 날아간다.
    /// 아직 저장 전인 장소는 nil 이고, 저장하면 서버가 붙여 준다.
    var serverItemId: Int64?

    /// 체류 시간. 기본 30분은 8/11 회의에서 확정된 값이다(*"30분만 하고 시작 시간은 뺐다"*).
    var stayMinutes: Int = RouteStop.defaultStayMinutes

    /// 지도를 눌러 직접 찍은 핀인가. 숙소처럼 우리 데이터에 없는 곳이다.
    var isPinned = false

    static let defaultStayMinutes = 30
    static let stayOptions = [15, 30, 45, 60, 90, 120, 180]

    var stayLabel: String {
        RouteFormat.minutes(stayMinutes)
    }
}

// MARK: - 질문 흐름의 답

/// 기간. **당일치기부터 5박 6일까지** 여섯 개다 (8/11 회의 확정, 트리플 참고).
///
/// 당일치기를 넣은 이유가 따로 있다 — *"본인 여행이 15일을 왔든 그날 하루만 이
/// 앱에서 코스를 짜서 갈 수 있잖아요"*. 더 긴 여정은 만든 뒤 일차 ＋ 로 늘린다.
enum RouteSpan: Int, CaseIterable, Identifiable {
    case sameDay = 0
    case oneNight, twoNights, threeNights, fourNights, fiveNights

    var id: Int {
        rawValue
    }

    var nights: Int {
        rawValue
    }

    var days: Int {
        rawValue + 1
    }

    var label: String {
        nights == 0 ? "당일치기" : "\(nights)박 \(days)일"
    }

    /// 일차 ＋/− 로 6일을 넘긴 코스도 있으므로 목록에 없는 값이 들어올 수 있다.
    init(days: Int) {
        self = RouteSpan(rawValue: max(0, days - 1)) ?? .fiveNights
    }
}

/// 「빡빡하게 / 널널하게」.
///
/// **UI 만 있고 로직은 없다.** 8/11 회의에서 항목을 넣는 것은 확정됐지만 이 답이
/// 일정을 어떻게 바꾸는지는 정하지 않았다(회의록 5장 #3 — *"로직은 아직 안 넣었다"*).
/// 없는 로직을 여기서 지어내면 팀이 정하지 않은 것이 구현으로 굳는다. 그래서 값만
/// 들고 다니고 초안 생성에는 쓰지 않으며, 화면에도 그 사실을 적어 둔다.
enum RoutePace: String, CaseIterable, Identifiable {
    case tight = "빡빡하게"
    case loose = "널널하게"

    var id: String {
        rawValue
    }

    var caption: String {
        switch self {
        case .tight: "하루를 알차게 채웁니다"
        case .loose: "여유 있게 돌아봅니다"
        }
    }

    var symbol: String {
        switch self {
        case .tight: "bolt.fill"
        case .loose: "leaf.fill"
        }
    }
}

// MARK: - 직선거리

/// 좌표만으로 거리를 재고 순서를 다시 잡는다.
///
/// **길찾기 API 를 부르지 않는다.** 8/11 회의 2부 확정: 여행 전 계획에서는 직선거리만
/// 쓰고, 실제 길찾기는 여행 중에만 부른다 — 동선 최적화를 누를 때마다 API 를 부르면
/// 비용이 감당되지 않는다(T맵 종량제 회당 11.88원).
///
/// 같은 이유로 **예상 소요 시간을 만들지 않는다.** 직선거리에서 시간을 지어내면
/// 사용자는 그것을 실제 이동 시간으로 읽는다.
enum RouteGeometry {
    /// 하버사인. 지구 반지름 6371km.
    static func kilometers(_ start: PlaceSummary, _ end: PlaceSummary) -> Double {
        let radius = 6371.0
        let dLat = (end.latitude - start.latitude) * .pi / 180
        let dLng = (end.longitude - start.longitude) * .pi / 180
        let lat1 = start.latitude * .pi / 180
        let lat2 = end.latitude * .pi / 180
        let haversine = sin(dLat / 2) * sin(dLat / 2)
            + sin(dLng / 2) * sin(dLng / 2) * cos(lat1) * cos(lat2)
        return 2 * radius * asin(min(1, sqrt(haversine)))
    }

    /// 순서대로 이었을 때의 총 직선거리(km).
    static func totalKilometers(_ stops: [RouteStop]) -> Double {
        guard stops.count > 1 else { return 0 }
        return zip(stops, stops.dropFirst())
            .reduce(0) { $0 + kilometers($1.0.place, $1.1.place) }
    }

    /// 동선 최적화 — **세 방법을 다 돌려 가장 짧은 것을 고른다** (MZ2AZ-247).
    ///
    /// **API 를 부르지 않는다.** 위경도로 직선거리만 재는 순수 계산이라 호출이 0회다 —
    /// 계획 단계가 직선거리만 쓴다는 규칙(계획서 11-1절)과 어긋나지 않고, 회의가
    /// 동선 최적화를 뺐던 이유(*"누를 때마다 API 호출이 되는 거 아니에요?"*)에도
    /// 걸리지 않는다(11-5절).
    ///
    /// ## 출발·도착 고정은 **따로, 그리고 선택이다**
    ///
    /// 앞서 첫 장소를 늘 고정했다. 「맨 위에 둔 곳은 대개 숙소·역이니 출발지일 것」이라는
    /// 짐작이었는데, 그것이 맞지 않는 사람이 있다 — **현재 위치에서 출발하는 사람**은
    /// 목록 첫 줄을 출발지로 정해 둔 적이 없고, 그 사람에게 첫 줄 고정은 최적화를
    /// 그만큼 나쁘게 만들 뿐이다(2026-08-24 사용자 지적).
    ///
    /// 그래서 넷을 다 만든다.
    ///
    /// | `pinStart` | `pinEnd` | 뜻 |
    /// | --- | --- | --- |
    /// | 켬 | 끔 | 숙소에서 나와 아무 데서나 끝낸다 |
    /// | 끔 | 켬 | 아무 데서나 시작해 숙소로 돌아온다 |
    /// | 켬 | 켬 | 양끝이 정해져 있다 |
    /// | 끔 | 끔 | 통째로 자유 — 현재 위치에서 출발하는 사람 |
    ///
    /// ## 왜 최근접 이웃 하나로 두지 않았나
    ///
    /// 처음에는 그것만 있었다. 팀 목업도 그 방식이다. 그런데 최근접 이웃은 **뒤로 갈수록
    /// 손해가 쌓인다** — 가까운 것부터 집다 보면 마지막에 멀리 떨어진 하나가 남아 되돌아
    /// 가야 한다. 웹 프로토타입에서 세 방법을 나란히 재 보고 이 자리를 올렸다.
    ///
    /// 셋을 다 돌려도 사람이 기다릴 일이 없다. 가운데가 8개를 넘으면 완전탐색만
    /// 건너뛴다 — 9개면 순열이 36만 가지가 된다.
    static func optimized(
        _ stops: [RouteStop],
        pinStart: Bool = true,
        pinEnd: Bool = false
    ) -> [RouteStop] {
        guard stops.count > 2 else { return stops }

        let matrix = distanceMatrix(stops)
        let head = pinStart ? 0 : nil
        let tail = pinEnd ? stops.count - 1 : nil

        // 양끝을 다 고정했는데 가운데가 없으면 바꿀 것이 없다.
        let middle = stops.indices.filter { $0 != head && $0 != tail }
        guard middle.count > 1 else { return stops }

        var best = orderNearest(matrix, middle: middle, head: head, tail: tail)
        var bestCost = cost(matrix, best)

        let twoOpt = orderTwoOpt(matrix, from: best, head: head, tail: tail)
        if cost(matrix, twoOpt) < bestCost {
            best = twoOpt
            bestCost = cost(matrix, twoOpt)
        }
        if let exact = orderExact(matrix, middle: middle, head: head, tail: tail),
           cost(matrix, exact) < bestCost
        {
            best = exact
        }
        return best.map { stops[$0] }
    }

    /// 직선거리 행렬(km). 같은 쌍을 여러 번 재지 않으려고 한 번만 만든다.
    private static func distanceMatrix(_ stops: [RouteStop]) -> [[Double]] {
        stops.indices.map { row in
            stops.indices.map { column in
                row == column ? 0 : kilometers(stops[row].place, stops[column].place)
            }
        }
    }

    private static func cost(_ matrix: [[Double]], _ order: [Int]) -> Double {
        guard order.count > 1 else { return 0 }
        return zip(order, order.dropFirst()).reduce(0) { $0 + matrix[$1.0][$1.1] }
    }

    /// 고정된 것을 앞뒤에 붙여 완성된 순서로 만든다.
    private static func assemble(_ middle: [Int], head: Int?, tail: Int?) -> [Int] {
        (head.map { [$0] } ?? []) + middle + (tail.map { [$0] } ?? [])
    }

    /// 최근접 이웃. 가까운 것부터 집는다. 빠르지만 최적해는 아니다.
    ///
    /// **출발을 고정하지 않으면 어디서 시작하느냐가 결과를 바꾼다.** 그래서 가운데의
    /// 모든 지점을 한 번씩 출발로 삼아 보고 가장 짧은 것을 남긴다 — 가운데가 많아야
    /// 수십 개라 값이 싸다.
    private static func orderNearest(
        _ matrix: [[Double]],
        middle: [Int],
        head: Int?,
        tail: Int?
    ) -> [Int] {
        func walk(from first: Int) -> [Int] {
            var current = first
            var remaining = middle.filter { $0 != first }
            var order = [first]
            while !remaining.isEmpty {
                let anchor = current
                let index = remaining.indices.min {
                    matrix[anchor][remaining[$0]] < matrix[anchor][remaining[$1]]
                }!
                current = remaining.remove(at: index)
                order.append(current)
            }
            return order
        }

        if let head {
            // 고정된 출발에서 가장 가까운 것부터 이어 간다.
            var current = head
            var remaining = middle
            var order: [Int] = []
            while !remaining.isEmpty {
                let anchor = current
                let index = remaining.indices.min {
                    matrix[anchor][remaining[$0]] < matrix[anchor][remaining[$1]]
                }!
                current = remaining.remove(at: index)
                order.append(current)
            }
            return assemble(order, head: head, tail: tail)
        }

        var best: [Int]?
        var bestCost = Double.infinity
        for first in middle {
            let candidate = assemble(walk(from: first), head: nil, tail: tail)
            let value = cost(matrix, candidate)
            if value < bestCost {
                best = candidate
                bestCost = value
            }
        }
        return best ?? assemble(middle, head: head, tail: tail)
    }

    /// 2-opt. 선이 **꼬인 곳**을 찾아 그 구간을 뒤집는다. 나아지지 않을 때까지 돈다.
    ///
    /// 최근접 이웃이 만든 순서를 다듬는 용도다 — 맨바닥에서 시작하는 것보다 훨씬 빨리
    /// 좋은 답에 닿는다. **고정된 양끝은 뒤집기 범위에서 뺀다.**
    private static func orderTwoOpt(
        _ matrix: [[Double]],
        from seed: [Int],
        head: Int?,
        tail: Int?,
        rounds: Int = 60
    ) -> [Int] {
        var best = seed
        let lower = head == nil ? 0 : 1
        let upper = best.count - 1 - (tail == nil ? 0 : 1)
        guard upper > lower else { return best }
        for _ in 0 ..< rounds {
            var moved = false
            for start in lower ..< upper {
                for end in (start + 1) ... upper {
                    var candidate = best
                    candidate[start ... end].reverse()
                    if cost(matrix, candidate) < cost(matrix, best) - 1e-9 {
                        best = candidate
                        moved = true
                    }
                }
            }
            if !moved {
                break
            }
        }
        return best
    }

    /// 모든 경우의 수. 가운데가 8개까지만 — 9개면 362,880 가지가 되어 화면이 멈춘다.
    private static func orderExact(
        _ matrix: [[Double]],
        middle: [Int],
        head: Int?,
        tail: Int?
    ) -> [Int]? {
        guard middle.count <= 8 else { return nil }
        var best: [Int]?
        var bestCost = Double.infinity
        permutations(middle) { permutation in
            let order = assemble(permutation, head: head, tail: tail)
            let value = cost(matrix, order)
            if value < bestCost {
                best = order
                bestCost = value
            }
        }
        return best
    }

    /// 순열을 하나씩 흘려 준다. 전부 배열로 모으면 8개에서 40,320개가 메모리에 쌓인다.
    private static func permutations(_ items: [Int], _ body: ([Int]) -> Void) {
        var items = items
        func recurse(_ start: Int) {
            if start == items.count {
                body(items)
                return
            }
            for index in start ..< items.count {
                items.swapAt(start, index)
                recurse(start + 1)
                items.swapAt(start, index)
            }
        }
        recurse(0)
    }
}

/// 같은 곳을 두 번 담지 못하게 거른다 (2026-08-27).
///
/// ## 왜 id 만으로는 안 되나
///
/// 촬영지는 서버 id 가 있어 그것으로 가른다. 그런데 **직접 찍은 핀과 가이드가 준
/// 곳은 id 가 시각으로 매겨진다**(`RouteMock.pinnedPlace`) — 같은 가게라도 담을
/// 때마다 값이 다르다. 실제로 「달큰커피」가 한 일차에 3번·4번으로 나란히
/// 들어갔다(사용자 지적).
///
/// 그래서 **이름 + 좌표**로도 본다. 좌표는 소수 다섯 자리(약 1 m)까지만 본다 —
/// 같은 가게를 두 번 받았을 때 끝자리가 미세하게 다를 수 있다.
///
/// 이름이 같아도 자리가 다르면 다른 곳이다 — 프랜차이즈 지점이 그렇다.
enum RouteDedupe {
    /// 한 곳을 가리키는 열쇠.
    static func key(_ place: PlaceSummary) -> String {
        String(format: "%@|%.5f|%.5f", place.name, place.latitude, place.longitude)
    }

    /// 이미 담긴 것과 겹치지 않는 것만 남긴다.
    ///
    /// **이번에 담는 것끼리도 본다** — 한 번에 같은 곳을 두 개 고르면 그것도 하나여야 한다.
    static func fresh(
        _ places: [PlaceSummary],
        takenIds: Set<Int64>,
        takenKeys: Set<String>
    ) -> [PlaceSummary] {
        var keys = takenKeys
        var out: [PlaceSummary] = []
        for place in places {
            if place.id > 0, takenIds.contains(place.id) {
                continue
            }
            let key = key(place)
            if keys.contains(key) {
                continue
            }
            keys.insert(key)
            out.append(place)
        }
        return out
    }
}

// MARK: - 표기

enum RouteFormat {
    /// 「8월 22일 (금)」. 요일까지 적는 이유는 회의에 있다 — 주말에 문을 닫는 촬영지가 있다.
    static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M월 d일 (E)"
        return formatter.string(from: date)
    }

    /// 「30분」·「1시간 30분」. 분으로만 적으면 90분이 얼마인지 사용자가 환산해야 한다.
    static func minutes(_ total: Int) -> String {
        let hours = total / 60
        let rest = total % 60
        switch (hours, rest) {
        case (0, _): return "\(rest)분"
        case (_, 0): return "\(hours)시간"
        default: return "\(hours)시간 \(rest)분"
        }
    }

    /// 「1.2 km」. 소수점 한 자리면 충분하다 — 직선거리는 어차피 어림값이다.
    static func kilometers(_ value: Double) -> String {
        String(format: "%.1f km", value)
    }
}
