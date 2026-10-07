import Foundation

/// 편의시설을 **화면에 뭐라고 적을지** (MZ2AZ-360).
///
/// 편의시설의 `name`·`address`·`category` 는 언제나 한국어 원본이다 — 네이버 지도 검색·코스 저장·
/// 중복 판정이 그 값에 묶여 있다. 앱 언어의 값은 서버가 새 칸으로 준다(MZ2AZ-358):
/// `displayName`(확실한 영어 이름이 있을 때만 — 체인 브랜드와 `식당`·`카페` 같은 흔한 낱말),
/// `displayAddress`(공식 영문 주소), `categoryLabel`(분류 이름), `nameRoman`(읽는 법).
///
/// 규칙은 계약 그대로다: 제목은 `displayName ?? name`. 영어 이름이 없으면 한국어 이름을 제목에 두고
/// 그 아래 로마자 읽기를 보인다 — 간판은 한글이라 한글을 지우면 가게를 못 찾고, 읽는 법이 없으면 부를 수 없다.
struct PoiLabel: Equatable {
    /// 제목 — 요청 언어의 이름, 없으면 한국어 원본.
    let title: String
    /// 제목 아래 작게 적는 로마자 읽기. 한국어 화면이거나 영어 이름이 이미 있으면 없다.
    let reading: String?
    let category: String?
    let address: String?

    /// 지도 이름표 — 한 줄뿐이다. 한글을 못 읽는 사람에게 한글 한 줄은 빈칸과 같으므로
    /// 영어 이름이 없으면 읽는 법을 적는다.
    var caption: String {
        reading ?? title
    }

    static func make(
        name: String, displayName: String?, nameRoman: String?,
        category: String?, categoryLabel: String?,
        address: String?, displayAddress: String?,
        korean: Bool
    ) -> PoiLabel {
        // 한국어 화면은 언제나 원본이다. 서버도 그렇게 주지만, 영어로 받아 둔 목록이 화면에 남은 채
        // 언어를 바꾸면 영어 칸이 그대로 들어 있다 — 그래서 여기서도 가른다.
        if korean {
            return PoiLabel(title: name, reading: nil, category: category, address: address)
        }
        let shown = filled(displayName)
        var reading: String?
        // 이름이 원래 로마자면(「GS25」) 읽는 법이 같은 글자다 — 두 번 적지 않는다.
        if shown == nil, let roman = filled(nameRoman),
           roman.caseInsensitiveCompare(name.trimmingCharacters(in: .whitespaces)) != .orderedSame
        {
            reading = roman
        }
        return PoiLabel(
            title: shown ?? name,
            reading: reading,
            category: filled(categoryLabel) ?? category,
            address: filled(displayAddress) ?? address
        )
    }

    private static func filled(_ text: String?) -> String? {
        guard let text = text?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        return text
    }
}
