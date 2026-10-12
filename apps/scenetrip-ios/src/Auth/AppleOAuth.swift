import AuthenticationServices
import CryptoKit
import Foundation
import SceneApiClient
import Security
import UIKit

/// Apple 인증 재료. 토큰의 진위와 nonce는 서버가 검증한다(MZ2AZ-415).
enum AppleOAuth {
    enum Failure: Error {
        case cancelled
        case failed
        case invalidCredential
        case secureRandomUnavailable
        case noPresentationWindow
        case alreadyInProgress
    }

    /// 난수 생성이 실패하면 고정 nonce로 로그인하지 않는다.
    static func randomNonce() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw Failure.secureRandomUnavailable
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func hashedNonce(_ raw: String) -> String {
        SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func isCancellation(_ error: Error) -> Bool {
        let error = error as NSError
        return error.domain == ASAuthorizationError.errorDomain && error.code == ASAuthorizationError.canceled.rawValue
    }

    @MainActor private static var active: Session?

    /// controller의 delegate는 약한 참조다. 인증 창이 닫힐 때까지 Session을 보유한다.
    @MainActor
    static func signIn() async throws -> AppleSignIn {
        guard active == nil else { throw Failure.alreadyInProgress }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let anchor = scenes.filter({ $0.activationState == .foregroundActive })
            .flatMap(\.windows).first(where: \.isKeyWindow) else { throw Failure.noPresentationWindow }
        let session = try Session(anchor: anchor, nonce: randomNonce())
        active = session
        defer { active = nil }
        return try await withTaskCancellationHandler {
            try await session.authorize()
        } onCancel: {
            Task { @MainActor in session.cancel() }
        }
    }

    static func payload(
        identityToken: Data?, authorizationCode: Data?, nonce: String, fullName: PersonNameComponents? = nil
    ) throws -> AppleSignIn {
        guard nonce.count >= 16 else { throw Failure.invalidCredential }
        return try AppleSignIn(
            identityToken: text(identityToken), authorizationCode: text(authorizationCode), nonce: nonce,
            givenName: name(fullName?.givenName), familyName: name(fullName?.familyName)
        )
    }

    private static func text(_ data: Data?) throws -> String {
        guard let data, let value = String(data: data, encoding: .utf8),
              !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw Failure.invalidCredential
        }
        return value
    }

    private static func name(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }

    @MainActor
    private final class Session: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
        private let anchor: UIWindow
        private let nonce: String
        private var controller: ASAuthorizationController?
        private var continuation: CheckedContinuation<AppleSignIn, Error>?

        init(anchor: UIWindow, nonce: String) {
            self.anchor = anchor
            self.nonce = nonce
        }

        func authorize() async throws -> AppleSignIn {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let request = ASAuthorizationAppleIDProvider().createRequest()
                request.requestedScopes = [.fullName, .email]
                request.nonce = hashedNonce(nonce)
                let controller = ASAuthorizationController(authorizationRequests: [request])
                controller.delegate = self
                controller.presentationContextProvider = self
                self.controller = controller
                controller.performRequests()
            }
        }

        func presentationAnchor(for _: ASAuthorizationController) -> ASPresentationAnchor {
            anchor
        }

        func authorizationController(controller _: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                finish(.failure(Failure.invalidCredential))
                return
            }
            do {
                let body = try payload(identityToken: credential.identityToken,
                                       authorizationCode: credential.authorizationCode,
                                       nonce: nonce, fullName: credential.fullName)
                finish(.success(body))
            } catch {
                finish(.failure(error))
            }
        }

        func authorizationController(controller _: ASAuthorizationController, didCompleteWithError error: Error) {
            finish(.failure(isCancellation(error) ? Failure.cancelled : Failure.failed))
        }

        func cancel() {
            controller?.cancel()
            finish(.failure(CancellationError()))
        }

        /// 취소와 delegate가 겹쳐도 continuation은 한 번만 완료한다.
        private func finish(_ result: Swift.Result<AppleSignIn, Error>) {
            guard let continuation else { return }
            self.continuation = nil
            controller = nil
            continuation.resume(with: result)
        }
    }
}
