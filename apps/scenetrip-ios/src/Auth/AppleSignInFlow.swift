import Foundation
import SceneApiClient

/// Apple 창 → 비회원 쓰기 완료 → 생성 클라이언트의 코드 교환. 토큰 저장은 성공 응답 뒤 AuthStore가 한다.
@MainActor
enum AppleSignInFlow {
    static func signIn(
        installId: UUID,
        authorize: @MainActor () async throws -> AppleSignIn = { try await AppleOAuth.signIn() },
        settle: @MainActor () async throws -> Void = { try await PendingWrites.settle() },
        exchange: @MainActor (UUID, AppleSignIn) async throws -> AuthSession = { installId, body in
            try await AuthAPI.signInWithApple(xInstallId: installId, appleSignIn: body)
        }
    ) async throws -> AuthSession {
        let body = try await authorize()
        try Task.checkCancellation()
        try await settle()
        try Task.checkCancellation()
        return try await exchange(installId, body)
    }
}
