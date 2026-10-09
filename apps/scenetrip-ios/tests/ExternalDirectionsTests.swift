@testable import SceneTrip
import XCTest

/// 길찾기 한도에 걸린 구간을 **지도 앱으로 넘길 주소** (MZ2AZ-366 D). 꼴은 두 회사의 URL Scheme 문서에 적힌
/// 그대로여야 한다 — 인자 이름·순서(위도 먼저)·이동 수단 글자가 틀리면 상대 앱이 조용히 지도만 띄운다.
final class ExternalDirectionsTests: XCTestCase {
    private typealias Spot = ExternalDirections.Spot
    private let here = Spot(name: "현재 위치", latitude: 37.5663, longitude: 126.9779)
    private let palace = Spot(name: "경복궁", latitude: 37.579617, longitude: 126.977041)
    private let bundle = "com.mz2az.scenetrip"

    // MARK: 카카오맵 앱

    /// 문서: `kakaomap://route?sp=37.39529,127.11044&ep=37.49795,127.02763&by=car` — 위도,경도 순.
    func testKakaoAppRoutePerMode() {
        XCTAssertEqual(
            ExternalDirections.kakaoApp(from: here, to: palace, mode: .transit)?.absoluteString,
            "kakaomap://route?sp=37.566300,126.977900&ep=37.579617,126.977041&by=publictransit"
        )
        XCTAssertEqual(
            ExternalDirections.kakaoApp(from: here, to: palace, mode: .walk)?.absoluteString,
            "kakaomap://route?sp=37.566300,126.977900&ep=37.579617,126.977041&by=foot"
        )
        XCTAssertEqual(
            ExternalDirections.kakaoApp(from: here, to: palace, mode: .car)?.absoluteString,
            "kakaomap://route?sp=37.566300,126.977900&ep=37.579617,126.977041&by=car"
        )
    }

    // MARK: 카카오맵 웹

    /// 문서: `/link/by/{이동수단}/이름,위도,경도/이름,위도,경도` — 대중교통은 `traffic`.
    func testKakaoWebRouteNamesAreEncoded() throws {
        XCTAssertEqual(
            ExternalDirections.kakaoWeb(from: here, to: palace, mode: .transit)?.absoluteString,
            "https://map.kakao.com/link/by/traffic/"
                + "%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98,37.566300,126.977900/"
                + "%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041"
        )
        XCTAssertTrue(
            try XCTUnwrap(ExternalDirections.kakaoWeb(from: here, to: palace, mode: .walk)?.absoluteString
                .hasPrefix("https://map.kakao.com/link/by/walk/"))
        )
        XCTAssertTrue(
            try XCTUnwrap(ExternalDirections.kakaoWeb(from: here, to: palace, mode: .car)?.absoluteString
                .hasPrefix("https://map.kakao.com/link/by/car/"))
        )
    }

    /// 출발지를 모르면 목적지만 받는 꼴 — 문서: `/link/to/이름,위도,경도`.
    func testKakaoWebWithoutOriginUsesTheToForm() {
        XCTAssertEqual(
            ExternalDirections.kakaoWeb(from: nil, to: palace, mode: .transit)?.absoluteString,
            "https://map.kakao.com/link/to/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041"
        )
    }

    /// 쉼표와 빗금은 칸을 가르는 글자다 — 이름에 있으면 좌표 칸이 밀린다.
    func testKakaoWebNameCannotBreakTheFields() throws {
        let tower = Spot(name: " 남산, 서울타워/전망대 & Cafe? ", latitude: 37.5512, longitude: 126.9882)
        let text = try XCTUnwrap(ExternalDirections.kakaoWeb(from: nil, to: tower, mode: .transit)?.absoluteString)
        XCTAssertEqual(
            text,
            "https://map.kakao.com/link/to/"
                + "%EB%82%A8%EC%82%B0%20%EC%84%9C%EC%9A%B8%ED%83%80%EC%9B%8C%20"
                + "%EC%A0%84%EB%A7%9D%EB%8C%80%20%26%20Cafe%3F,37.551200,126.988200"
        )
        XCTAssertEqual(text.components(separatedBy: ",").count, 3, "쉼표는 이름·위도·경도를 가르는 둘뿐이다")
        // 이름이 비어도 칸은 비우지 않는다.
        let blank = Spot(name: "  ", latitude: 37.5, longitude: 127)
        XCTAssertEqual(
            ExternalDirections.kakaoWeb(from: nil, to: blank, mode: .transit)?.absoluteString,
            "https://map.kakao.com/link/to/-,37.500000,127.000000"
        )
    }

    // MARK: 네이버 지도 앱

    /// 문서: `nmap://route/public?slat=…&slng=…&sname=…&dlat=…&dlng=…&dname=…&appname=…` —
    /// 이름은 URL 인코딩, `appname` 은 반드시.
    func testNaverAppRoutePerMode() {
        let tail = "?slat=37.566300&slng=126.977900&sname=%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98"
            + "&dlat=37.579617&dlng=126.977041&dname=%EA%B2%BD%EB%B3%B5%EA%B6%81&appname=com.mz2az.scenetrip"
        XCTAssertEqual(
            ExternalDirections.naverApp(from: here, to: palace, mode: .transit, appName: bundle)?.absoluteString,
            "nmap://route/public" + tail
        )
        XCTAssertEqual(
            ExternalDirections.naverApp(from: here, to: palace, mode: .walk, appName: bundle)?.absoluteString,
            "nmap://route/walk" + tail
        )
        XCTAssertEqual(
            ExternalDirections.naverApp(from: here, to: palace, mode: .car, appName: bundle)?.absoluteString,
            "nmap://route/car" + tail
        )
    }

