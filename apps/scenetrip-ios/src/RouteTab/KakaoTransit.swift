import CoreLocation
import Foundation

/// 카카오 길찾기 — 대중교통과 도보 **둘 다** (MZ2AZ-233).
///
/// ## 왜 앱이 직접 부르나
///
/// 원래 자리는 백엔드(`POST /navigation/next-leg`)다. 그런데 그 엔드포인트가 아직
/// 만들어지지 않았다(계약은 있으나 서버는 `404` — 실측). MVP1 데모를 굴리려면 앱이
/// 부르는 수밖에 없다.
///
/// **스토어에 내기 전에 백엔드로 옮긴다.** 앱에 박힌 키는 뽑아낼 수 있고, 그러면 남이
/// 우리 쿼터와 요금을 쓴다. 그때 이 파일은 통째로 사라지고 화면은 그대로 남는다 —
/// `RouteNavResult` 를 채우는 쪽만 바뀐다.
///
/// ## 도보도 카카오다 — T맵을 걷어냈다 (2026-08-24)
///
/// 전에는 도보를 T맵(`TmapWalk.swift`)이 맡았다. 대중교통과 도보가 회사가 갈리면 앱이
/// 키 두 벌을 관리해야 하고, 백엔드가 이 자리를 가져갈 때도 두 벌을 옮겨야 한다.
///
/// **실측으로 갈아 끼워도 되는지 먼저 확인했다.** 같은 구간(북촌→삼청동, 약 580 m)을
/// 카카오 도보와 T맵 도보에 나란히 물었다 —
///
/// | | 카카오 도보 | T맵 도보 |
/// | --- | --- | --- |
/// | 거리 | 581 m | 520 m |
/// | 시간 | 618초 (오르막 반영) | 444초 (평지 속도만) |
/// | 좌표 | 34개 | 51개 |
/// | 계단 | 모름 | **2구간 발견** |
///
/// 같은 POI(뚝섬역)를 두 서비스에 물었을 때 좌표 차이는 5.5 m — 정확도는 사실상 같다.
/// 150 m 짜리 짧은 구간에서도 정상 응답이었다. **잃는 것은 계단 정보뿐이다** — T맵만
/// `facilityType 17` 로 계단을 알려준다. 프로토타입(`SceneTrip_navi`) 실측: 후보 73개
/// 중 11개(15%)에 계단이 있었고 그중 1/5은 최단 경로 순위가 바뀌었다. 그 트레이드오프를
/// 알고 카카오로 간다 — 무릎·캐리어 사용자를 위한 되돌리기는 남겨 둔다(아래 `WalkEngine`).
///
/// ## 문서에 없는 엔드포인트다
///
/// `dapi.kakao.com/v2/routing/publictraffic` 과 `.../v2/routing/walk` 둘 다 카카오 공식
/// 문서에 없다. 카카오맵 웹이 쓰는 것을 REST 키로 부르는 것이고, 프로토타입이 이것으로
/// 돌고 있다. **예고 없이 막힐 수 있다** — 그래서 실패를 정상 경로로 다룬다.
///
/// ## 한 호출에 15개가 온다
///
/// 대중교통은 경로 후보를 최대 15개 주고 **도보 구간 좌표까지 함께** 준다. ODsay 조합이
/// 구간당 22회였던 것과 비교된다(프로토타입 실측).
enum KakaoTransit {
    /// 이보다 가까우면 대중교통을 묻지 않는다.
    ///
    /// **카카오가 150 m 짜리에 `NO_RESULTS` 를 준다**(프로토타입 실측). 그것을 그대로
    /// 옮기면 걸어서 3분인 곳이 「갈 수 없는 곳」으로 보인다. 가까우면 걷는 것이 답이다.
    static let walkThresholdMeters = 900.0

    enum Failure: Error {
        /// 키가 안 들어왔다. `.env` 에 `KAKAO_REST_KEY` 가 없으면 이렇다.
        case noKey
        /// 카카오가 경로를 못 찾았다. 섬·산속처럼 대중교통이 닿지 않는 곳에서 난다.
        case noRoute
        /// 그 밖. 문서에 없는 엔드포인트라 막혔을 수도 있다.
        case transport
    }

