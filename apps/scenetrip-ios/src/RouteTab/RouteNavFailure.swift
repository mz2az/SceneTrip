import Foundation
import SceneApiClient

/// 길찾기가 안 된 이유 — **계약이 정한 응답별로 갈린다** (`POST /navigation/next-leg`,
/// docs/api/errors.md).
///
/// 앞서는 `catch` 하나가 모든 오류를 「준비 중」으로 뭉뚱그렸다. 서버가 없던 때의
/// 정직한 말이었는데, 서버가 서자 거짓말이 됐다 — 401 도 503 도 「준비 중」이면
/// 사용자는 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다.
///
/// 판별은 두 겹이다. HTTP 코드로 갈래를 나누고, 같은 코드 안에서 뜻이 갈리는 것
/// (404 의 코스/항목, 422 의 경로 없음/정류장 없음)은 본문의 `ApiError.code` 로 본다.
/// 생성 클라이언트는 연결 자체가 실패하면 음수 코드를 준다(`ApiFailure` 주석) — 그것은
/// 서버 탓이 아니라 네트워크 탓이라 따로 말한다.
enum RouteNavFailure: Equatable {
    /// 가입해야 부를 수 있다 (401). 로컬 kind 는 벽을 치워 두어(MZ2AZ-302) 안 난다.
    case signInRequired
    /// 코스나 항목이 서버에 없다 (404). 저장 전 코스, 지운 항목.
    case notFound
    /// 코스를 시작하지 않았다 (409). 「시작」을 누르면 통한다.
    case courseNotActive
    /// 경로가 없다 (422). **다시 불러도 같다** — 섬·산속, 정류장 없는 자리.
    case noRoute(code: String)
    /// 제공자(카카오)가 응답하지 않거나 한도를 넘었다 (503). 잠시 뒤 다시.
    case providerDown
    /// 서버에 닿지 못했다. 백엔드가 꺼져 있거나 네트워크가 없다.
    case unreachable
    /// 그 밖의 응답. 코드를 그대로 보여 준다.
    case other(status: Int)

    // 앱 안에서 정하는 것 — 서버를 부르기 전에 걸러진다.

    /// 챗봇 가게로 갈아탄 목적지. 계약이 **활성 코스의 항목**만 받으므로 코스 밖
    /// 좌표는 물을 수 없다.
    case detourUnsupported
    /// 저장 전 코스라 서버 항목 id 가 없다.
    case unsavedCourse

    /// 오류를 계약 응답으로 분류한다.
    init(_ error: Error) {
        guard case let ErrorResponse.error(status, data, _, _) = error else {
            self = .unreachable
            return
        }
        // 음수는 HTTP 코드가 아니라 「연결 실패」 신호다 (URLSessionImplementations).
        guard status > 0 else {
            self = .unreachable
            return
        }
        let code = Self.apiCode(from: data)
        switch status {
        case 401: self = .signInRequired
        case 404: self = .notFound
        case 409: self = .courseNotActive
        case 422: self = .noRoute(code: code ?? "ROUTE_NOT_FOUND")
        case 503: self = .providerDown
        default: self = .other(status: status)
        }
    }

    private static func apiCode(from data: Data?) -> String? {
        guard let data, let body = try? JSONDecoder().decode(ApiError.self, from: data) else {
            return nil
        }
        return body.code
    }

    /// 화면에 보이는 한 줄. **안드로이드와 같은 문구여야 한다.**
    var message: String {
        switch self {
        case .signInRequired:
            "길찾기는 가입한 분만 쓸 수 있어요"
        case .notFound:
            "저장된 코스의 장소에서만 길찾기를 부를 수 있어요"
        case .courseNotActive:
            "코스를 시작한 뒤에 길찾기를 쓸 수 있어요"
        case let .noRoute(code) where code == "NO_TRANSIT_NEARBY":
            "근처에 정류장이 없어 대중교통 경로를 찾지 못했어요"
        case .noRoute:
            "여기서는 경로를 찾지 못했어요"
        case .providerDown:
            "길찾기 서비스가 잠시 응답하지 않아요. 잠시 뒤 다시 시도해 주세요"
        case .unreachable:
            "서버에 연결하지 못했어요 — 백엔드(:8081)가 켜져 있나요?"
        case let .other(status):
            "길찾기를 처리하지 못했어요 (\(status))"
        case .detourUnsupported:
            "코스 밖 장소로의 길찾기는 아직 지원하지 않아요"
        case .unsavedCourse:
            "저장된 코스의 장소에서만 길찾기를 부를 수 있어요"
        }
    }

    /// 「다시 시도」 단추를 보일 것인가. 다시 불러도 같은 답이 오는 것(경로 없음·
    /// 가입 필요·코스 밖)에는 단추를 두지 않는다 — 눌러도 달라지지 않는 단추는
    /// 사용자를 두 번 실망시킨다.
    var canRetry: Bool {
        switch self {
        case .providerDown, .unreachable, .other: true
        default: false
        }
    }
}
