import Foundation

/// 길찾기를 **카카오맵·네이버 지도로 넘길 주소** (MZ2AZ-366 D, 계획 `app-retry.md` §5-3·§12).
///
/// 앱 안 길찾기가 한도(429 `NAVIGATION_LIMIT_REACHED`)에 걸려도 여행은 멈추지 않아야 한다 — 그 구간
/// (지금 자리 → 다음 성지)을 지도 앱이 대신 안내하게 넘긴다. 여기는 주소만 만든다(순수 함수). 앱이 깔려
/// 있는지 보고 여는 것은 화면의 일이다(`RouteEditorTripLimit`).
///
/// ## 쓰는 주소 — 전부 공식 문서에 적힌 꼴이다
///
/// | 대상 | 꼴 |
/// | --- | --- |
/// | 카카오맵 앱 | `kakaomap://route?sp=위도,경도&ep=위도,경도&by=publictransit` |
/// | 카카오맵 웹 | `https://map.kakao.com/link/by/traffic/이름,위도,경도/이름,위도,경도` |
/// | 카카오맵 웹(출발지 모름) | `https://map.kakao.com/link/to/이름,위도,경도` |
/// | 네이버 지도 앱 | `nmap://route/public?slat=&slng=&sname=&dlat=&dlng=&dname=&appname=` |
///
/// 출처: 카카오맵 앱 — apis.map.kakao.com/ios_v2/docs/getting-started/urlscheme/ 「길찾기」. 카카오맵 웹 —
/// apis.map.kakao.com/web/guide/ 「길찾기 바로가기」. 네이버 — guide.ncloud-docs.com/docs/maps-url-scheme.
///
/// 문서에 없는 것은 쓰지 않는다:
///
/// - **네이버 지도의 웹 길찾기 주소는 없다** — 문서는 앱이 없으면 앱스토어로 보내라고만 한다. 여행 중인
///   사람을 앱스토어로 보내지 않는다. 그래서 네이버 단추는 **앱이 깔려 있을 때만** 선다.
/// - 카카오맵 앱 스킴의 출발지(`sp`)를 빼도 되는지는 문서에 없다 — 출발지를 모르면 앱 스킴을 쓰지 않고
///   웹의 `/link/to/` 로 간다(목적지만 받는 꼴이 문서에 있다).
///
/// 좌표는 둘 다 WGS84 위도·경도다. 카카오는 `위도,경도` 한 덩어리, 네이버는 `lat`·`lng` 따로.
enum ExternalDirections {
    /// 지도 위의 한 자리와 그 이름(상대 앱의 출발·도착 칸에 보일 글자).
    struct Spot: Equatable {
        let name: String
        let latitude: Double
        let longitude: Double
    }

    enum App: String, CaseIterable, Equatable {
        case kakao
        case naver

        /// `canOpenURL` 로 물을 스킴 — `Info.plist` 의 `LSApplicationQueriesSchemes` 에 있어야 답이 온다.
        var scheme: String {
            switch self {
            case .kakao: "kakaomap"
            case .naver: "nmap"
            }
        }
    }

    /// 이동 수단. 앱 안 길찾기는 대중교통(걷기 포함) 하나라 지금은 `.transit` 만 넘긴다.
    enum Mode: Equatable {
        case transit
        case walk
        case car

        /// 카카오맵 앱 스킴의 `by`(문서: car · publictransit · foot · bicycle).
        var kakaoApp: String {
            switch self {
            case .transit: "publictransit"
            case .walk: "foot"
            case .car: "car"
            }
        }

        /// 카카오맵 웹 `/link/by/{이동수단}`(문서: car · traffic · walk · bicycle).
        var kakaoWeb: String {
            switch self {
            case .transit: "traffic"
            case .walk: "walk"
            case .car: "car"
            }
        }

        /// 네이버 지도 앱 스킴의 경로(`nmap://route/{…}` — 문서: public · car · walk · bicycle).
        var naverApp: String {
            switch self {
            case .transit: "public"
            case .walk: "walk"
            case .car: "car"
            }
        }
    }

    /// 화면에 세울 단추 하나 — 어디로 넘기고, 무엇을 열고, 안 열리면 무엇을 여나.
    struct Offer: Equatable {
        let app: App
        /// 먼저 열 주소. 앱이 깔려 있으면 앱 스킴, 아니면 웹.
        let url: URL
        /// `url` 이 앱 스킴인데 열리지 않았을 때 열 웹 주소. 웹이 없는 앱(네이버)은 nil.
        let fallback: URL?

        /// 그 앱으로 넘어가나(아니면 브라우저가 연다).
        var opensApp: Bool {
            url.scheme != "https"
        }
    }

