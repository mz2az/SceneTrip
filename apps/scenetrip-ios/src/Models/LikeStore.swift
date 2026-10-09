import Foundation
import SceneApiClient

/// 찜의 작은 규칙들 — 값만 보는 순수 함수.
enum LikeSync {
    /// 아직 옮기지 않았으면 서버에 없는 것만 올린다. **한 번 옮긴 뒤에는 올리지 않는다** —
    /// 그때부터 서버가 정본이라, 기기 사본에만 있는 것은 다른 곳에서 지운 찜이다.
    static func pendingUploads(local: Set<Int64>, server: Set<Int64>, migrated: Bool) -> Set<Int64> {
        migrated ? [] : local.subtracting(server)
    }

    /// **찜을 켠 직후의 다시 읽기**가 하트에 미치는 것 — 없다 (MZ2AZ-372).
    ///
    /// 켠 직후에 찜 목록을 다시 읽는 까닭은 새로 찜한 작품의 **요약**(제목·포스터)을 얻으려는 것뿐이다.
    /// 그 응답으로 하트(id 집합)까지 덮으면, 응답이 떠난 뒤에 누른 것이 지워진다:
    /// A 를 켜고 곧바로 끄면 다시 읽기는 「A 있음」 을 들고 와 하트를 다시 켜고, 서버는 꺼진 채라 어긋난 채 남는다.
    /// A·B 를 연달아 켜면 A 의 다시 읽기가 「B 없음」 을 들고 와 B 가 깜빡인다.
    ///
    /// 하트는 **누른 사람의 것**이다. 서버 값으로 하트를 맞추는 것은 요청이 실패했을 때(`toggle` 의 catch)와
    /// 화면을 열 때·계정이 바뀔 때의 `refresh` 뿐이다.
    static func idsAfterSummaryReload(current: Set<Int64>, fetched _: [Int64]) -> Set<Int64> {
        current
    }

    /// 찜 목록에 그릴 줄 — 받아 둔 요약 가운데 **지금 하트가 켜진 것만**, 받은 순서(최근 찜부터)로.
    /// 요약은 한 박자 늦게 오므로 꺼진 작품의 요약이 남아 있을 수 있다 — 그것을 그리지 않는다.
    static func shown<Work>(_ works: [Work], liked: Set<Int64>, id: (Work) -> Int64) -> [Work] {
        works.filter { liked.contains(id($0)) }
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

    /// 찜한 작품의 **요약** — 서버가 찜 목록에 실어 주는 그대로, 찜한 순서(최근 것부터)다 (MZ2AZ-372).
    ///
    /// 전에는 id 만 남기고 버렸다. 그래서 찜 목록을 그리는 화면이 「작품 전체」 를 받아 거기서 id 로 찾았는데,
    /// 작품이 100편을 넘자 그 「전체」 가 앞 100편뿐이라 찜한 작품이 목록에서 사라졌다. 이제 찜 목록은
    /// 이것으로 그린다 — 작품이 몇 편이든 찜한 것은 여기 다 있다.
    ///
    /// 기기에 저장하지 않는다(하트를 채우는 `contentIds` 만 저장한다). 서버에 닿기 전에는 비어 있다.
    @Published private(set) var works: [ContentSummary] = []

    /// 찜 목록에 그릴 줄 — `works` 가운데 지금 하트가 켜진 것(`LikeSync.shown`).
    var likedWorks: [ContentSummary] {
        LikeSync.shown(works, liked: contentIds, id: \.id)
    }

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
    /// 닿았는지를 돌려준다 — 찜 목록 화면이 「없음」 과 「못 받음」 을 가른다.
    @discardableResult
    func refresh() async -> Bool {
        guard let fetched = try? await fetchAll() else { return false }
        var server = Set(fetched.map(\.id))
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
        works = fetched
        save()
        return true
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
                    // 새로 찜한 작품의 요약은 서버에만 있다 — 찜 목록에 바로 보이게 다시 읽는다.
                    // **요약만** 채운다. 하트는 덮지 않는다(`LikeSync.idsAfterSummaryReload`).
                    await reloadSummaries()
                } else {
                    try await FavoritesAPI.removeFavoriteContent(
                        xInstallId: installId, contentId: contentId
                    )
                }
            } catch {
                apply(contentId, liked: !liked)
                // 실패가 「서버는 처리했는데 응답만 잃은 것」 일 수 있다 — 그러면 되돌린 화면이 서버와 어긋난다
                // (찜을 풀었는데 하트가 다시 켜진다). 서버 값을 한 번 받아 맞춘다. 그 조회도 실패하면 되돌린
                // 채로 둔다(`refresh` 는 못 받으면 아무것도 바꾸지 않는다). (MZ2AZ-366)
                await refresh()
            }
        }
    }

    /// 찜한 작품의 요약만 다시 읽는다 — 하트(`contentIds`)는 건드리지 않는다. 못 받으면 가진 것을 둔다.
    private func reloadSummaries() async {
        guard let fetched = try? await fetchAll() else { return }
        contentIds = LikeSync.idsAfterSummaryReload(current: contentIds, fetched: fetched.map(\.id))
        works = fetched
    }

    private func apply(_ contentId: Int64, liked: Bool) {
        if liked {
            contentIds.insert(contentId)
        } else {
            contentIds.remove(contentId)
            works.removeAll { $0.id == contentId }
        }
        save()
    }

    private func save() {
        UserDefaults.standard.set(contentIds.map(NSNumber.init(value:)), forKey: key)
    }

    /// 계약의 한 번 최대가 100 이라 끝까지 넘긴다. 찜은 사람이 누른 만큼이라 전부 받아도 작다.
    private func fetchAll() async throws -> [ContentSummary] {
        var all: [ContentSummary] = []
        var paging = Paging()
        while paging.hasMore {
            let page = try await FavoritesAPI.listFavoriteContents(
                xInstallId: installId, limit: 100, offset: paging.offset
            )
            all = Paging.merged(all, with: page.items, by: \.id)
            paging = paging.advanced(received: page.items.count, total: page.total)
        }
        return all
    }
}
