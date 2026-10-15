@testable import SceneTrip
import XCTest

final class ReleaseResourcesTests: XCTestCase {
    private var bundle: Bundle {
        Bundle(for: Self.self)
    }

    func testAppBundleDeclaresItsUserDefaultsReason() throws {
        let url = try XCTUnwrap(bundle.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let data = try Data(contentsOf: url)
        let manifest = try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
        let types = try XCTUnwrap(manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let defaults = try XCTUnwrap(types.first { ($0["NSPrivacyAccessedAPIType"] as? String) == "NSPrivacyAccessedAPICategoryUserDefaults" })
        let reasons = try XCTUnwrap(defaults["NSPrivacyAccessedAPITypeReasons"] as? [String])
        XCTAssertTrue(reasons.contains("CA92.1"))
    }

    func testEnglishAndKoreanLocationPermissionMessagesAreBundled() throws {
        for language in ["en", "ko"] {
            let url = try XCTUnwrap(bundle.url(forResource: "InfoPlist", withExtension: "strings", subdirectory: nil, localization: language))
            let data = try Data(contentsOf: url)
            let values = try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String])
            let purpose = try XCTUnwrap(values["NSLocationWhenInUseUsageDescription"])
            XCTAssertFalse(purpose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if language == "en" {
                XCTAssertTrue(purpose.contains("location"))
                XCTAssertTrue(purpose.contains("filming"))
            }
        }
    }

    func testEnglishAndKoreanTrackingPermissionMessagesAreBundled() throws {
        let expected = [
            "ko": "광고가 SceneTrip 설치와 앱 사용(장소 저장·코스 만들기)으로 이어졌는지 측정하는 데 사용합니다. 허용하지 않아도 모든 기능을 쓸 수 있어요.",
            "en": "Used to measure whether our ads led to SceneTrip installs and in-app actions (saving places, creating courses). Everything in the app works even if you don't allow it.",
        ]
        for (language, purpose) in expected {
            let url = try XCTUnwrap(bundle.url(forResource: "InfoPlist", withExtension: "strings", subdirectory: nil, localization: language))
            let data = try Data(contentsOf: url)
            let values = try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String])
            XCTAssertEqual(values["NSUserTrackingUsageDescription"], purpose)
        }
    }
}
