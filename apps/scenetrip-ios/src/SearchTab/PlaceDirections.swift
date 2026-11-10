import Foundation
import SceneApiClient

/// 촬영지 상세 「길찾기」 (MZ2AZ-532 → iOS MZ2AZ-547 G3, 웹 `features/route/placeDirections.ts`).
///
/// 촬영지 한 곳만 가 보고 싶은 사람에게 코스 만들기는 병목이다. 누르면 그 장소 하나로 된 1일 코스를 만들고
/// (「직접 짜기」 저장과 같은 길), 여행 중으로 바꾼 뒤(서버는 여행 중 코스에만 길찾기를 준다), 그 코스를 열어
/// 안내를 켠다. 비회원이면 로그인 시트를 먼저 띄우고, 로그인되면 같은 장소로 이어 간다. 서버·계약은 그대로다.
///
/// 바깥과 닿는 것은 전부 `PlaceDirectionsDeps` 로 받는다 — 시험이 가짜로 바꿔 끼운다.
enum PlaceDirectionsRules {
    /// 계약 `CourseCreate`·`CourseReplace` 의 title maxLength. 넘기면 POST 가 400 이다.
    static let titleMax = 50

    /// 「{장소} 가는 길」 / "Trip to {place}". 50자를 넘으면 장소 이름 끝을 말줄임(…)으로 잘라 50자 안에 둔다.
    /// 길이는 UTF-16 단위로 잰다 — 서버(자바 `String.length`)와 웹이 재는 방식이다.
    static func title(_ name: String) -> String {
        let full = format(name)
        guard full.utf16.count > titleMax else { return full }
        var cut = ""
        for character in name {
            if format(cut + String(character) + "…").utf16.count > titleMax {
                break
            }
            cut.append(character)
        }
        let trimmed = cut.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
        return format(trimmed + "…")
    }

    private static func format(_ name: String) -> String {
        String(format: tr("%@ 가는 길"), name)
    }

    /// 그 장소 하나로 된 1일 코스(아직 저장 전).
    static func course(for place: PlaceSummary) -> RouteCourse {
        RouteCourse(title: title(place.name), days: [RouteDay(stops: [RouteStop(place: place)])])
    }

    /// 이 장소 하나뿐인 1일 코스인가 — 다시 열어도 되는 「가는 길」. 사용자가 고쳐 다른 곳을 더했으면 아니다.
    static func isDirectionsCourse(_ course: RouteCourse, of placeId: Int64) -> Bool {
        guard course.days.count == 1, let only = course.days.first?.stops, only.count == 1 else { return false }
        return only[0].kind == .place && only[0].place.id == placeId
    }

    /// 받아 둔 코스 목록(요약)에서 이 장소의 「가는 길」일 수 있는 것 — 여행 중·1일·1곳·제목이 같은 것의 서버 id.
    /// 진짜 그 장소인지는 속을 읽어 확인한다(`isDirectionsCourse`).
    static func candidates(in courses: [RouteCourse], for place: PlaceSummary) -> [Int64] {
        let wanted = title(place.name)
        return courses
            .filter { $0.isRunning && $0.days.count == 1 && $0.placeCount == 1 && $0.title == wanted }
            .compactMap(\.serverId)
    }
}

/// 한 걸음의 결과 — 실패면 화면에 보일 한 줄.
enum PlaceDirectionsStep: Equatable {
    case ok
    case failed(String)
}

@MainActor
protocol PlaceDirectionsDeps: AnyObject {
    func isSignedIn() -> Bool
    /// 로그인 시트를 띄우고 결과를 기다린다. 로그인되면 true, 로그인 없이 시트가 내려가면 false.
    func askSignIn() async -> Bool
    /// 새 코스를 서버에 만든다(POST + 채우기 PUT). 실패면 화면에 보일 한 줄.
    func createCourse(_ course: RouteCourse) async -> Result<RouteCourse, PlaceDirectionsFailure>
    /// 코스를 여행 중(1일차)으로 바꾼다.
    func startCourse(_ course: RouteCourse) async -> PlaceDirectionsStep
    /// 그 코스를 열며 안내를 켠다.
    func openTrip(_ courseId: Int64)
    /// 장소 id → 이 기능이 전에 만든 코스 id. 상세를 닫았다 다시 열어도 남는다.
    var made: [Int64: Int64] { get set }
    /// 받아 둔 코스 목록에서 이 장소의 「가는 길」 후보.
    func existingCandidates(_ place: PlaceSummary) -> [Int64]
    /// 코스 하나의 속(읽기만). 없거나 못 받으면 nil.
    func loadCourse(_ courseId: Int64) async -> RouteCourse?
}

struct PlaceDirectionsFailure: Error, Equatable {
    let message: String
}

enum PlaceDirectionsOutcome: Equatable {
    case started(courseId: Int64)
    case cancelled
    case busy
    case failed(String)
}

/// 촬영지 상세 하나에 하나. 장소가 바뀌거나 화면을 떠나면 `dispose()` 하고 새로 만든다.
@MainActor
final class PlaceDirections {
    private let deps: any PlaceDirectionsDeps
    private var running = false
    private var disposed = false

    init(deps: any PlaceDirectionsDeps) {
        self.deps = deps
    }

