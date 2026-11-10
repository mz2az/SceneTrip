import Foundation
import SceneApiClient

// 새 코스 만들기의 순수 규칙 (MZ2AZ-547 P3 — F·Q·R·S·A, 계획 docs/project/plans/ios-web-parity.md).
// 화면은 얇게 두고 판단은 전부 여기서 한다. 웹 기준: `features/proto/{mockPlan.ts, protoState.ts, protoParts.tsx, ProtoPlanFlows.tsx}`.

/// 새 코스를 무엇으로 시작하나 — 새 코스 시트(F1)의 세 줄과 홈 도시 칩.
enum NewCourseStart: Hashable {
    /// 도시로 시작. 도시를 주면(홈 카드의 도시 칩) 「며칠?」(Q2)부터.
    case city(String?)
    /// 작품으로(AI) — A1·A2·A3.
    case works
    /// 담은 곳으로 — A4.
    case saved
}

// MARK: - 도시로 여행 짜기 질문 (Q1–Q3)

/// 도시 → 며칠 → 어떻게 → (알아서 짜기 R | 직접 고르기 S) 의 상태.
struct CityQuestionFlow: Equatable {
    enum Step: Equatable {
        case city, days, how, plan, pick
    }

    /// 「며칠?」 의 고를 거리 — 당일치기 · 1박 2일 · 2박 3일 · 3박 4일.
    static let dayOptions = [1, 2, 3, 4]

    /// 홈 도시 칩으로 들어왔으면 그 도시. 이때 「며칠?」 의 뒤로는 닫기다.
    let startCity: String?
    private(set) var step: Step
    private(set) var city: String?
    private(set) var days: Int?

    init(startCity: String?) {
        self.startCity = startCity
        step = startCity == nil ? .city : .days
        city = startCity
        days = nil
    }

    /// Q1 에서 도시를 골랐다 → Q2.
    mutating func pickCity(_ key: String) {
        city = key
        step = .days
    }

    /// Q2 에서 며칠을 골랐다 → Q3.
    mutating func pickDays(_ count: Int) {
        guard city != nil else { return }
        days = count
        step = .how
    }

    /// Q3 「알아서 짜 주세요」 → R.
    mutating func planForMe() {
        guard step == .how, days != nil else { return }
        step = .plan
    }

    /// Q3 「직접 고를게요」 → S.
    mutating func pickMyself() {
        guard step == .how, days != nil else { return }
        step = .pick
    }

    /// 뒤로. 질문 흐름을 닫아야 하면 true.
    mutating func back() -> Bool {
        switch step {
        case .city:
            return true
        case .days:
            // 홈 도시 칩으로 들어왔으면 도시 질문이 없었다 — 뒤로는 닫기다.
            if startCity != nil {
                return true
            }
            step = .city
        case .how:
            step = .days
        case .plan, .pick:
            step = .how
        }
        return false
    }

    /// 단계 점에 그릴 번호(1…3). 추천 코스·직접 고르기는 점이 없다(nil).
    var stepNumber: Int? {
        switch step {
        case .city: 1
        case .days: 2
        case .how: 3
        case .plan, .pick: nil
        }
    }
}

// MARK: - 추천 코스 (R1–R5) — 앱 안 계산 (웹 `mockPlan.ts`)

enum CityPlanRules {
    /// 하루 몇 곳 — relaxed 3 · normal 5 · packed 7.
    static func stopsPerDay(_ pace: TripPace) -> Int {
        switch pace {
        case .relaxed: 3
        case .normal: 5
        case .packed: 7
        }
    }

    /// 가까운 곳으로 이어 간 순서. 첫 곳은 목록 맨 앞(가장 인기).
    static func chain(_ candidates: [PlaceSummary]) -> [PlaceSummary] {
        guard let first = candidates.first else { return [] }
        var left = Array(candidates.dropFirst())
        var out = [first]
        while !left.isEmpty, let last = out.last {
            var best = 0
            for index in left.indices.dropFirst()
                where RouteGeometry.kilometers(last, left[index]) < RouteGeometry.kilometers(last, left[best])
            {
                best = index
            }
            out.append(left.remove(at: best))
        }
        return out
    }

