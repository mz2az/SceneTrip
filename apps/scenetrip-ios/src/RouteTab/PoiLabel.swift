import Foundation

/// 편의시설을 **화면에 뭐라고 적을지** (MZ2AZ-360).
///
/// 편의시설의 `name`·`address`·`category` 는 언제나 한국어 원본이다 — 네이버 지도 검색·코스 저장·
/// 중복 판정이 그 값에 묶여 있다. 앱 언어의 값은 서버가 새 칸으로 준다(MZ2AZ-358):
/// `displayName`(확실한 영어 이름이 있을 때만 — 체인 브랜드와 `식당`·`카페` 같은 흔한 낱말),
/// `displayAddress`(공식 영문 주소), `categoryLabel`(분류 이름), `nameRoman`(읽는 법).
///
/// 규칙은 티켓(MZ2AZ-360)과 계약 그대로다. 제목은 `displayName ?? name`. 제목 아래 한 줄은
/// **영어 이름이 있으면 한국어 이름**(간판과 맞춰 본다), **없으면 로마자 읽기**다 — 간판은 한글이라
/// 한글이 화면에서 사라지면 가게를 못 찾고, 읽는 법이 없으면 부를 수 없다.
///
/// 예) 영어 이름 있음: **Starbucks Gurigalmaeyeok Branch** / 스타벅스 구리갈매역점
///     없음: **도시어부** / Dosieobu
struct PoiLabel: Equatable {
    /// 제목 — 요청 언어의 이름, 없으면 한국어 원본.
    let title: String
    /// 제목 아래 작게 적는 한 줄 — 영어 이름이 제목이면 한국어 이름, 한국어 이름이 제목이면 로마자 읽기.
    /// 한국어 화면에는 없다.
    let reading: String?
    /// 지도 이름표에 적을 로마자 — 영어 이름이 없을 때만 있다.
    let roman: String?
    let category: String?
    let address: String?

    /// 지도 이름표 — 한 줄뿐이다. 한글을 못 읽는 사람에게 한글 한 줄은 빈칸과 같으므로
    /// 영어 이름이 없으면 읽는 법을 적는다.
    var caption: String {
        roman ?? title
    }

    static func make(
        name: String, displayName: String?, nameRoman: String?,
        category: String?, categoryLabel: String?,
        address: String?, displayAddress: String?,
        korean isKorean: Bool
    ) -> PoiLabel {
        // 한국어 화면은 언제나 원본이다. 서버도 그렇게 주지만, 영어로 받아 둔 목록이 화면에 남은 채
        // 언어를 바꾸면 영어 칸이 그대로 들어 있다 — 그래서 여기서도 가른다.
        if isKorean {
            return PoiLabel(title: name, reading: nil, roman: nil, category: category, address: address)
        }
        let shown = filled(displayName)
        let original = name.trimmingCharacters(in: .whitespaces)
        var roman: String?
        // 이름이 원래 로마자면(「GS25」) 읽는 법이 같은 글자다 — 두 번 적지 않는다.
        if shown == nil, let reading = filled(nameRoman),
           reading.caseInsensitiveCompare(original) != .orderedSame
        {
            roman = reading
        }
        // 영어 이름이 제목이면 그 아래는 한국어 이름이다(같은 글자면 생략).
        var korean: String?
        if let shown, shown.caseInsensitiveCompare(original) != .orderedSame {
            korean = name
        }
        return PoiLabel(
            title: shown ?? name,
            reading: korean ?? roman,
            roman: roman,
            category: filled(categoryLabel) ?? category,
            address: filled(displayAddress) ?? address
        )
    }

    private static func filled(_ text: String?) -> String? {
        guard let text = text?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        return text
    }
}