    /// 이름 속 `&`·`=`·`+`·`#` 가 인자를 깨뜨리지 않는다.
    func testNaverNameCannotInjectParameters() throws {
        let odd = Spot(name: "A&B=C+D #1", latitude: 37.5, longitude: 127)
        let link = ExternalDirections.naverApp(from: here, to: odd, mode: .transit, appName: bundle)
        let text = try XCTUnwrap(link?.absoluteString)
        XCTAssertTrue(text.contains("&dname=A%26B%3DC%2BD%20%231&appname="), text)
        XCTAssertNil(
            ExternalDirections.naverApp(from: here, to: odd, mode: .transit, appName: ""), "appname 없이는 부르지 않는다"
        )
    }

    // MARK: 깨진 좌표

    func testBrokenCoordinatesMakeNoLink() {
        let nowhere = Spot(name: "x", latitude: .nan, longitude: 127)
        let outside = Spot(name: "x", latitude: 91, longitude: 127)
        XCTAssertNil(ExternalDirections.kakaoApp(from: here, to: nowhere, mode: .transit))
        XCTAssertNil(ExternalDirections.kakaoWeb(from: here, to: outside, mode: .transit))
        XCTAssertNil(ExternalDirections.naverApp(from: nowhere, to: palace, mode: .transit, appName: bundle))
        // 출발지만 깨졌으면 목적지만 받는 꼴로 물러선다.
        XCTAssertEqual(
            ExternalDirections.kakaoWeb(from: nowhere, to: palace, mode: .transit)?.absoluteString,
            "https://map.kakao.com/link/to/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041"
        )
        XCTAssertTrue(
            ExternalDirections.offers(from: here, to: nowhere, appName: bundle, installed: [.kakao, .naver]).isEmpty
        )
    }

    // MARK: 어느 단추가 서나

    /// 지도 앱이 하나도 없다(시뮬레이터·외국인 여행자) — 카카오맵 웹 하나. 네이버는 웹 길찾기 주소가 문서에 없다.
    func testNoAppsInstalledOffersKakaoWebOnly() {
        let offers = ExternalDirections.offers(from: here, to: palace, appName: bundle, installed: [])
        XCTAssertEqual(offers.map(\.app), [.kakao])
        XCTAssertEqual(offers.first?.url.scheme, "https")
        XCTAssertEqual(offers.first?.opensApp, false)
        XCTAssertNil(offers.first?.fallback)
    }

    /// 둘 다 깔려 있다 — 둘 다 앱으로. 카카오는 안 열리면 웹으로 물러설 주소를 든다.
    func testBothInstalledOffersBothApps() {
        let offers = ExternalDirections.offers(from: here, to: palace, appName: bundle, installed: [.kakao, .naver])
        XCTAssertEqual(offers.map(\.app), [.kakao, .naver])
        XCTAssertEqual(offers.map(\.url.scheme), ["kakaomap", "nmap"])
        XCTAssertEqual(offers.map(\.opensApp), [true, true])
        XCTAssertEqual(offers[0].fallback, ExternalDirections.kakaoWeb(from: here, to: palace, mode: .transit))
        XCTAssertNil(offers[1].fallback)
        XCTAssertTrue(offers[0].url.absoluteString.hasSuffix("&by=publictransit"), "앱 안 길찾기와 같은 대중교통")
        XCTAssertTrue(offers[1].url.absoluteString.hasPrefix("nmap://route/public?"))
    }

    func testOnlyNaverInstalledStillOffersKakaoWeb() {
        let offers = ExternalDirections.offers(from: here, to: palace, appName: bundle, installed: [.naver])
        XCTAssertEqual(offers.map(\.app), [.kakao, .naver])
        XCTAssertEqual(offers.map(\.url.scheme), ["https", "nmap"])
    }

    /// 지금 자리를 모른다 — 앱 스킴은 출발지가 있어야 한다. 카카오는 웹의 목적지 꼴, 네이버는 없다.
    func testUnknownOriginFallsBackToDestinationOnly() {
        let offers = ExternalDirections.offers(from: nil, to: palace, appName: bundle, installed: [.kakao, .naver])
        XCTAssertEqual(offers.map(\.app), [.kakao])
        XCTAssertTrue(offers[0].url.absoluteString.hasPrefix("https://map.kakao.com/link/to/"))
    }

    /// `canOpenURL` 로 묻는 스킴 — `Info.plist` 의 `LSApplicationQueriesSchemes` 와 같아야 한다.
    func testQuerySchemes() {
        XCTAssertEqual(ExternalDirections.App.kakao.scheme, "kakaomap")
        XCTAssertEqual(ExternalDirections.App.naver.scheme, "nmap")
    }
}