    /// 현재 위치에서 목적지까지 한 구간.
    ///
    /// 가까우면 대중교통을 아예 묻지 않고 걷는 것으로 답한다.
    static func leg(
        from origin: CLLocationCoordinate2D,
        to target: CLLocationCoordinate2D,
        destinationName: String,
        language: String = "ko"
    ) async throws -> RouteNavResult {
        let straight = distanceMeters(origin, target)
        if straight < walkThresholdMeters {
            // 가까우면 대중교통을 안 묻고 **카카오 보행자 경로**를 받는다. 직선을 긋는
            // 대신 실제 골목을 따라가고, 턴바이턴 안내문(「…까지 오른쪽길로 95m」)이
            // 함께 온다. 계단 정보는 없다 — 클래스 머리말 참고.
            if let walk = try? await fetchWalk(from: origin, to: target, destinationName: destinationName) {
                return walk
            }
            return walkOnly(meters: straight, destinationName: destinationName)
        }

        let routes = try await fetch(origin: origin, target: target, language: language)
        guard let best = routes.first else { throw Failure.noRoute }
        return await stitched(best, origin: origin, target: target, destinationName: destinationName)
    }

    // MARK: 양 끝 도보를 메운다

    /// 카카오가 **도보 구간을 안 줄 때 우리가 만들어 붙인다.**
    ///
    /// 실측(2026-08-24, 북촌 → 찰스H, 직선 1.4 km): 카카오가 후보 15개를 줬는데
    /// **1위가 마을버스 한 구간뿐이고 양 끝 도보가 통째로 없었다.** 프로토타입도 같은
    /// 것을 쟀다 — 여러 구간을 모아 보니 **후보 75개 중 35개(47%)가 도보 구간 없이
    /// 온다.** 버스 전용 경로가 특히 그렇다.
    ///
    /// 그대로 두면 화면이 둘 다 틀린다 —
    ///
    /// - 「도보 정보 없음」이라 얼마나 걷는지 모른다
    /// - **지도의 선이 끊긴다.** 출발지에서 정류장까지, 정류장에서 목적지까지가 비어
    ///   버스 구간만 공중에 떠 있다
    ///
    /// 차량 구간의 **첫 좌표가 승차점, 끝 좌표가 하차점**이다. 그 둘을 기준으로
    /// 출발지→승차 · 하차→목적지 두 조각을 카카오 도보로 실측해 앞뒤에 끼운다.
    private static func stitched(
        _ route: Route,
        origin: CLLocationCoordinate2D,
        target: CLLocationCoordinate2D,
        destinationName: String
    ) async -> RouteNavResult {
        var built = result(from: route, destinationName: destinationName)

        // 이미 도보가 들어 있으면 손대지 않는다.
        guard built.walkMeters == nil else { return built }

        // 차량 구간의 좌표에서 승차·하차 지점을 집는다.
        let vehiclePaths = (route.steps ?? [])
            .filter { ($0.properties?.type ?? "") != "WALKING" }
            .compactMap { $0.path?.points }
            .filter { $0.count > 1 }
        guard let boarding = vehiclePaths.first?.first,
              let alighting = vehiclePaths.last?.last,
              boarding.count == 2, alighting.count == 2
        else { return built }

        // 좌표는 [경도, 위도] 로 온다.
        let board = CLLocationCoordinate2D(latitude: boarding[1], longitude: boarding[0])
        let alight = CLLocationCoordinate2D(latitude: alighting[1], longitude: alighting[0])

        async let head = try? fetchWalk(from: origin, to: board, destinationName: "승차 지점")
        async let tail = try? fetchWalk(from: alight, to: target, destinationName: destinationName)
        let (first, last) = await (head, tail)

        var legs = built.legs
        var meters = 0
        var seconds = 0

        if let first {
            legs.insert(contentsOf: first.legs, at: 0)
            meters += first.walkMeters ?? 0
            seconds += first.totalMinutes * 60
        }
        if let last {
            legs.append(contentsOf: last.legs)
            meters += last.walkMeters ?? 0
            seconds += last.totalMinutes * 60
        }
        guard first != nil || last != nil else { return built }

        built = RouteNavResult(
            destination: built.destination,
            // 메운 도보만큼 시간이 는다. 카카오가 준 총 시간에는 이 걸음이 안 들어 있다.
            totalMinutes: built.totalMinutes + seconds / 60,
            transfers: built.transfers,
            walkMeters: meters,
            fareWon: built.fareWon,
            legs: legs
        )
        return built
    }

