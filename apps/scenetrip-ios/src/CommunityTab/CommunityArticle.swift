import Foundation
import SceneApiClient

/// 사진 ID에 연결해 초안에서 사진을 빼도 남은 사진의 설명·삽입 위치를 유지한다.
struct CommunityPhotoPlacement: Equatable {
    static let captionLimit = 500
    static let creditLimit = 1000
    var afterParagraph: Int
    var caption = ""
    var credit = ""
    var aiGenerated = false
    var captionLength: Int {
        CommunityRules.normalized(caption).utf16.count
    }

    var creditLength: Int {
        CommunityRules.normalized(credit).utf16.count
    }

    var metadataValid: Bool {
        captionLength <= Self.captionLimit && creditLength <= Self.creditLimit
    }
}

enum CommunityArticle {
    static let paragraphLimit = 64

    static func paragraphs(_ body: String) -> [String] {
        body.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n\n")
            .map(CommunityRules.normalized).filter { !$0.isEmpty }
    }

    static func plainBody(_ body: String) -> String {
        paragraphs(body).joined(separator: "\n\n")
    }

    static func canCompose(_ body: String) -> Bool {
        let parts = paragraphs(body)
        return !parts.isEmpty && parts.count <= paragraphLimit
    }

    static func blocks(body: String, photoIds: [UUID], placements: [UUID: CommunityPhotoPlacement]) -> [TripPostBlock] {
        let parts = paragraphs(body)
        var out: [TripPostBlock] = []
        for position in 0 ... parts.count {
            if position > 0 {
                out.append(TripPostBlock(kind: .text, text: parts[position - 1]))
            }
            for (index, id) in photoIds.enumerated() {
                let place = placements[id] ?? CommunityPhotoPlacement(afterParagraph: min(index + 1, parts.count))
                guard min(max(0, place.afterParagraph), parts.count) == position else { continue }
                out.append(TripPostBlock(kind: .photo, photoIndex: index,
                                         caption: nonempty(place.caption), credit: nonempty(place.credit),
                                         aiGenerated: place.aiGenerated ? true : nil))
            }
        }
        return out
    }

    /// 옛 본문·사진은 읽을 때만 배치한다. 저장된 원문이나 사진을 다시 쓰지 않는다.
    static func legacy(body: String, photoCount: Int) -> [TripPostBlock] {
        let parts = paragraphs(body)
        var out: [TripPostBlock] = []
        var nextPhoto = 0
        for (offset, text) in parts.enumerated() {
            out.append(TripPostBlock(kind: .text, text: text))
            let target = (offset + 1) * photoCount / max(1, parts.count)
            while nextPhoto < target {
                out.append(TripPostBlock(kind: .photo, photoIndex: nextPhoto))
                nextPhoto += 1
            }
        }
        while nextPhoto < photoCount {
            out.append(TripPostBlock(kind: .photo, photoIndex: nextPhoto))
            nextPhoto += 1
        }
        return out
    }

    private static func nonempty(_ text: String) -> String? {
        let text = CommunityRules.normalized(text)
        return text.isEmpty ? nil : text
    }
}
