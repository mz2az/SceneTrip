import Foundation
import SceneApiClient

/// 비회원 기록 삭제. 회원 탈퇴와 달리 토큰을 보내거나 세션을 갱신하지 않는다(MZ2AZ-471).
@MainActor
enum GuestDataDeletion {
    enum Outcome: Equatable {
        case deleted
        case sessionRequired
        case failed
    }

    static func isVisible(signedIn: Bool) -> Bool {
        !signedIn
    }

    static func delete(
        installId: UUID,
        request: (UUID) async throws -> Void = { try await GuestDeleteRequest.send(installId: $0) }
    ) async -> Outcome {
        do {
            try await request(installId)
            return .deleted
        } catch let ErrorResponse.error(status, data, _, _)
            where status == 401 && AuthRules.apiCode(from: data) == "SESSION_REQUIRED"
        {
            return .sessionRequired
        } catch {
            return .failed
        }
    }
}

/// 생성기의 메서드·경로·설치 헤더를 재사용하되 인증 가로채기와 자동 재전송은 거치지 않는다.
/// 삭제 응답을 잃은 뒤 새 비회원 계정이 생길 수 있으므로 삭제를 자동으로 반복하지 않는다.
final class GuestDeleteRequest: URLSessionRequestBuilder<Void> {
    private var session: URLSessionProtocol = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        return URLSession(configuration: config)
    }()

    static func make(installId: UUID, session: URLSessionProtocol? = nil) throws -> GuestDeleteRequest {
        guard let generated = AuthAPI.deleteMeWithRequestBuilder(xInstallId: installId)
            as? URLSessionRequestBuilder<Void> else { throw URLError(.badURL) }
        let request = GuestDeleteRequest(
            method: generated.method, URLString: generated.URLString,
            parameters: generated.parameters, headers: generated.buildHeaders(),
            requiresAuthentication: false
        )
        if let session {
            request.session = session
        }
        return request
    }

    static func send(installId: UUID, session: URLSessionProtocol? = nil) async throws {
        let response = try await make(installId: installId, session: session).execute()
        guard response.statusCode == 204 else { throw URLError(.badServerResponse) }
    }

    override func createURLSession() -> URLSessionProtocol {
        session
    }

    override func buildHeaders() -> [String: String] {
        super.buildHeaders().filter { $0.key.caseInsensitiveCompare("Authorization") != .orderedSame }
    }

    override func addCredential() {}
}
