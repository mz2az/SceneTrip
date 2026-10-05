import Foundation
import SceneApiClient

/// 기기에만 있던 찜을 서버로 옮길 때 무엇을 올리는가.
enum LikeSync {
    /// 아직 옮기지 않았으면 서버에 없는 것만 올린다. **한 번 옮긴 뒤에는 올리지 않는다** —
    /// 그때부터 서버가 정본이라, 기기 사본에만 있는 것은 다른 곳에서 지운 찜이다.
    static func pendingUploads(local: Set<Int64>, server: Set<Int64>, migrated: Bool) -> Set<Int64> {
        migrated ? [] : local.subtracting(server)
    }
}

/// 작품 찜. **장바구니와 별개 저장소다.**
///
/// 8/11 회의 확정 — *"작품 찜이 있고, 장소에는 장바구니. 장소에는 찜 없다"*,
/// *"작품은 하트로 표시하고 장소는 플러스로 표시"*. 둘을 한 저장소로 합치면
/// "작품을 장바구니에 담았다" 는 잘못된 모형이 코드에 박힌다.
///
/// ## 서버가 정본이다 (MZ2AZ-335)
///
/// 기기(`UserDefaults`)에만 두던 것을 `/favorites/contents` 로 옮겼다. **기기에만 있으면
/// 로그인해도 계정에 붙지 않는다** — 서버의 합치기(MZ2AZ-256)는 서버에 있는 것만 본다.
///
/// 기기 사본은 남긴다. 앱을 켠 직후 서버 응답 전에도 하트가 채워져 있어야 하고, 서버에
/// 못 닿아도 화면이 비지 않아야 한다. 옛 버전의 찜은 첫 `refresh` 에서 서버로 올린다.
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
    /// 옛 기기 찜을 서버로 다 올렸는가.
    private let migratedKey = "scenetrip.likesOnServer"
    private let installId = InstallIdentity.current

    init() {
        // 숫자로 저장하지만 **읽을 때는 문자열도 받아 준다** — 도구(`defaults`)로
        // 심은 값이 문자열로 들어오는 일이 실제로 있었다(2026-08-28).
        let saved = UserDefaults.standard.array(forKey: key) ?? []
        contentIds = Set(saved.compactMap { item in
            (item as? NSNumber)?.int64Value ?? (item as? String).flatMap { Int64($0) }
        })
        Task { await refresh() }
    }

    func contains(_ contentId: Int64) -> Bool {
        contentIds.contains(contentId)
    }

    /// 서버의 찜을 읽어 온다. 로그인으로 계정이 합쳐진 뒤에도 부른다(MZ2AZ-336).
    ///
    /// 서버에 못 닿으면 기기 사본을 그대로 둔다 — 빈 목록으로 덮으면 찜이 사라져 보인다.
    func refresh() async {
        guard var server = try? await fetchAll() else { return }
        let migrated = UserDefaults.standard.bool(forKey: migratedKey)
        var allUploaded = true
        for id in LikeSync.pendingUploads(local: contentIds, server: server, migrated: migrated) {
            do {
                try await FavoritesAPI.addFavoriteContent(
                    xInstallId: installId, favoriteContentCreate: FavoriteContentCreate(contentId: id)
                )
                server.insert(id)
            } catch let ErrorResponse.error(code, _, _, _) where code == 404 {
                // 사라진 작품 — 올릴 것이 없다. 다시 시도해도 같다.
            } catch {
                allUploaded = false
                server.insert(id) // 다음 실행에 다시 올린다. 그때까지 하트는 유지한다.
            }
        }
        if allUploaded {
            UserDefaults.standard.set(true, forKey: migratedKey)
        }
        contentIds = server
        save()
    }

    /// 하트는 **누르는 즉시** 바뀐다. 서버가 거절하면 되돌린다.
    func toggle(_ contentId: Int64) {
        let liked = !contentIds.contains(contentId)
        apply(contentId, liked: liked)
        AppAnalytics.log(.likeTitle(contentId: contentId, liked: liked))
        Task {
            do {
                if liked {
                    try await FavoritesAPI.addFavoriteContent(
                        xInstallId: installId,
                        favoriteContentCreate: FavoriteContentCreate(contentId: contentId)
                    )
                } else {
                    try await FavoritesAPI.removeFavoriteContent(
                        xInstallId: installId, contentId: contentId
                    )
                }
            } catch {
                apply(contentId, liked: !liked)
            }
        }
    }

    private func apply(_ contentId: Int64, liked: Bool) {
        if liked {
            contentIds.insert(contentId)
        } else {
            contentIds.remove(contentId)
        }
        save()
    }

    private func save() {
        UserDefaults.standard.set(contentIds.map(NSNumber.init(value:)), forKey: key)
    }

    /// 계약의 한 번 최대가 100 이라 끝까지 넘긴다.
    private func fetchAll() async throws -> Set<Int64> {
        var ids: Set<Int64> = []
        var offset = 0
        while true {
            let page = try await FavoritesAPI.listFavoriteContents(
                xInstallId: installId, limit: 100, offset: offset
            )
            ids.formUnion(page.items.map(\.id))
            offset += page.items.count
            if page.items.isEmpty || offset >= page.total {
                return ids
            }
        }
    }
}