    /// 인기순 촬영지에서 후보를 `want * 2` 개 잡아 잇고, 앞에서 `want = 하루 수 × 날 수` 개를 날마다 자른다. 빈 날은 뺀다.
    static func plan(places: [PlaceSummary], days: Int, pace: TripPace) -> [[PlaceSummary]] {
        let perDay = stopsPerDay(pace)
        let want = perDay * max(days, 0)
        guard want > 0 else { return [] }
        let picked = Array(chain(Array(places.prefix(want * 2))).prefix(want))
        return (0 ..< days)
            .map { day in Array(picked.dropFirst(day * perDay).prefix(perDay)) }
            .filter { !$0.isEmpty }
    }

    /// 「다른 곳으로 바꾸기」 — `day` 일의 `index` 번째를 `nearby` 가운데 코스에 아직 없는 **첫 곳**으로 바꾼 계획. 없으면 그대로.
    /// `nearby` 는 누를 때 서버에 물은 그 자리 근처 촬영지, 가까운 순(`GET /places?city&lat&lng&sort=distance`, 계획 city-ai-course.md §5).
    static func swapped(_ plan: [[PlaceSummary]], day: Int, index: Int, among nearby: [PlaceSummary]) -> [[PlaceSummary]] {
        guard plan.indices.contains(day), plan[day].indices.contains(index) else { return plan }
        let used = Set(plan.flatMap { $0.map(\.id) })
        guard let next = nearby.first(where: { !used.contains($0.id) }) else { return plan }
        var out = plan
        out[day][index] = next
        return out
    }

    /// `day` 일의 `index` 번째를 뺀 계획(그날이 비어도 날은 남긴다).
    static func removed(_ plan: [[PlaceSummary]], day: Int, index: Int) -> [[PlaceSummary]] {
        guard plan.indices.contains(day), plan[day].indices.contains(index) else { return plan }
        var out = plan
        out[day].remove(at: index)
        return out
    }

    /// 고른 곳을 며칠로 나눈다(직접 고르기 S1) — 하루 `ceil(n / days)` 곳씩 담은 순서대로. 길이는 언제나 `days`.
    static func splitDays<T>(_ items: [T], days: Int) -> [[T]] {
        guard days > 0 else { return [] }
        let per = max(1, Int((Double(items.count) / Double(days)).rounded(.up)))
        return (0 ..< days).map { day in Array(items.dropFirst(day * per).prefix(per)) }
    }

    /// 서버 AI 일정 요청(MZ2AZ-548, 계획 city-ai-course.md §5) — 도시·일수·속도만. 작품은 보내지 않는다.
    static func aiRequest(city: String, days: Int, pace: TripPace) -> GuidePlanRequest {
        GuidePlanRequest(city: city, days: days, pace: GuidePlanRequest.Pace(rawValue: pace.rawValue) ?? .normal)
    }

    /// 서버가 준 일정을 카드로 — 응답만으로 그린다(도시 촬영지 목록 없이, 계획 city-ai-course.md §5). 정지점은 `order` 순,
    /// 번호(`placeId`)나 좌표가 없는 줄은 빼고, 빈 날도 뺀다. 사진·작품은 정지점의 `imageUrl`·`contents`.
    static func fromGuide(_ plan: GuidePlan) -> [[PlaceSummary]] {
        plan.days
            .map { day in
                day.stops.sorted { $0.order < $1.order }.compactMap { stop -> PlaceSummary? in
                    guard let id = stop.placeId, let lat = stop.latitude, let lng = stop.longitude else { return nil }
                    return PlaceSummary(
                        id: id, name: stop.name, address: stop.address, latitude: lat, longitude: lng,
                        imageUrl: stop.imageUrl, contents: stop.contents, sceneDescription: stop.sceneDescription
                    )
                }
            }
            .filter { !$0.isEmpty }
    }

    /// 서버 코스의 속도 — `packed` 만 빡빡하게, 나머지는 널널하게(웹 `saveCourse`).
    static func routePace(_ pace: TripPace) -> RoutePace {
        pace == .packed ? .tight : .loose
    }

    /// 저장할 코스 — 날마다 촬영지를 차례로 넣는다.
    static func course(title: String, plan: [[PlaceSummary]], pace: TripPace, madeByAI: Bool) -> RouteCourse {
        var course = RouteCourse(
            title: title,
            days: plan.map { stops in RouteDay(stops: stops.map { RouteStop(place: $0) }) }
        )
        course.pace = routePace(pace)
        course.madeByAI = madeByAI
        return course
    }
}

// MARK: - 담은 곳으로 (A4) — 웹 `ProtoPlanFlows.tsx` `arrange`·`splitEven`

