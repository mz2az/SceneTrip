import Foundation
import SceneApiClient

/// AI 가 코스를 짠다 (MZ2AZ-200).
///
/// ## 왜 필요한가
///
/// 앞서 이 자리는 **고른 작품의 촬영지를 전부 담았다.** 눈물의 여왕은 촬영지가 67곳이라
/// 1박 2일을 고르면 하루 34곳이 됐다. 하루에 34곳을 도는 여행자는 없다.
///
/// ## 무엇을 LLM 에 맡기고 무엇을 코드가 하나
///
/// **고르는 것만 맡긴다.** 계산은 코드가 한다 — 프로토타입(`SceneTrip_navi`)이 세운
/// 규칙과 같다. 모델은 「이 후보 중 어느 것을, 어느 날에」만 답하고, 거리·순서·시간은
/// 우리가 센다. 모델에게 산수를 시키면 틀린 값을 자신 있게 내놓는다.
///
/// 후보도 우리가 추린다. 촬영지 67곳을 통째로 프롬프트에 넣으면 토큰이 커지고 모델이
/// 헤맨다. **인기순으로 잘라** 하루 상한의 세 배쯤만 보여 준다.
///
/// ## 모델이 없으면
///
/// main 에서는 LLM 을 부르지 않는다(MZ2AZ-297) — 늘 **규칙만으로**
/// 짠다 — 인기순으로 상한만큼 골라 지리적으로 잇는다. 데모가 모델 유무에 매이면
/// 안 된다.
enum RoutePlanner {
    /// 하루에 넣을 장소 수. 「빡빡/널널」이 여기서 갈린다.
    ///
    /// 8/11 회의가 항목만 정하고 로직은 열어 두었는데(회의록 Open Issue 3), **하루
    /// 몇 곳인가**는 그중 가장 눈에 보이는 값이라 여기서 먼저 정한다. 촬영지 한 곳에
    /// 30분씩 머문다고 보면 빡빡해도 5곳이 한나절이다.
    static func perDay(_ pace: RoutePace) -> Int {
        switch pace {
        case .tight: 5
        case .loose: 3
        }
    }

    /// 모델에게 보여 줄 후보 수. 하루 상한의 세 배쯤이면 고를 여지가 있으면서
    /// 프롬프트가 길어지지 않는다.
    private static func candidateLimit(days: Int, pace: RoutePace) -> Int {
        min(40, max(12, perDay(pace) * days * 2))
    }

    /// 한 덩어리로 볼 반경(km).
    ///
    /// 실측으로 골랐다(2026-08-24, 서버 촬영지 155곳). 반경별로 눈물의 여왕 67곳을
    /// 묶어 보면 —
    ///
    /// | 반경 | 가장 큰 덩어리 | 그 안의 퍼짐 |
    /// | --- | --- | --- |
    /// | 15 km | 36곳 | 25 km |
    /// | **25 km** | **40곳** | **42 km** |
    /// | 40 km | 45곳 | 64 km |
    /// | 60 km | 46곳 | 102 km |
    ///
    /// 15 km 는 서울과 인천을 갈라 도깨비 후보가 32곳으로 줄고, 40 km 를 넘으면
    /// 퍼짐이 하루에 못 도는 크기가 된다. 25 km 가 **후보 수와 퍼짐이 둘 다 쓸 만한**
    /// 유일한 값이었다.
    private static let clusterRadiusKm = 25.0

    // MARK: 짜기

    /// 고른 작품의 촬영지에서 코스를 짠다.
    ///
    /// 모델이 답하면 그 순서를, 못 답하면 인기순을 쓴다. 어느 쪽이든 **일차별 상한을
    /// 넘지 않는다** — 상한은 코드가 지킨다.
    static func plan(
        places: [PlaceSummary],
        days: Int,
        pace: RoutePace,
        workTitles: [String],
        near: (lat: Double, lng: Double)? = nil
    ) async -> (stops: [RouteStop], byModel: Bool) {
        let limit = perDay(pace)
        // **지역으로 먼저 거르고 모델에게 준다.** 거르지 않으면 눈물의 여왕(67곳,
        // 전국에 퍼져 있다)에서 강원 정선과 서울 종로가 한 바구니에 담겨 모델에게
        // 가고, 모델은 그 둘이 176 km 떨어졌다는 것을 알 방법이 없다 — 프롬프트에
        // 이름과 주소만 실리기 때문이다(좌표를 실어도 모델에게 거리 계산을 시키는
        // 셈이라 믿을 수 없다). 실제로 1일차가 362 km 짜리로 나왔다.
        let pool = regionPool(places, need: limit * days, near: near)
        let candidates = Array(pool.prefix(candidateLimit(days: days, pace: pace)))
        guard !candidates.isEmpty else { return ([], false) }

        if let picked = await ask(
            candidates: candidates, days: days, perDay: limit, workTitles: workTitles
        ), !picked.isEmpty {
            return (picked.map { RouteStop(place: $0) }, true)
        }

        // 모델이 없거나 답이 쓸 수 없으면 인기순으로 상한만큼. **이쪽도 하루
        // 상한을 정확히 지킨다** — 모델이 실패했다고 일정이 이상해지면 안 된다.
        let fallback = Array(candidates.prefix(limit * days))
        return (fallback.map { RouteStop(place: $0) }, false)
    }

