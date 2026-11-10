import Foundation

/// 블로그형 글쓰기의 초안 — 글 조각과 사진이 쓴 순서대로 놓인 목록 (MZ2AZ-547 K11, 웹 `community/articleDraft.ts`).
///
/// 저장 형식은 그대로다(계약의 `body` + `bodyBlocks`, MZ2AZ-428). 이 목록에서 본문(`story`)과 사진마다 「몇 번째 문단 뒤」(`placements`)를
/// 계산해 기존 `CommunityArticle.blocks` 에 넘긴다. 글 조각 하나에 빈 줄이 있으면 문단이 여럿이다.
enum CommunityDraftItem: Equatable, Identifiable {
    case text(id: UUID, text: String)
    case photo(id: UUID)

    var id: UUID {
        switch self {
        case let .text(id, _), let .photo(id): id
        }
    }
}

enum CommunityDraft {
    /// 글 조각을 쓴 순서대로 빈 줄("\n\n")로 이은 본문. 조각마다 앞뒤 빈칸을 떼고 빈 조각은 건너뛴다.
    static func story(_ items: [CommunityDraftItem]) -> String {
        items.compactMap { item -> String? in
            guard case let .text(_, text) = item else { return nil }
            let trimmed = CommunityRules.normalized(text)
            return trimmed.isEmpty ? nil : trimmed
        }
        .joined(separator: "\n\n")
    }

    /// 사진마다 「앞에 있는 문단 수」(`CommunityArticle.paragraphs` 로 센다) = 그 사진이 들어갈 자리(`afterParagraph`).
    /// 설명·출처·AI 표시는 `meta` 에서(없으면 빈 값·false). meta 의 `afterParagraph` 는 무시한다.
    static func placements(_ items: [CommunityDraftItem], meta: [UUID: CommunityPhotoPlacement]) -> [UUID: CommunityPhotoPlacement] {
        var out: [UUID: CommunityPhotoPlacement] = [:]
        var before = 0
        for item in items {
            switch item {
            case let .text(_, text):
                before += CommunityArticle.paragraphs(text).count
            case let .photo(id):
                var placement = meta[id] ?? CommunityPhotoPlacement(afterParagraph: before)
                placement.afterParagraph = before
                out[id] = placement
            }
        }
        return out
    }

    /// 목록의 사진 순서 — 첫 사진이 대표(목록 썸네일)다.
    static func photoOrder(_ items: [CommunityDraftItem]) -> [UUID] {
        items.compactMap { item in
            if case let .photo(id) = item {
                return id
            }
            return nil
        }
    }

    /// `at` 번째 항목 뒤(−1 이면 맨 앞)에 사진들을 넣는다. 이미 있는 사진 id·같은 id 두 번은 다시 넣지 않는다.
    /// 넣을 자리는 0...items.count 로 자른다. 넣을 것이 없으면 그대로.
    static func insertPhotos(_ items: [CommunityDraftItem], after at: Int, ids: [UUID]) -> [CommunityDraftItem] {
        var present = Set(photoOrder(items))
        var fresh: [CommunityDraftItem] = []
        for id in ids where !present.contains(id) {
            present.insert(id)
            fresh.append(.photo(id: id))
        }
        guard !fresh.isEmpty else { return items }
        let cut = min(max(at + 1, 0), items.count)
        return Array(items[..<cut]) + fresh + Array(items[cut...])
    }

    /// 사진 칸에서 빠진 사진을 목록에서도 뺀다(글 조각은 그대로).
    static func dropMissingPhotos(_ items: [CommunityDraftItem], slotIds: [UUID]) -> [CommunityDraftItem] {
        let keep = Set(slotIds)
        return items.filter { item in
            if case let .photo(id) = item {
                return keep.contains(id)
            }
            return true
        }
    }

    /// 모양을 다듬는다 — 글 조각이 하나도 없으면 빈 조각 하나를 끝에, 사진이 맨 끝이면 그 뒤에 빈 조각(이어서 쓰게),
    /// 빈(공백뿐인) 글 조각이 둘 이어지면 하나로(앞의 것을 남긴다). 새 조각의 id 는 `newId` 로 만든다.
    static func normalize(_ items: [CommunityDraftItem], newId: () -> UUID) -> [CommunityDraftItem] {
        var out: [CommunityDraftItem] = []
        for item in items {
            if isBlankText(item), let previous = out.last, isBlankText(previous) {
                continue
            }
            out.append(item)
        }
        let hasText = out.contains {
            if case .text = $0 {
                true
            } else {
                false
            }
        }
        let endsWithPhoto = if case .photo = out.last {
            true
        } else {
            false
        }
        if !hasText || endsWithPhoto {
            out.append(.text(id: newId(), text: ""))
        }
        return out
    }

    /// `from` 의 항목을 `to` 자리로 옮긴다(빼고 그 자리에 끼운다). 둘 중 하나라도 범위 밖이면 그대로.
    static func moveItem(_ items: [CommunityDraftItem], from: Int, to: Int) -> [CommunityDraftItem] {
        guard items.indices.contains(from), items.indices.contains(to) else { return items }
        var next = items
        let item = next.remove(at: from)
        next.insert(item, at: to)
        return next
    }

    /// 「사진 넣기」 의 자리 — 마지막으로 글을 쓰던 조각과 커서(UTF-16 위치)에서 그 조각을 둘로 나눈다.
    /// 돌려주는 `insertAfter` 는 사진을 넣을 자리(그 항목 뒤, −1 이면 맨 앞)다.
    /// - 커서가 없거나 그 id 의 항목이 없으면: 목록 그대로, 마지막 항목 뒤(`items.count - 1`).
    /// - 그 항목이 사진이면: 그대로, 그 사진 뒤.
    /// - 커서 뒤가 (빈칸 빼고) 비었으면: 나누지 않고 그 조각 뒤(index).
    /// - 커서 앞이 (빈칸 빼고) 비었으면: 나누지 않고 그 조각 앞(index − 1).
    /// - 그 밖: 앞부분(끝 빈칸 뗌)은 그 조각 id 그대로, 뒷부분(앞 빈칸 뗌)은 `newId` 로 새 조각을 바로 뒤에. 그 조각 뒤(index).
    static func split(_ items: [CommunityDraftItem], caret: (id: UUID, at: Int)?, newId: () -> UUID) -> (items: [CommunityDraftItem], insertAfter: Int) {
        guard let caret, let index = items.firstIndex(where: { $0.id == caret.id }) else {
            return (items, items.count - 1)
        }
        guard case let .text(id, text) = items[index] else { return (items, index) }
        let utf16 = Array(text.utf16)
        let cut = min(max(0, caret.at), utf16.count)
        let head = String(decoding: utf16[..<cut], as: UTF16.self)
        let tail = String(decoding: utf16[cut...], as: UTF16.self)
        let before = String(head.reversed().drop { $0.isWhitespace }.reversed())
        let after = String(tail.drop { $0.isWhitespace })
        if after.isEmpty {
            return (items, index)
        }
        if before.isEmpty {
            return (items, index - 1)
        }
        var next = items
        next[index] = .text(id: id, text: before)
        next.insert(.text(id: newId(), text: after), at: index + 1)
        return (next, index)
    }

    private static func isBlankText(_ item: CommunityDraftItem) -> Bool {
        if case let .text(_, text) = item {
            return CommunityRules.normalized(text).isEmpty
        }
        return false
    }
}
