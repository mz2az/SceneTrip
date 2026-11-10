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

/// 본문 사진 아래의 AI 변환 표시와 출처(MZ2AZ-528, Android·웹과 같은 규칙).
/// **AI 변환 표시는 고지라 늘 보이고**, 길게 늘어지는 출처 문장(공공누리 표기 등)은 「더 보기」 뒤로 접는다.
/// 확대 화면(`CommunityPhotoViewer`)의 출처는 접지 않는다.
struct CommunityPhotoNotice: Equatable {
    let aiGenerated: Bool
    /// 앞뒤 공백을 뗀 출처. 비어 있으면 nil — 접기 단추도 없다.
    let credit: String?

    init(block: TripPostBlock) {
        aiGenerated = block.aiGenerated == true
        let credit = CommunityRules.normalized(block.credit ?? "")
        self.credit = credit.isEmpty ? nil : credit
    }

    var isEmpty: Bool {
        !aiGenerated && credit == nil
    }

    var foldsCredit: Bool {
        credit != nil
    }

    func shownCredit(open: Bool) -> String? {
        open ? credit : nil
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

    /// 글 끝에 덧붙이는 구간의 제목(첫 줄) — 가이드 글은 끝에 「References」·「Photo credits」 를 글 블록으로 단다.
    /// Android `CommunityArticle.referenceHeadings`·웹 `REFERENCE_HEADINGS` 와 같은 목록이다(소문자로 비교).
    private static let referenceHeadings: Set<String> = [
        "references", "reference", "photo credits", "photo credit", "image credits",
        "참고 자료", "참고자료", "사진 출처", "이미지 출처", "출처",
    ]

    /// 덧붙임 구간의 첫 블록이면 그 제목(쓴 그대로의 첫 줄), 아니면 nil. 대소문자는 가리지 않는다.
    static func referenceHeading(_ block: TripPostBlock) -> String? {
        guard block.kind == .text, let text = block.text else { return nil }
        let normalized = CommunityRules.normalized(
            text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        ).precomposedStringWithCanonicalMapping
        let first = (normalized.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? "")
            .trimmingCharacters(in: .whitespaces)
        return referenceHeadings.contains(first.lowercased()) ? first : nil
    }

    /// 본문과 끝의 덧붙임(참고 자료·사진 출처) 구간으로 나눈다. 끝에서부터 첫 줄이 덧붙임 제목인 글 블록만 모으므로
    /// 본문 중간의 같은 말은 건드리지 않는다. 읽는 화면만 덧붙임을 한 줄로 접는다(작성 미리보기는 그대로).
    static func splitReferences(_ blocks: [TripPostBlock]) -> (body: [TripPostBlock], references: [TripPostBlock]) {
        var start = blocks.count
        while start > 0, referenceHeading(blocks[start - 1]) != nil {
            start -= 1
        }
        return (Array(blocks[..<start]), Array(blocks[start...]))
    }

    /// 접힌 줄의 이름 — 덧붙임 제목을 글에 쓴 그대로 잇는다(글 언어를 따른다).
    static func referencesLabel(_ references: [TripPostBlock]) -> String {
        references.compactMap(referenceHeading).joined(separator: " · ")
    }

    /// 접기 단추(References 줄·사진 출처 「더 보기」)가 VoiceOver 에 알릴 상태 — 번역 열쇠(한국어 원문).
    static func foldStateKey(open: Bool) -> String {
        open ? "펼침" : "접힘"
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

    /// 읽는 화면(글 화면·미리보기)의 블록 — 맨 위 대표 사진을 따로 그리지 않는다(K7, 본문 블록에 이미 있다).
    /// 서버 `bodyBlocks` 가 있으면 그것, 없으면 `legacy`. 어느 블록도 가리키지 않는 사진은 끝에 붙여 빠뜨리지 않고,
    /// 범위 밖(photoIndex >= photoCount)이거나 두 번째로 같은 사진을 가리키는 사진 블록은 뺀다 — 사진은 한 번씩만 보인다.
    static func readingBlocks(bodyBlocks: [TripPostBlock]?, body: String, photoCount: Int) -> [TripPostBlock] {
        guard let bodyBlocks, !bodyBlocks.isEmpty else { return legacy(body: body, photoCount: photoCount) }
        var seen = Set<Int>()
        var out: [TripPostBlock] = []
        for block in bodyBlocks {
            if block.kind == .photo {
                guard let index = block.photoIndex, (0 ..< photoCount).contains(index), seen.insert(index).inserted else { continue }
            }
            out.append(block)
        }
        for index in 0 ..< max(0, photoCount) where !seen.contains(index) {
            out.append(TripPostBlock(kind: .photo, photoIndex: index))
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