    /// 세울 단추들 — 카카오맵은 언제나(앱이 없으면 웹), 네이버 지도는 앱이 깔려 있을 때만.
    ///
    /// - Parameters:
    ///   - origin: 지금 자리. 모르면 nil — 앱 스킴은 출발지가 있어야 해서 웹(목적지만)으로 간다.
    ///   - appName: 네이버가 요구하는 `appname`(번들 id).
    ///   - installed: 깔려 있는 지도 앱(`canOpenURL`).
    static func offers(
        from origin: Spot?, to destination: Spot, mode: Mode = .transit,
        appName: String, installed: Set<App>
    ) -> [Offer] {
        var offers: [Offer] = []
        let kakaoWeb = kakaoWeb(from: origin, to: destination, mode: mode)
        if installed.contains(.kakao), let origin, let app = kakaoApp(from: origin, to: destination, mode: mode) {
            offers.append(Offer(app: .kakao, url: app, fallback: kakaoWeb))
        } else if let kakaoWeb {
            offers.append(Offer(app: .kakao, url: kakaoWeb, fallback: nil))
        }
        let naver = origin.flatMap { naverApp(from: $0, to: destination, mode: mode, appName: appName) }
        if installed.contains(.naver), let naver {
            offers.append(Offer(app: .naver, url: naver, fallback: nil))
        }
        return offers
    }

    /// `kakaomap://route?sp=37.566300,126.977900&ep=37.579600,126.977000&by=publictransit`
    static func kakaoApp(from origin: Spot, to destination: Spot, mode: Mode) -> URL? {
        guard isValid(origin), isValid(destination) else { return nil }
        return URL(string: "kakaomap://route?sp=\(pair(origin))&ep=\(pair(destination))&by=\(mode.kakaoApp)")
    }

    /// `https://map.kakao.com/link/by/traffic/현재%20위치,37.566300,126.977900/경복궁,37.579600,126.977000`.
    /// 출발지를 모르면 `https://map.kakao.com/link/to/경복궁,37.579600,126.977000`(이동 수단은 카카오맵이 고른다).
    static func kakaoWeb(from origin: Spot?, to destination: Spot, mode: Mode) -> URL? {
        guard isValid(destination) else { return nil }
        let end = "\(segment(destination.name)),\(pair(destination))"
        guard let origin, isValid(origin) else {
            return URL(string: "https://map.kakao.com/link/to/\(end)")
        }
        let start = "\(segment(origin.name)),\(pair(origin))"
        return URL(string: "https://map.kakao.com/link/by/\(mode.kakaoWeb)/\(start)/\(end)")
    }

    /// `nmap://route/public?slat=37.566300&slng=126.977900&sname=…&dlat=…&dlng=…&dname=…&appname=com.mz2az.scenetrip`
    static func naverApp(from origin: Spot, to destination: Spot, mode: Mode, appName: String) -> URL? {
        guard isValid(origin), isValid(destination), !appName.isEmpty else { return nil }
        let query = [
            "slat=\(number(origin.latitude))", "slng=\(number(origin.longitude))",
            "sname=\(encode(label(origin.name)))",
            "dlat=\(number(destination.latitude))", "dlng=\(number(destination.longitude))",
            "dname=\(encode(label(destination.name)))",
            "appname=\(encode(appName))",
        ].joined(separator: "&")
        return URL(string: "nmap://route/\(mode.naverApp)?\(query)")
    }

    // MARK: 글자 만들기

    /// 위도·경도가 수이고 범위 안인가. 깨진 좌표로 남의 앱을 열지 않는다.
    private static func isValid(_ spot: Spot) -> Bool {
        spot.latitude.isFinite && spot.longitude.isFinite
            && (-90 ... 90).contains(spot.latitude) && (-180 ... 180).contains(spot.longitude)
    }

    /// 소수 여섯 자리(약 0.1 m). 기기 언어와 무관하게 점(`.`)을 쓴다.
    private static func number(_ value: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    /// 카카오의 `위도,경도`.
    private static func pair(_ spot: Spot) -> String {
        "\(number(spot.latitude)),\(number(spot.longitude))"
    }

    /// 이름을 한 줄 글자로 — 앞뒤 공백을 떼고, 비면 `-`(이름 칸은 비울 수 없다).
    private static func label(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "-" : trimmed
    }

    /// 카카오 웹 주소의 이름 칸. **쉼표와 빗금은 칸을 가르는 글자**라 이름에 있으면 빈칸으로 바꾼다 —
    /// 「남산, 서울타워」 가 그대로 가면 `서울타워` 를 위도로 읽는다.
    private static func segment(_ name: String) -> String {
        let flat = name.map { ",/".contains($0) ? " " : String($0) }.joined()
        return encode(label(flat.split(separator: " ").joined(separator: " ")))
    }

    /// 퍼센트 인코딩 — 영문·숫자·`-._~` 만 그대로 둔다(RFC 3986 의 예약되지 않은 글자). 한글·공백·`&`·`=`·`+`
    /// 는 전부 `%XX` 가 된다.
    private static func encode(_ text: String) -> String {
        text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }

    private static let unreserved = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
    )
}
