import CryptoKit
import Foundation

// 작품 포스터의 기기 보관 규칙 (MZ2AZ-507, 계획 docs/project/plans/poster-disk-cache.md).
// 포스터는 방송사·OTT 서버에 있어 우리가 줄일 수 없다 — 앱이 받아 둔다. 우리 서버 사진은 여기 쓰지 않는다.

/// 기기에 보관한 포스터 파일 하나.
struct PosterDiskEntry: Equatable {
    let name: String
    let bytes: Int
    /// 마지막으로 쓴 때.
    let lastUsed: Date
}

enum PosterDiskRules {
    /// 보관 기간 — 저장한 지 이만큼 지나면 다시 받는다.
    static let maxAge: TimeInterval = 30 * 24 * 60 * 60
    /// 보관 합계 상한(바이트).
    static let maxBytes = 100 * 1024 * 1024
    /// 화면에 그릴 때 줄이는 긴 변(px).
    static let edge = 360
    /// 저장할 응답의 상한(바이트) — 이보다 크면 그리기만 하고 저장하지 않는다.
    static let maxDownloadBytes = 24 * 1024 * 1024

    /// 파일 이름 — 포스터 주소의 SHA-256 16진수(소문자 64자).
    static func fileName(url: String) -> String {
        SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// 저장한 지 `maxAge` 를 넘었나.
    static func expired(savedAt: Date, now: Date) -> Bool {
        now.timeIntervalSince(savedAt) > maxAge
    }

    /// 합계가 `maxBytes` 이하가 될 때까지 지울 이름들 — 마지막으로 쓴 때가 오래된 것부터, 같으면 이름순.
    static func evictions(_ entries: [PosterDiskEntry], maxBytes: Int) -> [String] {
        var total = entries.reduce(0) { $0 + $1.bytes }
        var out: [String] = []
        for entry in entries.sorted(by: { ($0.lastUsed, $0.name) < ($1.lastUsed, $1.name) }) where total > maxBytes {
            out.append(entry.name)
            total -= entry.bytes
        }
        return out
    }
}
