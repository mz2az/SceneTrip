import Foundation

/// 촬영지·편의시설의 「네이버 지도에서 보기」가 갈 곳 (MZ2AZ-374·383).
///
/// 서버가 상세에 실어 주는 `naverPlaceUrl` — **그 장소의 네이버 장소 화면**
/// (`https://map.naver.com/p/entry/place/{번호}`, 계약 scene-api 1.6.0 · ADR 0021) — 만 연다.
/// 값이 없으면 링크도 없고 버튼도 없다. 이름으로 검색한 화면(`/p/search/…`, MZ2AZ-354)으로는
/// 더 넘기지 않는다 — 같은 이름의 다른 가게로 보내느니 없는 편이 낫다(정권호 결정 2026-10-09).
///
/// 사진·평점·리뷰를 가져오지 않는 것은 그대로다. 네이버 지도 앱이 있으면 앱이, 없으면 브라우저가 연다.
enum NaverMapLink {
    /// 서버가 준 주소를 열어도 되는 것으로 추린다. 아니면 nil — 버튼이 서지 않는다.
    ///
    /// 주소는 서버가 만든 것이지만 앱 밖(브라우저·다른 앱)으로 넘기는 값이라 한 번 더 본다:
    /// `https` 이고 포트가 없고 호스트가 네이버(`naver.com`·그 아래·`naver.me`)일 때만 연다. 다른 스킴
    /// (`http`·`tel`·앱 스킴)이나 남의 호스트면 「네이버 지도에서 보기」라는 글자와 갈 곳이 어긋난다.
    static func place(_ raw: String?) -> URL? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty,
              let parts = URLComponents(string: text),
              parts.scheme?.lowercased() == "https",
              parts.user == nil, parts.password == nil,
              // 서버는 포트 없는 주소만 만든다.
              parts.port == nil,
              // 퍼센트 인코딩을 **풀기 전** 글자를 본다 — `evil.io%2F.naver.com` 은 풀면 `.naver.com` 으로 끝난다.
              let host = parts.percentEncodedHost?.lowercased(), isNaver(host)
        else { return nil }
        return parts.url
    }

    /// 호스트 이름에 올 수 있는 글자(영문·숫자·`.`·`-`)뿐이고 네이버의 것일 때만. `%`·끝 점은 여기서 걸린다.
    private static func isNaver(_ host: String) -> Bool {
        let plain = host.unicodeScalars.allSatisfy { hostCharacters.contains($0) }
        return plain && (host == "naver.com" || host.hasSuffix(".naver.com") || host == "naver.me")
    }

    private static let hostCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")
}
