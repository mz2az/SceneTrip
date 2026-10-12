@testable import SceneTrip
import SwiftUI
import XCTest

final class AppThemeTests: XCTestCase {
    func testUnknownOrMissingChoiceUsesSystem() {
        XCTAssertEqual(AppTheme.resolve(nil), .system)
        XCTAssertEqual(AppTheme.resolve("unknown"), .system)
        XCTAssertNil(AppTheme.system.colorScheme)
        XCTAssertEqual(AppTheme.light.colorScheme, .light)
        XCTAssertEqual(AppTheme.dark.colorScheme, .dark)
    }

    func testChoiceSurvivesReadingPreferencesAgain() throws {
        let suite = "theme-test-\(UUID().uuidString)"
        let preferences = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { preferences.removePersistentDomain(forName: suite) }
        AppTheme.dark.store(in: preferences)
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertEqual(AppTheme.resolve(reopened.string(forKey: AppTheme.key)), .dark)
        AppTheme.system.store(in: preferences)
        XCTAssertNil(AppTheme.resolve(reopened.string(forKey: AppTheme.key)).colorScheme)
    }
}