enum SavedPlanRules {
    /// 시·도 이름을 짧게 — 「서울특별시」→「서울」, 「경상북도」→「경북」. 주소가 없으면 「기타」.
    static let provinces: [String: String] = [
        "경상북도": "경북", "경상남도": "경남", "전라북도": "전북", "전북특별자치도": "전북", "전라남도": "전남",
        "충청북도": "충북", "충청남도": "충남", "강원도": "강원", "강원특별자치도": "강원", "제주특별자치도": "제주",
        "경기도": "경기", "세종특별자치시": "세종",
    ]

    static func regionOf(_ address: String?) -> String {
        guard let head = address?.split(whereSeparator: \.isWhitespace).first.map(String.init), !head.isEmpty else {
            return "기타"
        }
        if let short = provinces[head] {
            return short
        }
        for suffix in ["특별시", "광역시"] where head.hasSuffix(suffix) {
            return String(head.dropLast(suffix.count))
        }
        return head
    }

    /// 시·도 묶음을 큰 것부터, 묶음 안에서는 앞 장소에서 가장 가까운 곳을 차례로. 좌표가 없는 곳(0,0)은 그 묶음 맨 뒤.
    static func arrange(_ places: [PlaceSummary]) -> [PlaceSummary] {
        // 시·도 묶음 — 처음 나온 순서를 기억해 크기가 같으면 먼저 나온 묶음이 앞(웹의 안정 정렬과 같다).
        var order: [String] = []
        var groups: [String: [PlaceSummary]] = [:]
        for place in places {
            let region = regionOf(place.address)
            if groups[region] == nil {
                order.append(region)
            }
            groups[region, default: []].append(place)
        }
        let sorted = order.enumerated()
            .sorted { lhs, rhs in
                let left = groups[lhs.element]?.count ?? 0
                let right = groups[rhs.element]?.count ?? 0
                return left == right ? lhs.offset < rhs.offset : left > right
            }
            .map(\.element)
        var out: [PlaceSummary] = []
        for region in sorted {
            let group = groups[region] ?? []
            var located = group.filter { $0.latitude != 0 || $0.longitude != 0 }
            let rest = group.filter { $0.latitude == 0 && $0.longitude == 0 }
            if !located.isEmpty {
                var at = located.removeFirst()
                out.append(at)
                while !located.isEmpty {
                    let from = at
                    let next = located.indices.min { near(from, located[$0]) < near(from, located[$1]) } ?? 0
                    at = located.remove(at: next)
                    out.append(at)
                }
            }
            out.append(contentsOf: rest)
        }
        return out
    }

    /// 지도 위 거리의 눈대중(위도·경도 차의 제곱합, 경도는 0.8배). 같은 시·도 안에서 순서를 정하는 데만 쓴다.
    private static func near(_ lhs: PlaceSummary, _ rhs: PlaceSummary) -> Double {
        let lat = lhs.latitude - rhs.latitude
        let lng = (lhs.longitude - rhs.longitude) * 0.8
        return lat * lat + lng * lng
    }

    /// 차례대로 날마다 고르게 — 남는 것은 앞날부터 하나씩. 날이 장소보다 많으면 뒷날은 빈다. 길이는 언제나 `days`.
    static func splitEven<T>(_ items: [T], days: Int) -> [[T]] {
        guard days > 0 else { return [] }
        let base = items.count / days
        let extra = items.count % days
        var out: [[T]] = []
        var at = 0
        for day in 0 ..< days {
            let count = base + (day < extra ? 1 : 0)
            out.append(Array(items[at ..< at + count]))
            at += count
        }
        return out
    }

    /// 보관함 → 코스에 넣을 장소. 판매 중단(`delisted`)은 빼고, 좌표가 없으면 (0,0).
    static func places(from items: [CartItem]) -> [PlaceSummary] {
        items.filter { $0.delisted != true }.map { item in
            PlaceSummary(
                id: item.placeId,
                name: item.name,
                address: item.address,
                latitude: item.latitude ?? 0,
                longitude: item.longitude ?? 0,
                imageUrl: item.imageUrl,
                contents: item.sourceContentId.map { [ContentRef(contentId: $0, title: item.sourceContentTitle ?? "")] }
            )
        }
    }
}

// MARK: - 작품으로 (A2·A3)

struct WorkToggle: Equatable {
    let selected: Set<Int64>
    /// 이미 최대만큼 골라서 더 고르지 못했다 — 경고를 띄운다.
    let tooMany: Bool
}

