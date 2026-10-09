import Foundation
import SceneApiClient

/// 촬영지 하나가 **어느 작품에 나왔는가**를 응답에서 읽는 규칙 (MZ2AZ-372).
///
/// 같은 사실이 응답 꼴에 따라 다른 칸에 실린다.
///
/// - **목록**(`GET /places` 의 `PlaceSummary`): `contents` — 「이 장소에 등장한 작품들」.
/// - **상세**(`GET /places/{id}` 의 `PlaceDetail`): **`scenes`** — 「이 장소에서 촬영된 작품과 장면」, 서버가
///   인기도 내림차순으로 정해 준다. 계약상 `PlaceDetail` 은 `PlaceSummary` 를 그대로 물려받아 `contents` 칸도
///   있지만, **서버는 상세에서 그 칸을 빈 배열로 준다**(2026-10-09 실측: `/places/49` → `contents: []`,
///   `scenes: [Lovely Runner]`). 그래서 상세에서는 `scenes` 가 정본이다.
///
/// 처음에 상세의 `contents` 를 읽었다가 코스 줄의 작품 이름이 계속 비었다. 여기서는 `scenes` 를 먼저 보고,
/// 그것이 비었을 때만 `contents` 로 물러선다(서버가 그 칸을 채우기 시작해도 맞게).
enum PlaceWorks {
    /// 상세에서 작품들을 — 서버가 준 순서 그대로, 같은 작품은 한 번만.
    static func refs(scenes: [SceneItem]?, contents: [ContentRef]?) -> [ContentRef] {
        let fromScenes = (scenes ?? []).map {
            ContentRef(contentId: $0.contentId, title: $0.contentTitle, posterUrl: $0.posterUrl)
        }
        var seen = Set<Int64>()
        return (fromScenes.isEmpty ? contents ?? [] : fromScenes).filter { seen.insert($0.contentId).inserted }
    }

    static func refs(of detail: PlaceDetail) -> [ContentRef] {
        refs(scenes: detail.scenes, contents: detail.contents)
    }

    /// 제목만. 빈 제목은 뺀다.
    static func titles(scenes: [SceneItem]?, contents: [ContentRef]?) -> [String] {
        refs(scenes: scenes, contents: contents).map(\.title).filter { !$0.isEmpty }
    }

    /// 코스에 든 곳 가운데 **상세를 물어야 하는 곳**. 순서를 지키고 같은 곳은 한 번만.
    ///
    /// - 직접 찍은 핀(id 가 0 이하)은 촬영지가 아니라 물을 것이 없다.
    /// - 목록으로 받아 둔 곳(`listed`)은 작품을 이미 들고 있다.
    /// - 이미 답을 받은 곳(`answered`)은 다시 묻지 않는다 — **작품이 정말 없다는 답도 답이다.** 못 받은 것
    ///   (끊김·오류)은 `answered` 에 넣지 않으므로 다음에 다시 묻는다.
    /// - 지금 묻고 있는 곳(`asking`)을 또 묻지 않는다.
    static func toAsk(
        _ placeIds: [Int64], listed: Set<Int64>, answered: Set<Int64>, asking: Set<Int64>
    ) -> [Int64] {
        var seen = Set<Int64>()
        return placeIds.filter { id in
            id > 0 && !listed.contains(id) && !answered.contains(id) && !asking.contains(id)
                && seen.insert(id).inserted
        }
    }
}
