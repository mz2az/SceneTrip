import Foundation

// 화면에 적는 말의 작은 규칙 셋 (MZ2AZ-372 의 7번). 셋 다 값만 보는 순수 함수다.

/// 수가 든 문구. 영어는 하나일 때 낱말이 달라진다 — 「1 places」 가 아니라 「1 place」.
///
/// 번역 표의 관례 그대로다(`리뷰 %d|하나`): 하나일 때는 `원문|하나` 를 찾고, 그 줄이 없으면 여럿의 번역으로
/// 물러선다. 한국어는 수에 따라 달라지지 않아 원문 그대로다.
func trCount(_ korean: String, _ count: Int) -> String {
    String(format: count == 1 ? tr(korean, at: "하나") : tr(korean), count)
}

/// 지도 핀 아래 이름표.
///
/// 영어 이름은 길다 — 「Korea National University of Arts Seokgwan-dong Campus …」 처럼 80자가 넘는 것이
/// 한 줄로 뻗어 옆 핀과 지도를 덮었다. 이름표는 **어느 핀인지 알아보는** 데 쓰는 것이라 앞머리면 된다 —
/// 전체 이름은 눌러 연 카드에 있다.
enum PinCaption {
    /// 이보다 길면 자른다.
    static let limit = 28
    /// 이름표의 폭(pt). 넘으면 SDK 가 줄을 바꾼다.
    static let width: CGFloat = 132

    static func text(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > limit else { return trimmed }
        let head = trimmed.prefix(limit - 1).trimmingCharacters(in: .whitespaces)
        return head + "…"
    }
}

/// 자동완성 작품 카드의 **다른 이름 배지**.
///
/// 「첫 별칭 = 영어 제목」 이라고 가정했었다. 새 데이터의 별칭은 순서가 정해져 있지 않아 일본어·중국어 제목이
/// 첫째로 오는 작품이 있고, 그것이 배지로 떴다. 이제 고른다:
///
/// 1. 사용자가 친 말이 별칭에 걸렸으면 **그 표기** — 왜 이 작품이 나왔는지를 보여 준다(어느 글자든).
/// 2. 아니면 **로마자로 적힌 첫 별칭**(영어 제목). 제목과 같은 글자면 건너뛴다.
/// 3. 그런 것이 없으면 배지가 없다 — 못 읽는 글자를 붙이느니 비운다.
enum AliasBadge {
    static func pick(title: String, aliases: [String], matched: String?) -> String? {
        if let matched = clean(matched), !same(matched, title) {
            return matched
        }
        return aliases.compactMap(clean).first { isRoman($0) && !same($0, title) }
    }

    /// 로마자(라틴 문자)로 적힌 말인가 — 글자가 하나는 있고, 글자는 전부 라틴이다. 숫자·문장부호는 따지지 않는다.
    static func isRoman(_ text: String) -> Bool {
        let letters = text.unicodeScalars.filter { CharacterSet.letters.contains($0) }
        return !letters.isEmpty && letters.allSatisfy { $0.value < 0x250 }
    }

    private static func clean(_ text: String?) -> String? {
        guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }

    private static func same(_ one: String, _ other: String) -> Bool {
        one.caseInsensitiveCompare(other) == .orderedSame
    }
}