    /// 「길찾기」를 눌렀다. 진행 중이면(로그인 시트가 떠 있는 동안 포함) 새 누름은 버린다.
    func start(_ place: PlaceSummary) async -> PlaceDirectionsOutcome {
        if running || disposed {
            return .busy
        }
        running = true
        defer { running = false }
        return await run(place)
    }

    /// 화면을 떠났다 — 기다리던 로그인이 끝나도 이어 가지 않는다.
    func dispose() {
        disposed = true
    }

    private func run(_ place: PlaceSummary) async -> PlaceDirectionsOutcome {
        if !deps.isSignedIn() {
            let signedIn = await deps.askSignIn()
            if !signedIn || disposed {
                return .cancelled
            }
        }
        var course = await reusable(place)
        if disposed {
            return .cancelled
        }
        if course == nil {
            switch await deps.createCourse(PlaceDirectionsRules.course(for: place)) {
            case let .failure(failure):
                return .failed(failure.message)
            case let .success(created):
                guard let id = created.serverId else { return .failed(tr("코스를 불러오지 못했어요")) }
                deps.made[place.id] = id
                course = created
                // 만드는 사이 화면을 떠났다 — 여행 중으로 바꾸지 않는다(다시 누르면 이 코스를 쓴다).
                if disposed {
                    return .cancelled
                }
            }
        }
        guard let course, let courseId = course.serverId else { return .failed(tr("코스를 불러오지 못했어요")) }
        if !course.isRunning {
            if case let .failed(message) = await deps.startCourse(course) {
                return .failed(message)
            }
            if disposed {
                return .cancelled
            }
        }
        deps.openTrip(courseId)
        return .started(courseId: courseId)
    }

    /// 다시 쓸 코스 — 이 기능이 전에 만든 것(기억), 그다음 받아 둔 목록의 여행 중 「가는 길」. 진짜 이 장소
    /// 하나뿐인지 속을 읽어 확인한다. 지워졌거나 고쳐 다른 코스가 된 기억은 버린다.
    private func reusable(_ place: PlaceSummary) async -> RouteCourse? {
        let remembered = deps.made[place.id]
        let ids = (remembered.map { [$0] } ?? []) + deps.existingCandidates(place).filter { $0 != remembered }
        for id in ids {
            let course = await deps.loadCourse(id)
            if let course, course.serverId != nil, PlaceDirectionsRules.isDirectionsCourse(course, of: place.id) {
                return course
            }
            if id == remembered {
                deps.made[place.id] = nil
            }
        }
        return nil
    }
}

/// 「길찾기」의 실제 의존성 — 로그인은 `AuthStore`, 코스는 앱이 함께 쓰는 `RouteStore`, 열기는 `TabRouter`.
@MainActor
final class LivePlaceDirectionsDeps: PlaceDirectionsDeps {
    /// 장소 id → 「길찾기」가 만든 코스 id. 상세를 닫았다 다시 열어도, 다른 장소를 봤다 와도 남는다
    /// (앱을 다시 켜면 목록 찾기 `existingCandidates` 가 대신한다).
    private static var remembered: [Int64: Int64] = [:]

    private let routes: RouteStore
    private let auth: AuthStore
    private let router: TabRouter

    init(routes: RouteStore, auth: AuthStore = .shared, router: TabRouter = .shared) {
        self.routes = routes
        self.auth = auth
        self.router = router
    }

    var made: [Int64: Int64] {
        get { Self.remembered }
        set { Self.remembered = newValue }
    }

    func isSignedIn() -> Bool {
        auth.signedIn
    }

    /// 로그인 시트를 이유 한 줄과 함께 띄우고, 로그인되거나 시트가 내려갈 때까지 기다린다.
    func askSignIn() async -> Bool {
        auth.promptSignIn()
        auth.message = tr("로그인하면 바로 길안내를 시작해요")
        while !Task.isCancelled {
            if auth.signedIn {
                return true
            }
            if !auth.showingSignIn {
                return false
            }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return false
    }

    func createCourse(_ course: RouteCourse) async -> Result<RouteCourse, PlaceDirectionsFailure> {
        if let saved = await routes.save(course, origin: "place_directions") {
            return .success(saved)
        }
        return .failure(PlaceDirectionsFailure(message: lastFailure()))
    }

    func startCourse(_ course: RouteCourse) async -> PlaceDirectionsStep {
        await routes.setRunning(course, true, dayNo: 1)
        return routes.saveFailure == nil ? .ok : .failed(lastFailure())
    }

    /// 홈 「이어서 길찾기」와 같은 길 — 경로여정을 열어 그 코스로 들어가고, 열리자마자 안내를 켠다.
    func openTrip(_ courseId: Int64) {
        router.pendingTripStart = true
        router.openCourse(courseId)
    }

    func existingCandidates(_ place: PlaceSummary) -> [Int64] {
        PlaceDirectionsRules.candidates(in: routes.courses, for: place)
    }

    func loadCourse(_ courseId: Int64) async -> RouteCourse? {
        await routes.detail(RouteCourse(serverId: courseId, title: "", days: []))
    }

    private func lastFailure() -> String {
        routes.saveFailure?.courseSaveMessage ?? tr("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요")
    }
}