enum WorkPickRules {
    /// 서버(`POST /guide/plan`)가 받는 작품 제목 수.
    static let maxWorks = 5

    /// 찜한 작품을 미리 골라 둔다 — 작품 목록 순서대로 최대 `maxWorks` 개.
    static func preselect(works: [ContentSummary], liked: Set<Int64>) -> Set<Int64> {
        Set(works.filter { liked.contains($0.id) }.prefix(maxWorks).map(\.id))
    }

    /// 포스터를 눌렀다 — 고른 것이면 빼고, 아니면 넣되 이미 `maxWorks` 개면 그대로 두고 `tooMany`.
    static func toggle(_ selected: Set<Int64>, _ id: Int64) -> WorkToggle {
        var next = selected
        if next.remove(id) != nil {
            return WorkToggle(selected: next, tooMany: false)
        }
        guard next.count < maxWorks else { return WorkToggle(selected: selected, tooMany: true) }
        next.insert(id)
        return WorkToggle(selected: next, tooMany: false)
    }
}

/// 짜는 중 화면의 세 줄(A3) — 시간이 지나면 앞 줄부터 체크(0 · 3.5 · 8초).
enum PlanningStages {
    static let starts: [Double] = [0, 3.5, 8]

    /// 지금 돌고 있는 줄의 번호(0…2).
    static func current(elapsed: Double) -> Int {
        starts.lastIndex { elapsed >= $0 } ?? 0
    }
}

// MARK: - 정한 여행 (Q4) — `/me/trip`

/// 정한 여행 — 도시·며칠·속도·만든 코스. 기기에 기억하고 서버(`/me/trip`)와 맞춘다.
struct TripMemo: Codable, Equatable {
    var city: String
    var days: Int
    var pace: TripPace
    var courseId: Int64?
}

/// 서버에 물어본 결과.
enum ServerTrip: Equatable {
    case found(TripMemo)
    /// 404 — 서버에 없다.
    case missing
    /// 묻지 못했다(망·5xx).
    case failed
}

struct TripReconcile: Equatable {
    /// 화면이 쓸 여행.
    let use: TripMemo?
    /// 기기 것을 서버에 올려야 하는가.
    let push: Bool
}

enum TripMemoryRules {
    /// 서버가 정본이다 — 있으면 그것. 서버에 없고 기기에만 있으면 기기 것을 쓰고 올린다. 묻지 못했으면 기기 것 그대로, 올리지 않는다.
    static func reconcile(local: TripMemo?, server: ServerTrip) -> TripReconcile {
        switch server {
        case let .found(trip): TripReconcile(use: trip, push: false)
        case .missing: TripReconcile(use: local, push: local != nil)
        case .failed: TripReconcile(use: local, push: false)
        }
    }
}

// MARK: - 문구

enum NewCourseText {
    /// 「당일치기」·「1박 2일」.
    static func days(_ count: Int) -> String {
        count <= 1 ? tr("당일치기") : String(format: tr("%d박 %d일"), count - 1, count)
    }

    /// R2 제목 — 「부산 1박 2일 추천 코스」.
    static func planTitle(city: String, days: Int) -> String {
        String(format: tr("%@ %@ 추천 코스"), city, Self.days(days))
    }

    /// R5 저장 제목 — 「부산 1박 2일」.
    static func courseTitle(city: String, days: Int) -> String {
        String(format: tr("%@ %@", at: "도시 코스 제목"), city, Self.days(days))
    }

    /// S1 저장 제목 — 「부산 여행」.
    static func pickedTitle(city: String) -> String {
        String(format: tr("%@ 여행"), city)
    }

    /// R6 안내 띠.
    static func createdBanner(city: String, days: Int) -> String {
        String(format: tr("%@ %@ 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요"), city, Self.days(days))
    }

    /// F1 ③ 「담은 곳으로」 설명 — 담은 곳이 있으면 「담아 둔 N곳을 날마다 나눠 넣어요」, 없으면 「빈 코스에서 하나씩 넣어요」.
    static func savedCaption(count: Int) -> String {
        count > 0 ? String(format: tr("담아 둔 %lld곳을 날마다 나눠 넣어요"), count) : tr("빈 코스에서 하나씩 넣어요")
    }

    /// R6 안내 띠가 떠 있는 시간(초).
    static let bannerSeconds: Double = 6
}