    // MARK: 호출

    private static func fetch(
        origin: CLLocationCoordinate2D,
        target: CLLocationCoordinate2D,
        language: String
    ) async throws -> [Route] {
        let key = Secrets.kakaoRestKey
        guard !key.isEmpty else { throw Failure.noKey }

        var components = URLComponents(string: "https://dapi.kakao.com/v2/routing/publictraffic")!
        components.queryItems = [
            URLQueryItem(name: "start_x", value: String(origin.longitude)),
            URLQueryItem(name: "start_y", value: String(origin.latitude)),
            URLQueryItem(name: "end_x", value: String(target.longitude)),
            URLQueryItem(name: "end_y", value: String(target.latitude)),
            URLQueryItem(name: "s_name", value: "현재 위치"),
            URLQueryItem(name: "e_name", value: "목적지"),
            // 문서에 없는 파라미터다. 실제로 번역되는 것은 역·정류장 이름뿐이고
            // 노선 이름·도보 안내문은 어느 언어로 불러도 영어로 온다(프로토타입 실측).
            URLQueryItem(name: "lang", value: language),
            URLQueryItem(name: "input_coord", value: "WGS84"),
            URLQueryItem(name: "output_coord", value: "WGS84"),
        ]

        var request = URLRequest(url: components.url!)
        request.setValue("KakaoAK \(key)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 12

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw Failure.transport
        }
        let decoded = try JSONDecoder().decode(Response.self, from: data)
        if let status = decoded.status, status != "OK" {
            throw status == "NO_RESULTS" ? Failure.noRoute : Failure.transport
        }
        return decoded.routes ?? []
    }

    /// 카카오 도보. 대중교통(`fetch`)과 **다른 엔드포인트, 다른 응답 모양**이다 —
    /// 대중교통은 `routes[].steps[]` 로 평평한데, 도보는 `route.legs[].steps[]` 로
    /// 한 겹 더 감싸여 온다. 그래서 `WalkResponse` 를 따로 둔다.
    private static func fetchWalk(
        from origin: CLLocationCoordinate2D,
        to target: CLLocationCoordinate2D,
        destinationName: String
    ) async throws -> RouteNavResult {
        let key = Secrets.kakaoRestKey
        guard !key.isEmpty else { throw Failure.noKey }

        var components = URLComponents(string: "https://dapi.kakao.com/v2/routing/walk")!
        components.queryItems = [
            URLQueryItem(name: "start_x", value: String(origin.longitude)),
            URLQueryItem(name: "start_y", value: String(origin.latitude)),
            URLQueryItem(name: "end_x", value: String(target.longitude)),
            URLQueryItem(name: "end_y", value: String(target.latitude)),
            URLQueryItem(name: "input_coord", value: "WGS84"),
            URLQueryItem(name: "output_coord", value: "WGS84"),
        ]

        var request = URLRequest(url: components.url!)
        request.setValue("KakaoAK \(key)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 12

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw Failure.transport
        }
        let decoded = try JSONDecoder().decode(WalkResponse.self, from: data)
        if let status = decoded.status, status != "OK" {
            throw status == "NO_RESULTS" ? Failure.noRoute : Failure.transport
        }
        guard let route = decoded.route else { throw Failure.noRoute }
        return result(fromWalk: route, destinationName: destinationName)
    }

    // MARK: 응답 → 화면

