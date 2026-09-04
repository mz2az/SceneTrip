import Foundation
import SceneApiClient

/// 발자취 한 점.
struct FootprintPoint: Codable, Equatable {
    let latitude: Double
    let longitude: Double
    let at: Date
}

/// 발자취 — 여행 모드 동안 내가 지나간 자리 (계획 `trip-mode.md` §2, 2026-09-02).
///
/// 젤다 야생의 숨결의 「영걸의 길」에서 왔다 — 이동한 길이 지도에 남아 어디를 갔고
/// 안 갔는지 되돌아볼 수 있는 것. 우리 것은 성지 순례의 발자취다.
///
/// **기기에만 남는다.** 이동 기록은 가장 민감한 데이터라 서버로 보내지 않고, 지우기는
/// 한 번에 끝난다. **한국 안에서만** 기록한다(`KoreaBounds`) — 토글이 켜져 있어도
/// 밖에서는 점이 안 쌓인다. 25 m 안에서 오락가락한 것은 한 점으로 친다.
///
/// 저장은 Application Support 의 JSON 파일이다. 1분 간격 200시간이면 1.2만 점 —
/// 파일 하나로 충분하다. 시간 되감기(슬라이더)는 2단계.
@MainActor
final class FootprintStore: ObservableObject {
    static let shared = FootprintStore()

    @Published private(set) var points: [FootprintPoint] = []

    /// 기록 토글. 기기에 남는다(UserDefaults) — 여행 중 앱을 다시 켜도 상태가 이어진다.
    @Published var enabled: Bool {
        didSet { UserDefaults.standard.set(enabled, forKey: Self.enabledKey) }
    }

    static let minStepMeters = 25.0
    private static let enabledKey = "footprint.enabled"

    private init() {
        // 확인용 뒷문 `-footprintOn 1` — 데모 주행 영상에서 토글을 누를 손이 없다.
        enabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
            || UserDefaults.standard.bool(forKey: "footprintOn")
        points = Self.load()
    }

    /// 새 위치. 토글이 꺼져 있거나 한국 밖이거나 직전 점에서 25 m 안이면 버린다.
    func record(latitude: Double, longitude: Double, at now: Date = Date()) {
        guard enabled, KoreaBounds.contains(latitude: latitude, longitude: longitude) else { return }
        if let last = points.last {
            let meters = RouteGeometry.kilometers(
                PlaceSummary(id: 0, name: "", latitude: last.latitude, longitude: last.longitude),
                PlaceSummary(id: 0, name: "", latitude: latitude, longitude: longitude)
            ) * 1000
            guard meters >= Self.minStepMeters else { return }
        }
        points.append(FootprintPoint(latitude: latitude, longitude: longitude, at: now))
        save()
    }

    /// 전부 지운다. 복구 없다 — 그래서 마이페이지가 한 번 더 묻는다.
    func clear() {
        points = []
        save()
    }

    /// 걸은 거리(km) — 점 사이 직선 합. 마이페이지의 한 줄 요약용.
    var kilometers: Double {
        zip(points, points.dropFirst()).reduce(0) { sum, pair in
            sum + RouteGeometry.kilometers(
                PlaceSummary(id: 0, name: "", latitude: pair.0.latitude, longitude: pair.0.longitude),
                PlaceSummary(id: 0, name: "", latitude: pair.1.latitude, longitude: pair.1.longitude)
            )
        }
    }

    // MARK: 저장

    private static var file: URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return nil }
        let dir = base.appendingPathComponent("SceneTrip", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("footprints.json")
    }

    private static func load() -> [FootprintPoint] {
        guard let file, let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([FootprintPoint].self, from: data)) ?? []
    }

    private func save() {
        guard let file = Self.file, let data = try? JSONEncoder().encode(points) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
