import Foundation
import SceneApiClient

// 추천 코스 카드 — 사진을 누르면 뒤집혀 장면 그림 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §6).
// 어떤 장면 그림을 뒷면에 둘지, 화면이 처음 뜰 때 어느 카드가 한 번 살짝 돌지(알려 주기)를 정한다.
// 움직임 자체는 화면 쪽에서 그린다 — 여기는 숫자와 고르기 규칙만.

enum SceneFlipRules {
    /// 앞면 ↔ 뒷면 뒤집기 시간(초).
    static let flipDuration: Double = 0.45
    /// 화면이 처음 뜬 뒤 살짝 돌기까지 기다리는 시간(초).
    static let peekDelay: Double = 0.6
    /// 살짝 돌기 각도(도).
    static let peekAngle: Double = 25
    /// 장소 상세(`GET /places/{placeId}`)를 동시에 받는 최대 수.
    static let maxConcurrentDetailFetches = 4
    /// 동작 줄이기일 때 앞뒤를 서서히 바꾸는 시간(초).
    static let reducedMotionFade: Double = 0.2

    /// 카드 뒷면의 장면 그림.
    /// 카드 작품(`workId`, 카드 장소의 `contents[0]`)과 `contentId` 가 같은 장면의 `sceneImageUrls` 첫 장.
    /// 그 장면에 그림이 없거나 작품을 모르면 그림이 있는 첫 장면의 첫 장. 아무 데도 없으면 nil(뒤집히지 않는다).
    static func sceneImageUrl(detail: PlaceDetail, workId: Int64?) -> String? {
        let scenes = detail.scenes ?? []
        if let workId, let own = scenes.first(where: { $0.contentId == workId })?.sceneImageUrls.first {
            return own
        }
        return scenes.lazy.compactMap(\.sceneImageUrls.first).first
    }

    /// 카드 사진 칸의 두 면 — 장소 사진(`photoUrl`, 쓸 수 없는 주소면 없는 것으로)과 장면 그림(`sceneUrl`). 모든 카드가 뒤집힌다.
    /// 앞면은 장소 사진, 없으면 장면 그림, 둘 다 없으면 빈 칸. 뒷면은 앞면에 쓰지 않은 쪽, 없으면 「없음」 면(§6 표).
    static func faces(photoUrl: String?, sceneUrl: String?) -> SceneFlipFaces {
        let photo = photoUrl.flatMap { NewCoursePhoto.usable($0) ? $0 : nil }
        switch (photo, sceneUrl) {
        case let (photo?, scene?): return SceneFlipFaces(front: photo, back: .scene(scene), frontIsScene: false)
        case let (nil, scene?): return SceneFlipFaces(front: scene, back: .noPhoto, frontIsScene: true)
        case let (photo, nil): return SceneFlipFaces(front: photo, back: .noScene, frontIsScene: false)
        }
    }

    /// 화면이 처음 뜰 때 한 번 살짝 돌 카드의 자리(0부터).
    /// 뒤집히는 첫 카드. 사용자가 한 번이라도 뒤집었거나(`hasFlipped`) 동작 줄이기면 nil. 뒤집히는 카드가 없어도 nil.
    static func peekIndex(flippable: [Bool], hasFlipped: Bool, reduceMotion: Bool) -> Int? {
        guard !hasFlipped, !reduceMotion else { return nil }
        return flippable.firstIndex(of: true)
    }
}

/// 카드 사진 칸의 두 면.
struct SceneFlipFaces: Equatable {
    /// 앞면 그림 주소 — 없으면 빈 칸.
    let front: String?
    let back: SceneFlipBack
    /// 앞면이 장면 그림인가(장소 사진이 없어 대신 보일 때) — 알약을 「「작품」 속 장면」 으로.
    let frontIsScene: Bool
}

/// 카드 뒷면.
enum SceneFlipBack: Equatable {
    /// 장면 그림.
    case scene(String)
    /// 「장소 사진이 아직 없어요」 — 장면 그림이 앞면에 갔을 때.
    case noPhoto
    /// 「장면 그림이 아직 없어요」.
    case noScene
}
