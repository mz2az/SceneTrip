import Combine
import Foundation
import SceneApiClient

/// 도시로 여행 짜기가 받는 것 — 도시 목록(`GET /places/cities`)과 고른 도시의 촬영지(`GET /places?city=`) (MZ2AZ-547 Q1·R1·S1).
///
/// 앱에 하나다 — 새 코스 시트의 도시 사진, 질문의 도시 칸, 추천 코스·직접 고르기가 같은 것을 본다. 도시 촬영지는 한 번 받으면
/// 들고 있다(앱 언어가 바뀌면 다시 받는다).
@MainActor
final class CityPlacesStore: ObservableObject {
    static let shared = CityPlacesStore()

    enum Status: Equatable {
        case idle, loading, loaded, failed
    }

    @Published private(set) var cities: [PlaceCity] = []
    @Published private(set) var status = Status.idle
    @Published private(set) var places: [String: [PlaceSummary]] = [:]
    @Published private(set) var placeFailures: Set<String> = []

    /// 질문에 내보일 도시 수 — 촬영지가 적은 도시까지 다 늘어놓으면 고르기 어렵다.
    static let shownCities = 8
    /// 도시 촬영지 한 번에 받는 수 — 추천(최대 7×4×2=56)·직접 고르기(30)에 넉넉하다.
    static let placePage = 200

    private var lang = AppLanguage.current
    private var retried = false

    var shownCities: [PlaceCity] {
        Array(cities.filter { $0.placeCount > 0 }.prefix(Self.shownCities))
    }

    func city(_ key: String?) -> PlaceCity? {
        cities.first { $0.key == key }
    }

    /// 도시 목록을 (아직 없으면) 받는다. 못 받으면 15초 뒤 한 번 더 — 요청 한도(429)에 걸리기 쉽다(웹과 같다).
    func loadCities() async {
        if lang != AppLanguage.current {
            lang = AppLanguage.current
            cities = []
            places = [:]
            status = .idle
        }
        guard status != .loaded, status != .loading else { return }
        status = .loading
        if let list = try? await PlacesAPI.listPlaceCities(), !list.items.isEmpty {
            cities = list.items
            status = .loaded
            return
        }
        status = .failed
        guard !retried else { return }
        retried = true
        try? await Task.sleep(for: .seconds(15))
        guard !Task.isCancelled, status == .failed else { return }
        await loadCities()
    }

    /// 「다시 시도」.
    func retry() async {
        status = .idle
        await loadCities()
    }

    /// 이 도시의 촬영지(인기순)를 (아직 없으면) 받는다.
    func loadPlaces(_ key: String) async {
        guard places[key] == nil else { return }
        placeFailures.remove(key)
        do {
            places[key] = try await PlacesAPI.listPlaces(limit: Self.placePage, city: key).items
        } catch {
            placeFailures.insert(key)
        }
    }

    /// 도시 칸·시트 줄의 사진 — 고정 사진이 먼저, 없으면 받아 둔 인기 1위 촬영지 사진.
    func photo(_ key: String) -> HomeCityRules.Photo? {
        let top = places[key]?.first { HomeTabModel.hasPhoto($0) }?.imageUrl
        return HomeCityRules.photo(cityKey: key, topPlaceImageUrl: top)
    }
}

/// 정한 여행(Q4) — 도시·며칠·속도·만든 코스. 기기에 기억하고 서버(`/me/trip`, 비회원도 설치 id 로)와 맞춘다.
///
/// 켤 때 한 번 서버에 묻는다 — 서버 값이 이긴다. 서버에 없고 기기에만 있으면 올린다(`TripMemoryRules.reconcile`).
/// 바꿀 때마다 `PUT`. 실패해도 기기 것은 남고 다음에 켤 때 다시 올린다.
@MainActor
final class TripMemoryStore: ObservableObject {
    static let shared = TripMemoryStore()

    @Published private(set) var trip: TripMemo?

    private static let key = "scenetrip.trip"
    private var resetWatch: AnyCancellable?

    private init() {
        trip = Self.readLocal()
        // 계정을 지우거나 바꾸면 여행 기억도 그 사람 것이 아니다.
        resetWatch = AccountDataReset.changes.sink { [weak self] _ in
            self?.trip = nil
            UserDefaults.standard.removeObject(forKey: Self.key)
        }
    }

    func sync() async {
        let server: ServerTrip
        do {
            let found = try await TripAPI.getTrip(xInstallId: InstallIdentity.current)
            server = .found(TripMemo(city: found.city, days: found.days, pace: found.pace, courseId: found.courseId))
        } catch let ErrorResponse.error(status, _, _, _) where status == 404 {
            server = .missing
        } catch {
            server = .failed
        }
        let result = TripMemoryRules.reconcile(local: trip, server: server)
        store(result.use)
        if result.push, let use = result.use {
            await push(use)
        }
    }

    func set(_ next: TripMemo) {
        store(next)
        Task { await push(next) }
    }

    private func push(_ memo: TripMemo) async {
        _ = try? await TripAPI.putTrip(
            xInstallId: InstallIdentity.current,
            tripPlanInput: TripPlanInput(city: memo.city, days: memo.days, pace: memo.pace, courseId: memo.courseId)
        )
    }

    private func store(_ memo: TripMemo?) {
        trip = memo
        if let memo, let data = try? JSONEncoder().encode(memo) {
            UserDefaults.standard.set(data, forKey: Self.key)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.key)
        }
    }

    private static func readLocal() -> TripMemo? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(TripMemo.self, from: data)
    }
}