    // MARK: 지역으로 거르기

    /// 촬영지를 지역 덩어리로 묶고, **가장 큰 덩어리부터** 필요한 만큼 담아 준다.
    ///
    /// ## 왜 「가장 큰 덩어리」인가
    ///
    /// 촬영지가 많이 몰린 곳이 그 작품의 중심지다. 눈물의 여왕은 서울·경기에 45곳이
    /// 몰려 있고 경북·충북에 몇 곳씩 흩어져 있다 — 여행자가 그 작품을 보러 온다면
    /// 가는 곳은 전자다.
    ///
    /// 모자라면 **가장 가까운 덩어리를 붙인다.** 기간이 길어 25곳이 필요한데 한
    /// 덩어리에 19곳뿐이면(이태원 클라쓰) 옆 덩어리까지 담는다 — 그때도 「가까운
    /// 옆」이라 전국구가 되지는 않는다.
    ///
    /// ## 효과 (2026-08-24 실측, 1일차 이동거리)
    ///
    /// | 작품 | 거르기 전 | 거른 뒤 |
    /// | --- | --- | --- |
    /// | 눈물의 여왕 3일 | 590 km | **69 km** |
    /// | 도깨비 2일 | 111 km | **58 km** |
    /// | 이태원 클라쓰 1일 | 511 km | **14 km** |
    /// - Parameter near: 지금 있는 자리. **한국 안일 때만 쓴다** — 밖이면 무시하고
    ///   가장 큰 덩어리를 고른다(아래 `isInKorea`).
    static func regionPool(
        _ places: [PlaceSummary],
        need: Int,
        near here: (lat: Double, lng: Double)? = nil
    ) -> [PlaceSummary] {
        guard places.count > need else { return places }

        var groups = clusters(places)
        guard !groups.isEmpty else { return places }

        // **지금 있는 자리가 우선한다.** 부산에 있는 사람에게 서울 코스를 내밀 이유가
        // 없다 — 가장 큰 덩어리는 대개 서울이라 자리를 안 보면 늘 서울이 나온다.
        let start: Int = if let here, isInKorea(here) {
            groups.indices.min {
                distance(here, center(of: groups[$0])) < distance(here, center(of: groups[$1]))
            }!
        } else {
            0 // `clusters` 가 큰 것부터 돌려준다.
        }
        var pool = groups.remove(at: start)

        // 모자라면 지금 담긴 것의 무게중심에서 가장 가까운 덩어리를 붙인다.
        while pool.count < need, !groups.isEmpty {
            let here = center(of: pool)
            let nearest = groups.indices.min {
                distance(here, center(of: groups[$0])) < distance(here, center(of: groups[$1]))
            }!
            pool += groups.remove(at: nearest)
        }
        return pool
    }

    /// 한국 안인가.
    ///
    /// **밖이면 자리를 무시한다.** 이 앱은 외국인이 오기 **전에도** 코스를 짜는데,
    /// 그때 「가장 가까운 덩어리」는 뜻이 없다 — 도쿄에서 재나 뉴욕에서 재나 한국
    /// 어딘가가 가장 가까울 뿐이고, 그것이 그 사람이 가고 싶은 곳은 아니다. 그때는
    /// 촬영지가 가장 많이 몰린 곳(=그 작품의 중심지)이 낫다.
    ///
    /// 시뮬레이터 기본 위치가 미국이라 이 갈림이 개발 중에도 실제로 걸린다.
    ///
    /// 네모로 판단한다. 국경선을 정확히 그릴 이유가 없다 — 「한국 안이냐」가 아니라
    /// 「가까운 덩어리를 고르는 것이 뜻이 있느냐」를 가르는 것이기 때문이다.
    private static func isInKorea(_ point: (lat: Double, lng: Double)) -> Bool {
        (32.5 ... 39.5).contains(point.lat) && (124.0 ... 132.5).contains(point.lng)
    }

    /// 반경 안을 한 덩어리로 묶는다. **이웃이 가장 많은 곳을 씨앗으로 삼는다** —
    /// 아무 곳에서나 시작하면 변두리 한 곳이 씨앗이 되어 덩어리가 잘게 갈린다.
    ///
    /// 큰 덩어리부터 돌려준다. 촬영지가 155곳뿐이라 이 정도 계산은 눈 깜짝할 새다.
    private static func clusters(_ places: [PlaceSummary]) -> [[PlaceSummary]] {
        var left = places
        var out: [[PlaceSummary]] = []

        while !left.isEmpty {
            let seed = left.max { first, second in
                neighbours(of: first, in: left) < neighbours(of: second, in: left)
            }!
            let group = left.filter { distance(point(seed), point($0)) <= clusterRadiusKm }
            out.append(group)
            let taken = Set(group.map(\.id))
            left = left.filter { !taken.contains($0.id) }
        }
        return out.sorted { $0.count > $1.count }
    }

