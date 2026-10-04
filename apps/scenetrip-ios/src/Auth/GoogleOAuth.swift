import Foundation
import GoogleSignIn
import UIKit

/// 구글 로그인 — **GoogleSignIn SDK** 로 ID 토큰을 받는다 (MZ2AZ-336).
///
/// 백엔드 티켓이 정한 길이다. 로그인 창, 인가 코드 교환, nonce 대조를 구글이 관리하는 코드에 맡긴다.
/// (처음에는 SDK 없이 시스템 로그인 창 + PKCE 로 직접 구현했었다 — 서버가 받는 토큰은 같았지만
/// 보안 절차를 우리가 유지해야 했고 티켓과 달랐다. 2026-10-05 SDK 로 바꿨다.)
///
/// 필요한 것 둘이 Info.plist 에 있다: `GIDClientID`(iOS 클라이언트 ID)와 로그인 뒤 돌아올
/// URL scheme(클라이언트 ID 를 뒤집은 것). 돌아온 주소는 `AppRoot` 의 `.onOpenURL` 이 [handle] 로 넘긴다.
enum GoogleOAuth {
    /// iOS 클라이언트 ID — 비밀이 아니다(`docs/project/plans/social-login.md` §9). 서버의 `aud` 허용 목록에 있다.
    static let clientId = "700188854872-7v9hphkb4phavae7stepil7q6vp7lbig.apps.googleusercontent.com"

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

    /// 32 바이트 난수의 base64url. 로그인 시도마다 새로 만든다 — 남의 ID 토큰을 다시 보내는 것을 막는다.
    static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// 로그인 창을 띄우고 ID 토큰을 받아 온다.
    @MainActor
    static func signIn() async throws -> Result {
        guard let presenter = topViewController() else { throw Failure.failed }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId)
        let nonce = randomToken()
        do {
            let signed = try await GIDSignIn.sharedInstance.signIn(
                withPresenting: presenter, hint: nil, additionalScopes: nil, nonce: nonce
            )
            guard let token = signed.user.idToken?.tokenString else { throw Failure.failed }
            return Result(idToken: token, nonce: nonce)
        } catch let error as GIDSignInError where error.code == .canceled {
            throw Failure.cancelled
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.failed
        }
    }

    /// 우리 서버에서 로그아웃·탈퇴할 때 SDK 가 기억하는 구글 사용자도 지운다 — 다음 로그인에 계정을 다시 고른다.
    @MainActor
    static func forget() {
        GIDSignIn.sharedInstance.signOut()
    }

    /// 로그인 뒤 구글이 앱으로 되돌려 보낸 주소. 구글 것이면 `true`.
    @MainActor
    static func handle(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    /// 지금 맨 위에 떠 있는 화면 — 로그인 시트 위에 구글 창을 올려야 한다.
    @MainActor
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var top = scenes.flatMap(\.windows).first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
