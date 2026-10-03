import SceneApiClient
@testable import SceneTrip
import XCTest

/// 안내 띠의 구간 칩 — 카카오 실측 응답(2026-09-28, 서울역 → DDP)으로 못 박는다.
///
/// 카카오는 1호선을 `vehicleType: "일반"`, `vehicleName: "1호선"` 으로 보낸다. 종류 칸만
/// 보던 분류는 이것을 「그 밖의 탈것」으로 떨어뜨려 초록 칩에 기차 아이콘을 그렸고,
/// 내린 뒤 걷는 길은 7~145 m 조각 10개가 칩 10개로 늘어섰다.
final class RouteLegChipTests: XCTestCase {
    private func leg(
        _ mode: SceneApiClient.RouteLeg.Mode,
        guidance: String = "",
        meters: Int? = nil,
        seconds: Int? = nil,
        stops: Int? = nil,
        type: String? = nil,
        name: String? = nil,
        path: [[Double]] = [],
        stairs: Bool = false
    ) -> SceneApiClient.RouteLeg {
        SceneApiClient.RouteLeg(
            mode: mode, guidance: guidance, meters: meters, seconds: seconds,
            stopCount: stops, vehicleType: type, vehicleName: name,
            path: LineString(type: .lineString, coordinates: path), hasStairs: stairs
        )
    }

    // MARK: 갈래

    func testLineNumberNameIsSubway() {
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: "일반", vehicleName: "1호선"), .subway)
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: "일반", vehicleName: "경의중앙선"), .subway)
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: nil, vehicleName: "공항철도"), .subway)
    }

    func testBusTypeWinsOverName() {
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: "간선", vehicleName: "150"), .bus)
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: "마을", vehicleName: "종로02"), .bus)
    }

    func testUnknownVehicleStaysTransit() {
        XCTAssertEqual(RouteLegMode.from(contractMode: "transit", vehicleType: "일반", vehicleName: "100"), .transit)
        XCTAssertEqual(RouteLegMode.from(contractMode: "walk", vehicleType: "일반", vehicleName: "1호선"), .walk)
    }

    // MARK: 문구

    /// 「일반 1호선 · 1호선 (서울역 > 동대문)」처럼 노선이 두 번 나오지 않는다.
    func testSubwayTitleIsLineOnlyAndGuidanceDropsRepeat() {
        let chip = RouteLeg(contract: leg(
            .transit, guidance: "1호선 (서울역 > 동대문)", meters: 4600, seconds: 660,
            stops: 5, type: "일반", name: "1호선"
        ))
        XCTAssertEqual(chip.mode, .subway)
        XCTAssertEqual(chip.title, "1호선")
        XCTAssertEqual(chip.detail, "서울역 > 동대문 · 11분 · 4600 m · 5 정거장")
    }

    /// 버스는 「외 1대」(같이 탈 수 있는 다른 버스)를 버리지 않는다.
    func testBusKeepsAlternativeCount() {
        let chip = RouteLeg(contract: leg(
            .transit, guidance: "마을 종로02외 1대 (북촌한옥마을입구 > 가회동주민센터)",
            seconds: 300, stops: 3, type: "마을", name: "종로02"
        ))
        XCTAssertEqual(chip.title, "마을 종로02")
        XCTAssertEqual(chip.detail, "외 1대 (북촌한옥마을입구 > 가회동주민센터) · 5분 · 3 정거장")
    }

    // MARK: 도보 합치기

    func testAdjacentWalksMergeIntoOneChip() {
        let result = RouteNavResult(
            destination: "DDP", totalMinutes: 28, transfers: 0, walkMeters: 868, fareWon: 1550,
            legs: [
                RouteLeg(contract: leg(.walk, meters: 239, seconds: 180, path: [[0, 0], [1, 1]])),
                RouteLeg(contract: leg(.transit, guidance: "1호선 (서울역 > 동대문)", type: "일반", name: "1호선")),
                RouteLeg(contract: leg(.walk, meters: 58, seconds: 60, path: [[2, 2]])),
                RouteLeg(contract: leg(.walk, meters: 106, seconds: 90, path: [[3, 3]], stairs: true)),
                RouteLeg(contract: leg(.walk, meters: 64, seconds: 50, path: [[4, 4]])),
            ]
        )
        let chips = result.chips
        XCTAssertEqual(chips.map(\.mode), [.walk, .subway, .walk])
        XCTAssertEqual(chips[2].detail, "3분 · 228 m") // 200초 — 낱장 칩과 같은 내림
        XCTAssertTrue(chips[2].hasStairs)
        XCTAssertEqual(chips[2].path, [[2, 2], [3, 3], [4, 4]])
        // 칩만 합친다 — 지도 선·데모 주행이 쓰는 원래 구간은 그대로다.
        XCTAssertEqual(result.legs.count, 5)
    }

    /// 한 조각이라도 거리를 모르면 합계를 지어내지 않는다 — 0 으로 두면 「짧은 길」이 된다.
    func testMergedWalkDropsUnknownTotals() {
        let result = RouteNavResult(
            destination: "x", totalMinutes: 5, transfers: 0, walkMeters: nil, fareWon: nil,
            legs: [
                RouteLeg(contract: leg(.walk, guidance: "직진", meters: 100, seconds: 60)),
                RouteLeg(contract: leg(.walk, guidance: "우회전", meters: nil, seconds: 60)),
            ]
        )
        XCTAssertEqual(result.chips.count, 1)
        XCTAssertEqual(result.chips[0].detail, "2분")
    }
}
