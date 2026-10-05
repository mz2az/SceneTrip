import Foundation

/// 네이버 지도로 **넘기는** 링크 (MZ2AZ-354).
///
/// 편의시설의 사진·평점·리뷰를 우리가 가져와 보여 주지 않는다 — 그 정보는 네이버와 가게,
/// 방문자의 것이다. 이름으로 검색한 화면을 열어 줄 뿐이다. 네이버 지도 앱이 있으면 앱이,
/// 없으면 브라우저가 연다.
enum NaverMapLink {
    /// 이름으로 검색한 네이버 지도 주소. 이름이 비면 없다.
    ///
    /// `near` 는 시군구(「종로구」) — **같은 이름의 다른 지점**이 뜨지 않게 검색어 뒤에 붙인다.
    /// 이름만 넘기면 네이버가 제 기준 지역에서 찾는다(종로 가게를 열었는데 중구 중심으로 검색됐다).
    static func search(_ name: String, near: String? = nil) -> String? {
        let place = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let area = near?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // 이름에 이미 그 지역이 들어 있으면 두 번 적지 않는다(「명동교자 종로구점 종로구」).
        let term = area.isEmpty || place.contains(area) ? place : "\(place) \(area)"
        // 경로 한 칸에 들어간다 — `/`·`?`·`#` 가 그대로 가면 주소가 끊긴다.
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#%&+")
        guard !place.isEmpty, let encoded = term.addingPercentEncoding(withAllowedCharacters: allowed) else {
            return nil
        }
        return "https://map.naver.com/p/search/\(encoded)"
    }
}
