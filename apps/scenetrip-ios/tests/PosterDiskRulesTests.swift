import CryptoKit
import Foundation
@testable import SceneTrip
import XCTest

/// 작품 포스터 기기 보관 규칙 (MZ2AZ-507, 계획 docs/project/plans/poster-disk-cache.md 「규칙」·「순수 규칙」).
final class PosterDiskRulesTests: XCTestCase {
    private let day: TimeInterval = 24 * 60 * 60
    private let mb = 1024 * 1024
    private let base = Date(timeIntervalSince1970: 1_800_000_000)

    private func entry(_ name: String, _ bytes: Int, _ minutesAgo: Double) -> PosterDiskEntry {
        PosterDiskEntry(name: name, bytes: bytes, lastUsed: base.addingTimeInterval(-minutesAgo * 60))
    }

    // MARK: - 상수

    func testMaxAgeIsExactlyThirtyDays() {
        XCTAssertEqual(PosterDiskRules.maxAge, 30 * 24 * 60 * 60)
    }

    func testMaxBytesIsOneHundredMegabytes() {
        XCTAssertEqual(PosterDiskRules.maxBytes, 100 * 1024 * 1024)
    }

    func testEdgeIs360Pixels() {
        XCTAssertEqual(PosterDiskRules.edge, 360)
    }

    func testMaxDownloadBytesIs24Megabytes() {
        XCTAssertEqual(PosterDiskRules.maxDownloadBytes, 24 * 1024 * 1024)
    }

    // MARK: - 파일 이름 = 주소의 SHA-256 16진수(소문자 64자)

    func testFileNameMatchesKnownSha256Vector() {
        // FIPS 180-2 의 "abc" 시험 벡터.
        XCTAssertEqual(PosterDiskRules.fileName(url: "abc"),
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testFileNameMatchesCryptoKitDigestOfUrlString() {
        let url = "https://img.example.com/poster/드라마-1.jpg?w=600"
        let expected = SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(PosterDiskRules.fileName(url: url), expected)
    }

    func testFileNameIsLowercase64HexCharacters() {
        let name = PosterDiskRules.fileName(url: "https://img.example.com/poster/A.JPG")
        XCTAssertEqual(name.count, 64)
        XCTAssertTrue(name.allSatisfy { "0123456789abcdef".contains($0) }, "소문자 16진수만: \(name)")
    }

    func testSameUrlGivesSameNameAndDifferentUrlsGiveDifferentNames() {
        let first = PosterDiskRules.fileName(url: "https://img.example.com/poster/1.jpg")
        XCTAssertEqual(first, PosterDiskRules.fileName(url: "https://img.example.com/poster/1.jpg"))
        XCTAssertNotEqual(first, PosterDiskRules.fileName(url: "https://img.example.com/poster/2.jpg"))
        XCTAssertFalse(first.isEmpty)
    }

    // MARK: - 기간: 30일(정확히 30×24시간)을 넘었나

    func testNotExpiredJustUnderThirtyDays() {
        XCTAssertFalse(PosterDiskRules.expired(savedAt: base, now: base.addingTimeInterval(30 * day - 1)))
    }

    func testNotExpiredAtExactlyThirtyDays() {
        XCTAssertFalse(PosterDiskRules.expired(savedAt: base, now: base.addingTimeInterval(30 * day)))
    }

    func testExpiredJustOverThirtyDays() {
        XCTAssertTrue(PosterDiskRules.expired(savedAt: base, now: base.addingTimeInterval(30 * day + 1)))
    }

    func testExpiredLongAfterThirtyDays() {
        XCTAssertTrue(PosterDiskRules.expired(savedAt: base, now: base.addingTimeInterval(90 * day)))
    }

    // MARK: - 용량: 합계가 상한 이하가 될 때까지 오래 안 쓴 것부터, 같으면 이름순

    func testNoEvictionsWhenUnderLimit() {
        let entries = [entry("a", 10, 5), entry("b", 20, 1)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100), [])
    }

    func testNoEvictionsAtExactLimit() {
        let entries = [entry("a", 40, 5), entry("b", 60, 1)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100), [])
    }

    func testNoEvictionsForEmptyEntries() {
        XCTAssertEqual(PosterDiskRules.evictions([], maxBytes: 100), [])
    }

    func testOverLimitEvictsOldestLastUsedFirstUntilWithinLimit() {
        // 합계 150, 상한 100 — 가장 오래된 old(30) 를 빼도 120 이라 다음 mid(40) 까지 → 80.
        let entries = [
            entry("new", 50, 1),
            entry("old", 30, 60),
            entry("recent", 30, 10),
            entry("mid", 40, 30),
        ]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100), ["old", "mid"])
    }

    func testStopsAsSoonAsTotalReachesLimit() {
        // 합계 110, 상한 100 — 가장 오래된 하나(10)만 빼면 정확히 100.
        let entries = [entry("x", 10, 30), entry("y", 50, 20), entry("z", 50, 10)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100), ["x"])
    }

    func testTieOnLastUsedIsBrokenByName() {
        // 셋 다 같은 때. 합계 90, 상한 50 — 이름순 a, b 를 지우면 30.
        let entries = [entry("c", 30, 5), entry("a", 30, 5), entry("b", 30, 5)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 50), ["a", "b"])
    }

    func testOneHugeEntryIsEvicted() {
        let entries = [entry("huge", 200 * mb, 1)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100 * mb), ["huge"])
    }

    func testRecentlyUsedHugeEntryIsKeptWhenOlderOnesSuffice() {
        let entries = [entry("huge", 90 * mb, 1), entry("older", 20 * mb, 60)]
        XCTAssertEqual(PosterDiskRules.evictions(entries, maxBytes: 100 * mb), ["older"])
    }
}