    private static func result(fromWalk route: WalkRoute, destinationName: String) -> RouteNavResult {
        // 안내문이 있는 걸음만 구간으로 만든다 — 없는 것은 같은 길이 이어지는
        // 중간 좌표일 뿐이라 화면에 낼 문장이 없다.
        let legs: [RouteLeg] = (route.legs ?? [])
            .flatMap { $0.steps ?? [] }
            .compactMap { step in
                guard let guidance = step.properties?.guidance else { return nil }
                return RouteLeg(
                    mode: .walk,
                    title: guidance,
                    detail: walkDetail(
                        meters: Int(step.properties?.distance ?? 0),
                        seconds: step.properties?.time
                    ),
                    path: step.path?.points ?? [],
                    // 카카오 도보는 계단을 구조화해서도, 안내문에 섞어서도 안 준다 —
                    // 문자열을 뒤져도 나오지 않는다(실측). T맵만 `facilityType 17` 로
                    // 안다. 그래서 여기는 늘 `false` 다: 「계단 없음」이 아니라 「모름」인데
                    // 표시할 방법이 없다.
                    hasStairs: false
                )
            }

        let seconds = route.properties?.totalTime ?? 0
        return RouteNavResult(
            destination: destinationName,
            totalMinutes: max(1, Int(seconds / 60)),
            transfers: 0,
            walkMeters: Int(route.properties?.totalDistance ?? 0),
            fareWon: 0,
            legs: legs
        )
    }

    private static func result(from route: Route, destinationName: String) -> RouteNavResult {
        var legs: [RouteLeg] = []
        var walkMeters = 0
        var sawWalk = false

        for step in route.steps ?? [] {
            let properties = step.properties
            let kind = properties?.type ?? ""
            let distance = Int(properties?.distance ?? 0)
            // **실제 길 좌표.** 한 구간이 191개까지 온다(실측). 이것을 안 쓰면
            // 지도에 직선만 남는다.
            let points = step.path?.points ?? []

            if kind == "WALKING" {
                sawWalk = true
                walkMeters += distance
                legs.append(RouteLeg(
                    mode: .walk,
                    title: properties?.guidance ?? "걸어서 이동",
                    detail: walkDetail(meters: distance, seconds: properties?.duration),
                    path: points,
                    // 카카오는 계단을 구조화해서 주지 않는다 — 안내 문구에 섞여 나올
                    // 뿐이라 문자열에서 긁는다. T맵 `facilityType 17` 만이 제대로 준다.
                    hasStairs: (properties?.guidance ?? "").contains("계단")
                ))
            } else if !kind.isEmpty {
                let lane = (properties?.vehicles ?? []).compactMap(\.name).first
                legs.append(RouteLeg(
                    mode: .transit,
                    title: lane ?? modeLabel(kind),
                    detail: transitDetail(properties: properties),
                    path: points
                ))
            }
        }

        return RouteNavResult(
            destination: destinationName,
            totalMinutes: Int((route.properties?.totalTime ?? 0) / 60),
            transfers: route.properties?.transfers ?? 0,
            // **도보 구간이 없으면 0 이 아니라 「모름」이다.** 후보의 47% 가 도보 구간
            // 없이 오는데(프로토타입 실측) 그것을 0 m 로 두면 「안 걸어도 되는 경로」가
            // 되어 가장 좋아 보인다.
            walkMeters: sawWalk ? walkMeters : nil,
            fareWon: fare(from: route.properties?.fare),
            legs: legs
        )
    }

    /// 걸어서 가는 답. 카카오를 부르지 않는다.
    private static func walkOnly(meters: Double, destinationName: String) -> RouteNavResult {
        // 시속 4 km — 분당 66.7 m. 프로토타입이 쓰는 값과 같다.
        let minutes = max(1, Int((meters / 66.7).rounded()))
        return RouteNavResult(
            destination: destinationName,
            totalMinutes: minutes,
            transfers: 0,
            walkMeters: Int(meters.rounded()),
            fareWon: 0,
            legs: [RouteLeg(
                mode: .walk,
                title: "걸어서 \(destinationName)까지",
                detail: "도보 \(minutes)분 · \(Int(meters.rounded())) m"
            )]
        )
    }

    // MARK: 잔손질

