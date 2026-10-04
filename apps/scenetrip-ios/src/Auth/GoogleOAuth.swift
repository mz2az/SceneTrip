import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// 구글 로그인 — 시스템 로그인 창(`ASWebAuthenticationSession`)으로 ID 토큰을 받는다 (MZ2AZ-336).
///
/// ## 왜 GoogleSignIn SDK 가 아닌가
///
/// SDK 가 하는 일은 「시스템 로그인 창을 띄워 인가 코드를 받고(PKCE), 토큰 창구에서 ID 토큰으로
/// 바꾼다」이다. 그 일은 시스템 프레임워크만으로 된다. SDK 를 들이면 AppAuth·GTMAppAuth·
/// GTMSessionFetcher·AppCheck 등 Objective-C 패키지 대여섯이 Bazel 그래프에 딸려 온다 —
/// 얻는 것은 구글 로고 단추 하나다. 서버가 받는 것(ID 토큰, `aud` = iOS 클라이언트 ID,
/// `nonce`)은 같다.
///
/// 창을 띄울 때 `callbackURLScheme` 을 직접 넘기므로 Info.plist 에 URL scheme 을 등록하지 않아도 된다.
enum GoogleOAuth {
    /// iOS 클라이언트 ID — 비밀이 아니다(`docs/project/plans/social-login.md` §9). 서버의 `aud` 허용 목록에 있다.
    static let clientId = "700188854872-7v9hphkb4phavae7stepil7q6vp7lbig.apps.googleusercontent.com"
    /// 클라이언트 ID 를 뒤집은 것 — 구글이 iOS 클라이언트에 허용하는 되돌아올 주소다.
    static let scheme = "com.googleusercontent.apps.700188854872-7v9hphkb4phavae7stepil7q6vp7lbig"
    static let redirectURI = "\(scheme):/oauth2redirect"

    struct Result {
        let idToken: String
        /// 서버에 **원문 그대로** 보낸다. 구글이 ID 토큰 안에 넣어 준 값과 같은지 서버가 본다.
        let nonce: String
    }

    enum Failure: Error {
        /// 사람이 창을 닫았다 — 오류로 알리지 않는다.
        case cancelled
        case failed
    }

    /// 32 바이트 난수의 base64url. nonce·state·PKCE verifier 에 쓴다 — 시도마다 새로 만든다.
    static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64url(Data(bytes))
    }

    /// PKCE `S256` — verifier 의 SHA-256 을 base64url 로.
    static func challenge(for verifier: String) -> String {
        base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func authorizationURL(nonce: String, state: String, challenge: String) -> URL {
        var parts = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        parts.queryItems = [
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        return parts.url!
    }

    /// 되돌아온 주소에서 인가 코드를 꺼낸다. `state` 가 내가 보낸 것과 다르면 남이 끼어든 것이다.
    static func code(from callback: URL, expectedState: String) -> String? {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard items.first(where: { $0.name == "state" })?.value == expectedState else { return nil }
        return items.first(where: { $0.name == "code" })?.value
    }

    /// 로그인 창을 띄우고 ID 토큰을 받아 온다.
    @MainActor
    static func signIn() async throws -> Result {
        let nonce = randomToken()
        let state = randomToken()
        let verifier = randomToken()
        let callback = try await authorize(
            url: authorizationURL(nonce: nonce, state: state, challenge: challenge(for: verifier))
        )
        guard let code = code(from: callback, expectedState: state) else { throw Failure.failed }
        return try await Result(idToken: exchange(code: code, verifier: verifier), nonce: nonce)
    }

    @MainActor
    private static func authorize(url: URL) async throws -> URL {
        let anchor = PresentationAnchor()
        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { callback, error in
                _ = anchor // 창이 닫힐 때까지 붙들어 둔다 — 세션은 이것을 약하게만 든다.
                if let callback {
                    continuation.resume(returning: callback)
                } else if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    continuation.resume(throwing: Failure.cancelled)
                } else {
                    continuation.resume(throwing: Failure.failed)
                }
            }
            session.presentationContextProvider = anchor
            // 사파리의 구글 로그인 상태를 같이 쓴다 — 이미 로그인돼 있으면 계정만 고르면 된다.
            session.prefersEphemeralWebBrowserSession = false
            if !session.start() {
                continuation.resume(throwing: Failure.failed)
            }
        }
    }

    /// 인가 코드를 ID 토큰으로 바꾼다. iOS 클라이언트는 비밀값이 없다 — PKCE verifier 가 그 몫을 한다.
    private static func exchange(code: String, verifier: String) async throws -> String {
        struct Tokens: Decodable {
            let idToken: String
            enum CodingKeys: String, CodingKey { case idToken = "id_token" }
        }
        var form = URLComponents()
        form.queryItems = [
            URLQueryItem(name: "grant_type", value: "authorization_code"),
            URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "code_verifier", value: verifier),
        ]
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data((form.percentEncodedQuery ?? "").utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let tokens = try? JSONDecoder().decode(Tokens.self, from: data)
        else { throw Failure.failed }
        return tokens.idToken
    }

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private final class PresentationAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
        func presentationAnchor(for _: ASWebAuthenticationSession) -> ASPresentationAnchor {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}