    private static func neighbours(of place: PlaceSummary, in places: [PlaceSummary]) -> Int {
        places.count { distance(point(place), point($0)) <= clusterRadiusKm }
    }

    private static func point(_ place: PlaceSummary) -> (lat: Double, lng: Double) {
        (place.latitude, place.longitude)
    }

    private static func center(of places: [PlaceSummary]) -> (lat: Double, lng: Double) {
        let count = Double(places.count)
        return (places.reduce(0) { $0 + $1.latitude } / count,
                places.reduce(0) { $0 + $1.longitude } / count)
    }

    /// 두 점 사이 직선거리(km). `RouteGeometry.kilometers` 와 같은 계산이지만 그쪽은
    /// `PlaceSummary` 를 받아 여기서는 무게중심(장소가 아닌 좌표)에 못 쓴다.
    private static func distance(
        _ start: (lat: Double, lng: Double),
        _ end: (lat: Double, lng: Double)
    ) -> Double {
        let radius = 6371.0
        let dLat = (end.lat - start.lat) * .pi / 180
        let dLng = (end.lng - start.lng) * .pi / 180
        let lat1 = start.lat * .pi / 180
        let lat2 = end.lat * .pi / 180
        let haversine = sin(dLat / 2) * sin(dLat / 2)
            + sin(dLng / 2) * sin(dLng / 2) * cos(lat1) * cos(lat2)
        return 2 * radius * asin(min(1, sqrt(haversine)))
    }

    // MARK: 모델

    private static func ask(
        candidates: [PlaceSummary],
        days: Int,
        perDay: Int,
        workTitles: [String]
    ) async -> [PlaceSummary]? {
        let listing = candidates.enumerated().map { index, place in
            let kind = place.type.map { " (\($0))" } ?? ""
            return "\(index + 1). \(place.name)\(kind) — \(place.address ?? "주소 없음")"
        }.joined(separator: "\n")

        let works = workTitles.isEmpty ? "인기 작품" : workTitles.joined(separator: ", ")
        let total = perDay * days

        // **「하루 N곳씩 총 M곳」으로 물으면 안 된다.** 5일 × 5곳을 그렇게 물었더니
        // 모델이 5개만 답했다(실측) — 「하루에 5곳씩」을 총 5곳으로 읽는다.
        // 개수를 한 번만, 크게 말한다.
        let prompt = """
        한국 촬영지 여행 코스를 짠다. 아래 후보에서 정확히 \(total)곳을 고른다.

        작품: \(works)
        여행 기간: \(days)일

        규칙:
        - **반드시 \(total)개의 번호**를 답한다. 더도 덜도 아니다.
        - 후보가 \(total)개보다 적으면 있는 것을 전부 고른다.
        - 가까운 곳끼리 이어지도록 순서를 정한다.
        - 같은 번호를 두 번 쓰지 않는다.

        후보 \(candidates.count)곳:
        \(listing)

        답은 번호 \(total)개를 쉼표로 이어서만 적는다. 설명하지 않는다.
        """

        guard let text = await LocalModel.complete(prompt: prompt) else { return nil }

        // 모델이 뭐라고 덧붙이든 **숫자만 긁어낸다.** 「1. 경복궁」처럼 답해도 통한다.
        let numbers = text.split(whereSeparator: { !$0.isNumber })
            .compactMap { Int($0) }
            .filter { $0 >= 1 && $0 <= candidates.count }

        var seen = Set<Int>()
        let picked = numbers.filter { seen.insert($0).inserted }
            .prefix(total)
            .map { candidates[$0 - 1] }

        // **적게 고르면 버린다.** 앞서 「2개 이상」으로 뒀다가 5일 코스에 2곳이
        // 들어갔다 — 그러면 하루에 한 곳씩만 보는 일정이 된다. 요청한 양의 70%는
        // 채워야 쓸 만하다고 본다. 후보 자체가 적으면 그만큼만 기대한다.
        let expected = min(total, candidates.count)
        let floor = max(1, Int(Double(expected) * 0.7))
        guard picked.count >= floor else { return nil }
        return Array(picked)
    }
}

/// main 에서는 LLM 을 부르지 않는다 (2026-09-02, MZ2AZ-297).
///
/// AI 코스의 LLM 후보 선택은 프론트 자체 시험(로컬 :8900)이었다 — main 의
/// 프론트는 백엔드 계약에만 의존하므로, 정식 자리가 정해질 때까지(MZ2AZ-285
/// 계보) **규칙 기반 폴백**만 쓴다. LLM 을 붙인 판은 navi-proto 브랜치에 있다.
enum LocalModel {
    static func complete(prompt _: String, timeout _: TimeInterval = 45) async -> String? {
        // nil 을 돌려주면 부르는 쪽의 규칙 기반 경로가 그대로 동작한다 —
        // 처음부터 「모델이 없을 수 있다」는 전제로 설계된 폴백이다.
        nil
    }
}