    /// 버스 요금은 값이 아니라 **범위**로 온다 — 거리 비례라 확정할 수 없어서다.
    /// 0 으로 두면 「공짜 경로」가 되므로 중간값을 쓴다(프로토타입 실측).
    private static func fare(from fare: Fare?) -> Int? {
        if let value = fare?.value {
            return value
        }
        if let low = fare?.min, let high = fare?.max {
            return (low + high) / 2
        }
        return fare?.min ?? fare?.max
    }

    private static func walkDetail(meters: Int, seconds: Double?) -> String {
        var parts: [String] = []
        if let seconds, seconds > 0 {
            parts.append("도보 \(max(1, Int(seconds / 60)))분")
        }
        if meters > 0 {
            parts.append("\(meters) m")
        }
        return parts.isEmpty ? "걸어서 이동" : parts.joined(separator: " · ")
    }

    private static func transitDetail(properties: StepProperties?) -> String {
        var parts: [String] = []
        if let count = properties?.stationCount, count > 0 {
            parts.append("\(count)개 역")
        }
        if let seconds = properties?.duration, seconds > 0 {
            parts.append("\(max(1, Int(seconds / 60)))분")
        }
        return parts.isEmpty ? "이동" : parts.joined(separator: " · ")
    }

    private static func modeLabel(_ kind: String) -> String {
        switch kind {
        case "BUS": "버스"
        case "SUBWAY": "지하철"
        case "TRAIN": "기차"
        case "EXPRESSBUS": "고속·시외버스"
        case "FERRY": "해운"
        default: "이동"
        }
    }

    /// 하버사인. 두 점 사이 직선거리(m).
    static func distanceMeters(
        _ start: CLLocationCoordinate2D,
        _ end: CLLocationCoordinate2D
    ) -> Double {
        let radius = 6_371_000.0
        let dLat = (end.latitude - start.latitude) * .pi / 180
        let dLng = (end.longitude - start.longitude) * .pi / 180
        let lat1 = start.latitude * .pi / 180
        let lat2 = end.latitude * .pi / 180
        let haversine = sin(dLat / 2) * sin(dLat / 2)
            + sin(dLng / 2) * sin(dLng / 2) * cos(lat1) * cos(lat2)
        return 2 * radius * asin(min(1, sqrt(haversine)))
    }

    // MARK: 응답 모양

    private struct Response: Decodable {
        let status: String?
        let routes: [Route]?
    }

    fileprivate struct Route: Decodable {
        let properties: RouteProperties?
        let steps: [Step]?
    }

    fileprivate struct RouteProperties: Decodable {
        let totalTime: Double?
        let totalDistance: Double?
        let transfers: Int?
        let fare: Fare?
    }

    fileprivate struct Fare: Decodable {
        let value: Int?
        let min: Int?
        let max: Int?
    }

    fileprivate struct Step: Decodable {
        let properties: StepProperties?
        let path: Path?
    }

    fileprivate struct StepProperties: Decodable {
        let type: String?
        let distance: Double?
        let duration: Double?
        let guidance: String?
        let stationCount: Int?
        let vehicles: [Vehicle]?
    }

    fileprivate struct Vehicle: Decodable {
        let name: String?
    }

    fileprivate struct Path: Decodable {
        let points: [[Double]]?
    }

    // MARK: 응답 모양 — 도보

    fileprivate struct WalkResponse: Decodable {
        let status: String?
        let route: WalkRoute?
    }

    fileprivate struct WalkRoute: Decodable {
        let properties: WalkRouteProperties?
        let legs: [WalkLeg]?
    }

    fileprivate struct WalkRouteProperties: Decodable {
        let totalDistance: Double?
        let totalTime: Double?
    }

    fileprivate struct WalkLeg: Decodable {
        let steps: [WalkStep]?
    }

    fileprivate struct WalkStep: Decodable {
        let properties: WalkStepProperties?
        let path: Path?
    }

    /// 대중교통 쪽 `StepProperties` 와 키가 다르다 — `duration` 이 아니라 `time`.
    fileprivate struct WalkStepProperties: Decodable {
        let guidance: String?
        let distance: Double?
        let time: Double?
    }
}
