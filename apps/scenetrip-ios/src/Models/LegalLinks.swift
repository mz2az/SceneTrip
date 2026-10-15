import Foundation

/// 개인정보 처리방침·이용약관 주소 (MZ2AZ-455, App Store 5.1.1).
///
/// **주소는 여기 한 곳에만 둔다** — 게시 주소가 바뀌면 이 두 줄만 고친다. 마이페이지와 로그인 화면이
/// 둘 다 이 값을 읽는다.
///
/// 2026-10-15 확인: `privacy` 는 초안(시행일 비어 있음)이 게시돼 200, 영어판 주소는 없다(한국어 한 장).
/// `terms` 는 아직 게시 전이라 404 다(MZ2AZ-437·418) — 출시 전에 게시해야 한다. 링크는 숨기지 않는다.
enum LegalLinks {
    static let privacy = URL(string: "https://scenetrip.io/privacy")!
    static let terms = URL(string: "https://scenetrip.io/terms")!
}
