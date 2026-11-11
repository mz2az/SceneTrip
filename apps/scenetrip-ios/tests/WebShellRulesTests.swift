import Foundation
@testable import SceneTrip
import XCTest

/// 계획 docs/project/plans/ios-webview-shell.md §2-2·§2-3·§2-4 (MZ2AZ-557).
final class WebShellRulesTests: XCTestCase {
    private let base = URL(string: "https://dev.scenetrip.io")!

    private func url(_ raw: String) -> URL? { URL(string: raw) }

    // MARK: - baseURL

    func testBaseURLAcceptsHttpsAndTrimsWhitespace() {
        XCTAssertEqual(WebShellRules.baseURL("https://dev.scenetrip.io"), URL(string: "https://dev.scenetrip.io"))
        XCTAssertEqual(WebShellRules.baseURL("https://app.scenetrip.io/"), URL(string: "https://app.scenetrip.io/"))
        XCTAssertEqual(WebShellRules.baseURL("  https://dev.scenetrip.io \n")?.host, "dev.scenetrip.io")
        XCTAssertEqual(WebShellRules.baseURL("https://app.scenetrip.io/")?.host, "app.scenetrip.io")
    }

    func testBaseURLEmptyIsOff() {
        XCTAssertNil(WebShellRules.baseURL(""))
        XCTAssertNil(WebShellRules.baseURL("   "))
    }

    func testBaseURLRejectsPlainHttpRemoteHost() {
        XCTAssertNil(WebShellRules.baseURL("http://dev.scenetrip.io"))
        XCTAssertNil(WebShellRules.baseURL("http://app.scenetrip.io/"))
    }

    func testBaseURLAllowsLocalhostHttpForSimulatorDev() {
        XCTAssertEqual(WebShellRules.baseURL("http://localhost:5173"), URL(string: "http://localhost:5173"))
        XCTAssertEqual(WebShellRules.baseURL("http://127.0.0.1:5173"), URL(string: "http://127.0.0.1:5173"))
    }

    func testBaseURLRejectsNonURLsAndOtherSchemes() {
        XCTAssertNil(WebShellRules.baseURL("not a url"))
        XCTAssertNil(WebShellRules.baseURL("ftp://x.y"))
        XCTAssertNil(WebShellRules.baseURL("https://"))
    }

    func testBaseURLRejectsUserinfo() {
        XCTAssertNil(WebShellRules.baseURL("https://user:pw@dev.scenetrip.io"))
    }

    // MARK: - navigation

    func testNavigationNilIsCancelled() {
        XCTAssertEqual(WebShellRules.navigation(url: nil, isMainFrame: true, base: base), .cancel)
        XCTAssertEqual(WebShellRules.navigation(url: nil, isMainFrame: false, base: base), .cancel)
    }

    func testNavigationSameOriginIsAllowed() {
        for main in [true, false] {
            XCTAssertEqual(WebShellRules.navigation(url: url("https://dev.scenetrip.io/map"), isMainFrame: main, base: base), .allow)
            XCTAssertEqual(WebShellRules.navigation(url: url("https://DEV.SceneTrip.io/x?y=1#z"), isMainFrame: main, base: base), .allow)
            XCTAssertEqual(WebShellRules.navigation(url: url("https://dev.scenetrip.io:443/x"), isMainFrame: main, base: base), .allow)
        }
    }

    func testNavigationOtherOriginMainFrameGoesOutsideSubFrameStays() {
        let other = url("https://accounts.google.com/o/oauth2")
        XCTAssertEqual(WebShellRules.navigation(url: other, isMainFrame: true, base: base), .openExternally)
        XCTAssertEqual(WebShellRules.navigation(url: other, isMainFrame: false, base: base), .allow)
        XCTAssertEqual(WebShellRules.navigation(url: url("https://map.naver.com/p/entry"), isMainFrame: true, base: base),
                       .openExternally)
        XCTAssertEqual(WebShellRules.navigation(url: url("https://www.youtube.com/embed/x"), isMainFrame: false, base: base),
                       .allow)
    }

    func testNavigationDifferentSchemeOrPortIsAnotherOrigin() {
        XCTAssertEqual(WebShellRules.navigation(url: url("http://dev.scenetrip.io/x"), isMainFrame: true, base: base),
                       .openExternally)
        XCTAssertEqual(WebShellRules.navigation(url: url("https://dev.scenetrip.io:8443/x"), isMainFrame: true, base: base),
                       .openExternally)
        XCTAssertEqual(WebShellRules.navigation(url: url("https://app.scenetrip.io/x"), isMainFrame: true, base: base),
                       .openExternally)
    }

    func testNavigationLocalhostBaseMatchesPort() {
        let local = URL(string: "http://localhost:5173")!
        XCTAssertEqual(WebShellRules.navigation(url: url("http://localhost:5173/map"), isMainFrame: true, base: local), .allow)
        XCTAssertEqual(WebShellRules.navigation(url: url("http://localhost:8081/v1"), isMainFrame: true, base: local),
                       .openExternally)
    }

    func testNavigationBlankBlobDataAreAllowed() {
        for main in [true, false] {
            XCTAssertEqual(WebShellRules.navigation(url: url("about:blank"), isMainFrame: main, base: base), .allow)
            XCTAssertEqual(WebShellRules.navigation(url: url("blob:https://dev.scenetrip.io/1234-abcd"),
                                                    isMainFrame: main, base: base), .allow)
            XCTAssertEqual(WebShellRules.navigation(url: url("data:text/plain;base64,SGk="), isMainFrame: main, base: base),
                           .allow)
        }
    }

