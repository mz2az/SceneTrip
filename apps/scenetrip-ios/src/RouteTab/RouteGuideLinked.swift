import Foundation
import SceneApiClient

/// 챗봇이 찾아 준 곳 중 **어느 것이 네이버 장소에 연결돼 있는지 알아 온다** (2026-09-17).
///
/// 목록의 줄을 펼치면 네이버 카드(사진·영업시간·리뷰)가 나와야 하는데, 백엔드의 이름 맞추기
/// (`NaverMatcher`)가 상가정보 이름에 맞춰져 있지 않아 **대부분이 「네이버에서 찾지 못했습니다」**
/// 로 끝난다(MZ2AZ-327). 처음에는 연결 안 된 줄을 뺐는데 열두 곳이 한 곳이 됐다 — **빼지 않고
/// 표시한다**(같은 날 사용자 결정: "그냥 표시해주고 내가 그 1곳을 눌리면 되는거잖아").
/// 연결된 줄에는 「네이버」 딱지가 붙고 목록 위로 올라온다. 지도 핀은 초록 테를 두른다.
///
/// 묻는 곳은 목록용 `GET /pois/cards` 다. 바깥을 부르지 않아 몇 ms 에 오고, 아직 안 물어본
/// 것은 `pending` 으로 온 뒤 서버가 뒤에서 채운다. 계약의 규칙대로 **`pending` 이었던 것만**
/// 최대 3 회 다시 묻는다. 끝까지 `pending` 이면 연결 안 된 것으로 친다.
///
/// 촬영지(`place-N`)는 우리 상세가 있으므로 늘 연결된 것으로 친다.
enum RouteGuideLinked {
    /// 한 번 물은 결과. `retryAfter` 는 서버가 어림한 다음 물을 때까지의 초.
    struct Probe {
        var linked: Set<Int64> = []
        var pending: Set<Int64> = []
        var retryAfter = 1
    }

    /// 다시 묻기 전 기다리는 시간의 상한(초). 서버 어림은 30 까지 가는데, 답을 그만큼
    /// 붙들고 있을 수는 없다.
    static let waitCapSeconds = 3

    /// 연결된 편의시설의 `poiId`.
    static func linkedIds(
        of places: [RouteGuide.Place],
        retries: Int = 3,
        probe: ([Int64]) async -> Probe = ask,
        wait: (Int) async -> Void = { try? await Task.sleep(for: .seconds($0)) }
    ) async -> Set<Int64> {
        var asking = places.compactMap(\.poiId)
        var linked = Set<Int64>()
        var round = 0
        while !asking.isEmpty {
            let found = await probe(asking)
            linked.formUnion(found.linked)
            asking = asking.filter(found.pending.contains)
            guard !asking.isEmpty, round < retries else { break }
            round += 1
            await wait(min(max(found.retryAfter, 1), waitCapSeconds))
        }
        return linked
    }

    /// 표시를 달고 **연결된 것을 위로** 올린다. 그 안의 순서는 받은 그대로다(에이전트가 정한
    /// 거리·리뷰순) — 열두 줄 중 눌러 볼 만한 한 줄을 찾아 내려가지 않게.
    static func marked(_ places: [RouteGuide.Place], linked: Set<Int64>) -> [RouteGuide.Place] {
        let flagged = places.map { place in
            var next = place
            next.linked = place.poiId.map(linked.contains) ?? true
            return next
        }
        return flagged.filter { $0.linked == true } + flagged.filter { $0.linked != true }
    }

    /// 실패하면 빈 답 — 하나도 연결 안 된 것으로 친다. 모르는 것을 있다고 하지 않는다.
    static func ask(_ ids: [Int64]) async -> Probe {
        // 계약 상한이 50 개다. 챗봇 목록은 그보다 짧지만 넘치면 앞에서 자른다.
        guard let batch = try? await PoisAPI.listPoiCards(ids: Array(ids.prefix(50))) else {
            return Probe()
        }
        var result = Probe(retryAfter: batch.retryAfterSeconds ?? 1)
        for card in batch.items {
            if card.pending == true {
                result.pending.insert(card.poiId)
            } else if card.found == true {
                result.linked.insert(card.poiId)
            }
        }
        return result
    }
}
