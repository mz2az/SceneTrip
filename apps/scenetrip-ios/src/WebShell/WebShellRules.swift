import Foundation

/// 웹뷰 껍데기의 판단 규칙 (MZ2AZ-557, 계획 docs/project/plans/ios-webview-shell.md §2-2·§2-3). WebKit 없이 시험한다.
enum WebShellRules {
    /// 웹뷰 안 이동(링크·주소 바꾸기)을 어떻게 할 것인가.
    enum Navigation: Equatable {
        case allow
        /// Safari·해당 앱(메일·전화·네이버 지도·App Store)으로 넘기고 웹뷰에서는 취소한다.
        case openExternally
        case cancel
    }

    /// `target=_blank`·`window.open` — 새 웹뷰는 만들지 않는다.
    enum NewWindow: Equatable {
        case loadInPlace
        case openExternally
        case ignore
    }

    enum AuthProvider: String, Equatable {
        case google
        case apple
    }

    /// 웹의 nativeAuth.ts 와 같은 이름.
    static let handlerName = "scenetripAuth"
    /// 서버 로그·분석에서 껍데기 요청을 가른다.
    static let userAgentToken = "SceneTripShell/1"
    /// 웹은 이 문자열의 거절을 「사람이 닫았다」로 읽는다.
    static let cancelledMessage = "cancelled"
    static let failedMessage = "failed"

    /// 빌드에 넣은 웹 주소. 빈 값·틀린 값이면 nil — 껍데기를 끄고 네이티브 앱을 띄운다.
    /// 원격은 https 만, http 는 시뮬레이터 개발용 localhost·127.0.0.1 만.
    static func baseURL(_ raw: String) -> URL? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let components = URLComponents(string: text),
              let scheme = components.scheme?.lowercased(), let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil
        else { return nil }
        switch scheme {
        case "https":
            break
        case "http":
            guard ["localhost", "127.0.0.1"].contains(host.lowercased()) else { return nil }
        default:
            return nil
        }
        return components.url
    }

    static func navigation(url: URL?, isMainFrame: Bool, base: URL) -> Navigation {
        guard let url, let scheme = url.scheme?.lowercased() else { return .cancel }
        switch scheme {
        case "http", "https":
            if sameOrigin(url, base) {
                return .allow
            }
            return isMainFrame ? .openExternally : .allow
        case "about", "blob", "data":
            return .allow
        case "javascript":
            return .cancel
        default:
            return .openExternally
        }
    }

    static func newWindow(url: URL?, base: URL) -> NewWindow {
        guard let url, let scheme = url.scheme?.lowercased() else { return .ignore }
        switch scheme {
        case "about", "javascript":
            return .ignore
        case "http", "https":
            return sameOrigin(url, base) ? .loadInPlace : .openExternally
        default:
            return .openExternally
        }
    }

    /// 웹이 보낸 `{provider: 'google'|'apple'}`.
    static func authProvider(from body: Any) -> AuthProvider? {
        guard let dictionary = body as? [String: Any], let raw = dictionary["provider"] as? String else { return nil }
        return AuthProvider(rawValue: raw)
    }

    static func googleReply(idToken: String, nonce: String) -> [String: Any] {
        ["idToken": idToken, "nonce": nonce]
    }

    /// 이름이 없으면 NSNull — JS 에서 null 로 보인다.
    static func appleReply(identityToken: String, authorizationCode: String, nonce: String,
                           givenName: String?, familyName: String?) -> [String: Any]
    {
        [
            "identityToken": identityToken,
            "authorizationCode": authorizationCode,
            "nonce": nonce,
            "givenName": givenName.map { $0 as Any } ?? NSNull(),
            "familyName": familyName.map { $0 as Any } ?? NSNull(),
        ]
    }

    private static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        guard let schemeA = a.scheme?.lowercased(), let schemeB = b.scheme?.lowercased(), schemeA == schemeB,
              let hostA = a.host?.lowercased(), let hostB = b.host?.lowercased(), hostA == hostB
        else { return false }
        return effectivePort(a, schemeA) == effectivePort(b, schemeB)
    }

    private static func effectivePort(_ url: URL, _ scheme: String) -> Int {
        url.port ?? (scheme == "https" ? 443 : 80)
    }
}