    func testNavigationNonWebSchemesGoOutside() {
        let raws = [
            "mailto:hello@scenetrip.io",
            "tel:+821012345678",
            "sms:+821012345678",
            "nmap://route/public?dlat=37.5&dlng=127.0&appname=io.scenetrip",
            "kakaomap://look?p=37.5,127.0",
            "itms-apps://apps.apple.com/app/id123",
        ]
        for raw in raws {
            XCTAssertEqual(WebShellRules.navigation(url: url(raw), isMainFrame: true, base: base), .openExternally, raw)
        }
        XCTAssertEqual(WebShellRules.navigation(url: url("https://apps.apple.com/app/id123"), isMainFrame: true, base: base),
                       .openExternally)
    }

    func testNavigationJavascriptIsCancelled() {
        XCTAssertEqual(WebShellRules.navigation(url: url("javascript:alert(1)"), isMainFrame: true, base: base), .cancel)
        XCTAssertEqual(WebShellRules.navigation(url: url("javascript:alert(1)"), isMainFrame: false, base: base), .cancel)
    }

    // MARK: - newWindow

    func testNewWindowNilOrBlankIsIgnored() {
        XCTAssertEqual(WebShellRules.newWindow(url: nil, base: base), .ignore)
        XCTAssertEqual(WebShellRules.newWindow(url: url("about:blank"), base: base), .ignore)
    }

    func testNewWindowSameOriginLoadsInPlace() {
        XCTAssertEqual(WebShellRules.newWindow(url: url("https://dev.scenetrip.io/place/1"), base: base), .loadInPlace)
        XCTAssertEqual(WebShellRules.newWindow(url: url("https://DEV.scenetrip.io:443/"), base: base), .loadInPlace)
    }

    func testNewWindowOtherOriginGoesOutside() {
        XCTAssertEqual(WebShellRules.newWindow(url: url("https://map.naver.com/p/entry/place/1"), base: base), .openExternally)
        XCTAssertEqual(WebShellRules.newWindow(url: url("http://dev.scenetrip.io/x"), base: base), .openExternally)
    }

    func testNewWindowNonWebSchemesGoOutside() {
        XCTAssertEqual(WebShellRules.newWindow(url: url("mailto:hello@scenetrip.io"), base: base), .openExternally)
        XCTAssertEqual(WebShellRules.newWindow(url: url("nmap://route/public?dlat=37.5&dlng=127.0"), base: base),
                       .openExternally)
        XCTAssertEqual(WebShellRules.newWindow(url: url("itms-apps://apps.apple.com/app/id123"), base: base), .openExternally)
    }

    func testNewWindowJavascriptIsIgnored() {
        XCTAssertEqual(WebShellRules.newWindow(url: url("javascript:void(0)"), base: base), .ignore)
    }

    // MARK: - 로그인 메시지

    func testAuthProviderParsesGoogleAndApple() {
        XCTAssertEqual(WebShellRules.authProvider(from: ["provider": "google"]), .google)
        XCTAssertEqual(WebShellRules.authProvider(from: ["provider": "apple"]), .apple)
        XCTAssertEqual(WebShellRules.authProvider(from: ["provider": "google"] as NSDictionary), .google)
    }

    func testAuthProviderRejectsAnythingElse() {
        XCTAssertNil(WebShellRules.authProvider(from: ["provider": "naver"]))
        XCTAssertNil(WebShellRules.authProvider(from: ["provider": "Google"]))
        XCTAssertNil(WebShellRules.authProvider(from: ["provider": ""]))
        XCTAssertNil(WebShellRules.authProvider(from: ["other": "google"]))
        XCTAssertNil(WebShellRules.authProvider(from: [String: Any]()))
        XCTAssertNil(WebShellRules.authProvider(from: "google"))
        XCTAssertNil(WebShellRules.authProvider(from: 42))
        XCTAssertNil(WebShellRules.authProvider(from: ["provider": 1] as NSDictionary))
        XCTAssertNil(WebShellRules.authProvider(from: NSNull()))
    }

    func testGoogleReplyHasExactlyIdTokenAndNonce() {
        let reply = WebShellRules.googleReply(idToken: "a.b.c", nonce: "raw-nonce-123456")
        XCTAssertEqual(Set(reply.keys), ["idToken", "nonce"])
        XCTAssertEqual(reply["idToken"] as? String, "a.b.c")
        XCTAssertEqual(reply["nonce"] as? String, "raw-nonce-123456")
    }

    func testAppleReplyCarriesAllFieldsAndNullNames() {
        let full = WebShellRules.appleReply(identityToken: "jwt", authorizationCode: "code", nonce: "raw-nonce-123456",
                                            givenName: "길", familyName: "심")
        XCTAssertEqual(Set(full.keys), ["identityToken", "authorizationCode", "nonce", "givenName", "familyName"])
        XCTAssertEqual(full["identityToken"] as? String, "jwt")
        XCTAssertEqual(full["authorizationCode"] as? String, "code")
        XCTAssertEqual(full["nonce"] as? String, "raw-nonce-123456")
        XCTAssertEqual(full["givenName"] as? String, "길")
        XCTAssertEqual(full["familyName"] as? String, "심")

        let noNames = WebShellRules.appleReply(identityToken: "jwt", authorizationCode: "code", nonce: "raw-nonce-123456",
                                               givenName: nil, familyName: nil)
        XCTAssertEqual(Set(noNames.keys), ["identityToken", "authorizationCode", "nonce", "givenName", "familyName"])
        XCTAssertTrue(noNames["givenName"] is NSNull)
        XCTAssertTrue(noNames["familyName"] is NSNull)
    }

    // MARK: - 상수

    func testConstants() {
        XCTAssertEqual(WebShellRules.handlerName, "scenetripAuth")
        XCTAssertEqual(WebShellRules.cancelledMessage, "cancelled")
        XCTAssertTrue(WebShellRules.userAgentToken.contains("SceneTripShell"))
    }
}
