import AuthenticationServices
import Foundation
@testable import SceneTrip
import XCTest

final class AppleOAuthTests: XCTestCase {
    private let nonce = "test-raw-nonce-at-least-16"

    func testOnlyAppleCancellationIsSilent() {
        XCTAssertTrue(AppleOAuth.isCancellation(NSError(domain: ASAuthorizationError.errorDomain,
                                                        code: ASAuthorizationError.canceled.rawValue)))
        XCTAssertFalse(AppleOAuth.isCancellation(NSError(domain: ASAuthorizationError.errorDomain,
                                                         code: ASAuthorizationError.failed.rawValue)))
        XCTAssertFalse(AppleOAuth.isCancellation(URLError(.timedOut)))
    }

    func testAppleNonceIsSha256LowercaseHexAndServerKeepsRawNonce() throws {
        XCTAssertEqual(AppleOAuth.hashedNonce("abc"),
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        let raw = try AppleOAuth.randomNonce()
        let body = try AppleOAuth.payload(identityToken: Data("test-id-token".utf8),
                                          authorizationCode: Data("test-code".utf8), nonce: raw)
        XCTAssertEqual(body.nonce, raw)
        XCTAssertNotEqual(body.nonce, AppleOAuth.hashedNonce(raw))
    }

    func testEveryAttemptHasAtLeast32BytesOfFreshSecureRandomness() throws {
        let values = try (0 ..< 16).map { _ in try AppleOAuth.randomNonce() }
        XCTAssertEqual(Set(values).count, values.count)
        for value in values {
            XCTAssertEqual(value.count, 43)
            XCTAssertNil(value.range(of: "[^A-Za-z0-9_-]", options: .regularExpression))
        }
    }

    func testFirstLoginSendsBothNamesWithIdentityTokenAndCode() throws {
        var name = PersonNameComponents()
        name.givenName = "  여행  "
        name.familyName = " 김 "
        let body = try AppleOAuth.payload(identityToken: Data("test-id-token".utf8),
                                          authorizationCode: Data("test-code".utf8), nonce: nonce, fullName: name)
        XCTAssertEqual(body.identityToken, "test-id-token")
        XCTAssertEqual(body.authorizationCode, "test-code")
        XCTAssertEqual(body.givenName, "여행")
        XCTAssertEqual(body.familyName, "김")
    }

    func testReturningLoginWorksWithoutName() throws {
        let body = try AppleOAuth.payload(identityToken: Data("test-id-token".utf8),
                                          authorizationCode: Data("test-code".utf8), nonce: nonce)
        XCTAssertNil(body.givenName)
        XCTAssertNil(body.familyName)
    }

    func testIncompleteOrNonUtf8CredentialsCannotReachServer() {
        let good = Data("test-value".utf8)
        for bad in [nil, Data(), Data(" \n".utf8), Data([0xFF])] as [Data?] {
            XCTAssertThrowsError(try AppleOAuth.payload(identityToken: bad, authorizationCode: good, nonce: nonce))
            XCTAssertThrowsError(try AppleOAuth.payload(identityToken: good, authorizationCode: bad, nonce: nonce))
        }
        XCTAssertThrowsError(try AppleOAuth.payload(identityToken: good, authorizationCode: good, nonce: ""))
        XCTAssertThrowsError(try AppleOAuth.payload(identityToken: good, authorizationCode: good, nonce: "too-short"))
    }
}
