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
/// ## 개인정보로 다룬다 (MZ2AZ-348, 2026-10-05 결정)
///
/// 이동 경로는 사람을 특정할 수 있는 정보다. 그래서
/// 1. **이 기기에만** 둔다 — 서버로 보내지 않는다(우리는 위치 이력을 수집하지 않는다).
/// 2. **기본은 꺼짐.** 사람이 마이페이지에서 켜고 동의해야 기록한다(`recording`).
/// 3. **로그아웃·탈퇴·세션 소실 때 지운다**(`forgetOwner`) — 기기에 남의 기록이 남지 않게.
/// 4. 지우기는 한 번에 끝난다.
///
/// 개인정보 처리방침에 적는 내용이 이 규칙과 같아야 한다 — 고치면 문서도 함께 고친다.
///
/// 이동 기록은 가장 민감한 데이터라 서버로 보내지 않고, 지우기는
/// 한 번에 끝난다. **한국 안에서만** 기록한다(`KoreaBounds`) — 토글이 켜져 있어도
/// 밖에서는 점이 안 쌓인다. 25 m 안에서 오락가락한 것은 한 점으로 친다.
///
/// 저장은 Application Support 의 JSON 파일이다. 1분 간격 200시간이면 1.2만 점 —
/// 파일 하나로 충분하다. 시간 되감기(슬라이더)는 2단계.
@MainActor
final class FootprintStore: ObservableObject {
    static let shared = FootprintStore()

    @Published private(set) var points: [FootprintPoint] = []

    /// **기록 동의** — 켜야 점이 쌓인다. 기본은 꺼짐이고, 켤 때 마이페이지가 무엇이 어디에
    /// 저장되는지 알린다. 기기에 남는다(UserDefaults).
    @Published var recording: Bool {
        didSet { UserDefaults.standard.set(recording, forKey: Self.recordingKey) }
    }

    /// **보기** 토글 — 지도에 발자국을 그릴 것인가. 기록 여부와는 따로다.
    /// 앞서 기록·보기가 한 스위치였는데, 꺼 두면 나중에 켜도 볼 게 없었다(2026-09-04 사용자
    /// 요청: 토글은 「보이기/숨기기」). 기기에 남는다(UserDefaults).
    @Published var enabled: Bool {
        didSet { UserDefaults.standard.set(enabled, forKey: Self.enabledKey) }
    }

    static let minStepMeters = 25.0
    private static let enabledKey = "footprint.enabled"
    private static let recordingKey = "footprint.recording"

    private init() {
        // 확인용 뒷문 `-footprintOn 1` — 데모 주행 영상에서 토글을 누를 손이 없다.
        enabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
            || UserDefaults.standard.bool(forKey: "footprintOn")
        // 같은 뒷문이 기록 동의도 켠다 — 영상에서 동의 창을 누를 손도 없다.
        recording = UserDefaults.standard.bool(forKey: Self.recordingKey)
            || UserDefaults.standard.bool(forKey: "footprintOn")
        points = Self.load()
    }

    /// 계정이 이 기기를 떠났다(로그아웃·탈퇴·세션 소실). 기록을 지우고 동의도 되돌린다 —
    /// 다음 사람이 앞사람의 길을 보거나, 앞사람의 동의로 기록되면 안 된다.
    func forgetOwner() {
        clear()
        recording = false
    }

    /// 새 위치. **기록 동의가 꺼져 있으면 버린다.** 한국 밖이거나 직전 점에서 25 m 안이어도 버린다.
    /// 보기 토글과는 무관하다.
    func record(latitude: Double, longitude: Double, at now: Date = Date()) {
        guard recording else { return }
        guard KoreaBounds.contains(latitude: latitude, longitude: longitude) else { return }
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
