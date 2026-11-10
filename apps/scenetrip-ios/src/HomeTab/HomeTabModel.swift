import Combine
import Foundation
import SceneApiClient

/// 홈이 서버에서 받아 오는 것들 (계획 `mobile-home-tab.md` §2).
///
/// **네 요청을 나란히 보낸다.** 줄줄이 기다리면 코스 상세(스탬프용)가 느릴 때 작품
/// 선반까지 비어 있다 — 마이페이지가 같은 이유로 같은 모양이다(2026-08-28 버그).
/// 하나가 실패해도 나머지는 그린다 — 홈은 서버가 꺼져 있어도 죽지 않는다.
@MainActor
final class HomeTabModel: ObservableObject {
    /// 「지금 뜨는 작품」 — 서버 인기도 순서 그대로.
    @Published private(set) var works: [ContentSummary] = []

    /// 「오늘의 성지」 — 하루 동안 같은 곳.
    @Published private(set) var today: PlaceSummary?

    /// 홈 사진 카드의 도시 칩 — `GET /places/cities` 한 번, 서버 순서 그대로(MZ2AZ-547 H3). 못 받으면 비어 있고 카드는 그대로 뜬다.
    @Published private(set) var cities: [PlaceCity] = []

    @Published private(set) var stamps: [VisitStamp] = []

    /// 작품 목록조차 못 받았나 — 화면이 「불러오지 못했습니다」 를 띄우는 기준.
    @Published private(set) var failed = false

    private var resetWatch: AnyCancellable?
    /// 매번 읽는다 — 설치 식별자는 바뀔 수 있다(MZ2AZ-521).
    private var installId: UUID {
        InstallIdentity.current
    }

    private let fetchWorks: (Int) async throws -> [ContentSummary]

    /// 기본 조회는 인기순 첫 쪽에 고정 작품(케이팝 데몬 헌터스)을 맨 앞에 세운다(MZ2AZ-547 H6, `PinnedWorks`).
    init(fetchWorks: @escaping (Int) async throws -> [ContentSummary] = { limit in
        let works = try await ContentsAPI.listContents(limit: limit).items
        return await PinnedWorks.apply(to: works) { try? await ContentsAPI.getContent(contentId: $0) }
    }) {
        self.fetchWorks = fetchWorks
        resetWatch = AccountDataReset.changes.sink { [weak self] _ in
            self?.stamps = []
        }
    }

    /// **코스와 무관한 셋을 나란히 받고, 온 것부터 그린다** (MZ2AZ-372).
    ///
    /// 전에는 넷을 나란히 보내 놓고도 **작품 응답을 먼저 기다린 뒤** 나머지를 대입했고, 그 앞에서는 화면이
    /// 코스 목록·촬영지 200·작품 30·장바구니를 차례로 다 기다린 뒤에야 이것을 불렀다. 작품이 한 번의 호출이던
    /// 때는 티가 안 났는데, 선반을 고르려고 작품을 끝까지 훑게 되면서 「오늘의 성지」·스탬프까지 그 뒤로
    /// 밀렸다. 이제 각자 받는 대로 제 자리에 넣는다. 선반은 인기순 첫 20편만 받는다.
    func loadRest() async {
        async let todayJob: Void = loadToday()
        async let stampsJob: Void = loadStamps()
        async let shelfJob: Void = loadShelf()
        async let citiesJob: Void = loadCities()
        _ = await (todayJob, stampsJob, shelfJob, citiesJob)
    }

    private func loadCities() async {
        guard let list = try? await PlacesAPI.listPlaceCities() else { return }
        cities = list.items
    }

    func loadShelf() async {
        guard let fresh = try? await fetchWorks(Self.shelfCount) else {
            failed = works.isEmpty
            return
        }
        works = Self.shelf(from: fresh)
        failed = false
    }

    private func loadToday() async {
        guard let list = try? await PlacesAPI.listPlaces(limit: 60), !list.items.isEmpty else { return }
        // **사진 있는 곳에서 고른다.** 카드 윗단이 96pt 그림 자리인데 사진이 없으면
        // 그라데이션만 남아 빈 띠로 보인다. 사진이 하나도 없을 때만 전체에서 고른다.
        let withPhoto = list.items.filter { Self.hasPhoto($0) }
        let pool = withPhoto.isEmpty ? list.items : withPhoto
        // **연중 몇 번째 날인가로 고른다.** 무작위면 화면을 다시 그릴 때마다 성지가
        // 바뀌어 「오늘의」 라는 말이 거짓이 된다. 하루가 지나면 다음 곳.
        let day = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1
        today = pool[day % pool.count]
    }

    private func loadStamps() async {
        let generation = AccountDataReset.generation
        let fetched = await VisitStamp.collect(installId: installId)
        guard generation == AccountDataReset.generation else { return }
        stamps = fetched
    }

    // MARK: 「지금 뜨는 작품」 — 서버 인기순

    /// 선반에 올리는 수.
    nonisolated static let shelfCount = 20
    /// 서버가 준 인기도 순서를 유지하며 첫 20편만 표시한다 (MZ2AZ-411).
    nonisolated static func shelf(from works: [ContentSummary]) -> [ContentSummary] {
        Array(works.prefix(shelfCount))
    }

    /// 화면에 실제로 뜨는 사진이 있는가.
    ///
    /// 주소가 있다고 그림이 나오는 것은 아니다 — 시드에 호스트 없는 상대 경로가 섞여
    /// 있어(`place_image` 82행 중 70행) `AsyncImage` 가 조용히 빈 자리를 남긴다.
    /// 그래서 **절대 http(s) 주소**만 사진으로 친다.
    /// 값만 보는 순수 함수라 `nonisolated` 다 — 모델이 `@MainActor` 이지만 이것까지
    /// 주 스레드에 묶을 이유가 없고, 묶으면 시험에서 부르지 못한다.
    nonisolated static func hasPhoto(_ place: PlaceSummary) -> Bool {
        guard let raw = place.imageUrl, let url = URL(string: raw), let scheme = url.scheme else {
            return false
        }
        return (scheme == "http" || scheme == "https") && url.host != nil
    }
}
