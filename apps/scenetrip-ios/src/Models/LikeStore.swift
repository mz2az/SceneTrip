import Foundation

/// 작품 찜. **장바구니와 별개 저장소다.**
///
/// 8/11 회의 확정 — *"작품 찜이 있고, 장소에는 장바구니. 장소에는 찜 없다"*,
/// *"작품은 하트로 표시하고 장소는 플러스로 표시"*. 둘을 한 저장소로 합치면
/// "작품을 장바구니에 담았다" 는 잘못된 모형이 코드에 박힌다.
///
/// **아직 서버가 없다.** 찜 API 는 MZ2AZ-231 이고 계약에도 없다. 그때까지 기기에만
/// 둔다 — 앱을 다시 켜도 남아야 하므로 메모리가 아니라 `UserDefaults` 다.
/// 서버가 생기면 `CartStore` 처럼 갈아 끼운다.
///
/// 이 화면이 필요한 이유는 검색 탭이 아니라 **경로여정 탭**에 있다. AI 로 코스를
/// 짤 때 *"찜한 작품 우선적으로 보여주고 나머지는 인기도 순으로"* 가 확정이라
/// (MZ2AZ-235), 찜이 없으면 그 화면이 반쪽이 된다.
@MainActor
final class LikeStore: ObservableObject {
    /// **앱에 하나뿐이다.** 검색 탭에서 누른 하트가 마이페이지에 바로 보여야
    /// 한다 — 화면마다 따로 만들면 각자 처음 읽은 값에 멈춘다(2026-08-28 확인).
    static let shared = LikeStore()

    @Published private(set) var contentIds: Set<Int64> = []

    private let key = "scenetrip.likedContents"

    init() {
        // 숫자로 저장하지만 **읽을 때는 문자열도 받아 준다** — 도구(`defaults`)로
        // 심은 값이 문자열로 들어오는 일이 실제로 있었다(2026-08-28).
        let saved = UserDefaults.standard.array(forKey: key) ?? []
        contentIds = Set(saved.compactMap { item in
            (item as? NSNumber)?.int64Value ?? (item as? String).flatMap { Int64($0) }
        })
    }

    func contains(_ contentId: Int64) -> Bool {
        contentIds.contains(contentId)
    }

    func toggle(_ contentId: Int64) {
        if contentIds.contains(contentId) {
            contentIds.remove(contentId)
        } else {
            contentIds.insert(contentId)
        }
        UserDefaults.standard.set(contentIds.map(NSNumber.init(value:)), forKey: key)
    }
}
