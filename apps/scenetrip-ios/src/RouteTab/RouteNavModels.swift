import Foundation

// 여행 중 길찾기와 주변 편의시설의 자료 모양 (MZ2AZ-225 · MZ2AZ-233).
//
// ## 왜 한 구간씩인가
//
// 코스에 담은 다섯 곳의 경로를 **미리 다 받아 두지 않는다.** 주문진에 도착한
// 사람은 한참 둘러보다 마음이 들 때 다음 곳으로 움직이고, 그 사이에 밥을 먹거나
// 계획에 없던 곳에 들른다. 미리 받아 둔 다섯 구간은 그때 대부분 버려진다 —
// 돈은 돈대로 나가고 안내는 틀린다.
//
// > *"저희가 무조건 성지에 도착해서 뭔가 딴 짓을 할 거라고 봤단 말이에요"*
// > (8/11 회의 2부 24:36)
//
// 그래서 **행마다 「길찾기」 버튼이 따로 있고, 누를 때만 부른다.** 이것이 비용
// 통제 장치이기도 하다 — 호출 횟수가 사용자의 의도와 1:1로 붙는다.
//
// ## 값은 서버가 준다
//
// 계약의 `POST /navigation/next-leg` 가 섰다(MZ2AZ-296, 엔진은 카카오). 이 타입들은
// 계약 응답(`NextLeg`·`RouteLeg`)을 화면 말로 옮긴 것이고, 채우는 코드는
// `RouteNavControls.swift` 의 `init(contract:)` 다. 하루 호출 상한(MZ2AZ-205)은 아직
// 없다 — 지금 통제는 가입·활성 코스·한 구간씩, 셋뿐이다.

/// 한 구간의 이동 수단.
enum RouteLegMode {
    case walk
    /// 버스 — 계약 `vehicleType` 이 마을·간선·지선·광역·직행·버스. 지하철과 갈라 그린다
    /// (2026-09-04 사용자 지적: "지하철인지 버스인지 구분은 못 해?").
    case bus
    /// 지하철·전철.
    case subway
    /// 그 밖의 탈것(기차·고속버스·해운) — 종류를 모르는 대중교통도 여기.
    case transit

    var symbol: String {
        switch self {
        case .walk: "figure.walk"
        case .bus: "bus.fill"
        case .subway: "tram.fill"
        case .transit: "train.side.front.car"
        }
    }

    var isVehicle: Bool {
        self != .walk
    }

    /// 계약의 `RouteLeg.mode`(walk/transit)와 `vehicleType`(제공자 원문, 한국어)에서 갈래를 고른다.
    static func from(contractMode: String, vehicleType: String?) -> RouteLegMode {
        guard contractMode != "walk" else { return .walk }
        let kind = vehicleType ?? ""
        if ["지하철", "전철", "경전철", "SUBWAY"].contains(where: kind.contains) {
            return .subway
        }
        if ["버스", "마을", "간선", "지선", "광역", "직행", "순환", "BUS"].contains(where: kind.contains) {
            return .bus
        }
        return .transit
    }
}

/// 「현재 위치 → 다음 목적지」 한 번의 안내를 이루는 조각 하나.
struct RouteLeg: Identifiable {
    let id = UUID()
    let mode: RouteLegMode
    let title: String
    let detail: String

    /// 이 구간이 지나는 **실제 길 좌표**. `(경도, 위도)` 순서로 온다.
    ///
    /// 지도에 이것을 그린다. 앞서 출발지·목적지만 직선으로 이었는데, 그러면
    /// 「어느 길로 가는가」가 통째로 빠진다 — 길찾기 화면이 할 일의 절반이다.
    var path: [[Double]] = []

    /// 계단이 있는 구간인가.
    ///
    /// **카카오는 이것을 구조화해서 주지 않는다** — 안내 문구에 「계단」이 섞여 나올
    /// 뿐이라 문자열에서 긁어야 한다. T맵 `facilityType 17` 만이 제대로 준다.
    /// 캐리어를 끄는 외국인에게 계단은 경로를 바꿀 정보라, 이것 하나 때문에 도보
    /// 보조로 T맵을 놓지 못한다.
    var hasStairs = false
}

/// 한 번의 길찾기 결과.
struct RouteNavResult {
    let destination: String
    let totalMinutes: Int
    let transfers: Int

    /// 걷는 거리. **모르면 `nil` 이고 0 이 아니다.**
    ///
    /// 카카오 후보의 47% 가 도보 구간 없이 온다(프로토타입 실측 — 버스 전용 경로가
    /// 특히 그렇다). 그것을 0 m 로 두면 「안 걸어도 되는 경로」가 되어 가장 좋아
    /// 보인다. 프로토타입이 같은 실수를 네 번 반복하고 얻은 규칙이다 — 도보·요금·
    /// 계단·합계 전부 「모르는 값을 0 으로 두니 그 후보가 1위가 됐다」였다.
    let walkMeters: Int?

    let fareWon: Int?
    let legs: [RouteLeg]

    /// 구간 좌표를 순서대로 이은 것. 지도가 이것을 그린다.
    var path: [[Double]] {
        legs.flatMap(\.path)
    }

    var summaryLine: String {
        var parts = ["환승 \(transfers)회"]
        // 모르는 것은 모른다고 적는다. 빼 버리면 「도보 0」과 구별되지 않는다.
        parts.append(walkMeters.map { "도보 \($0) m" } ?? "도보 정보 없음")
        if let fareWon, fareWon > 0 {
            parts.append("\(fareWon.formatted(.number.grouping(.automatic)))원")
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - 주변 편의시설

/// 편의시설의 갈래. **DB 의 `poi.category_group` 과 같은 값이어야 한다**
/// (`V12__poi.sql`, MZ2AZ-283). 서버가 이 문자열을 그대로 내려준다.
enum RoutePoiGroup: String, CaseIterable, Identifiable {
    case food
    case sight
    case stay
    case transit

    var id: String {
        rawValue
    }

    var label: String {
        switch self {
        case .food: "음식점"
        case .sight: "명소"
        case .stay: "숙소"
        case .transit: "교통"
        }
    }
}

/// 반경 안에서 찾은 편의시설 하나.
///
/// 촬영지(`PlaceSummary`)와 **다른 표에서 온다** — 편의시설 47만 건, 촬영지 155 건이라
/// 섞으면 촬영지가 묻힌다(`V12__poi.sql` 주석).
struct RoutePoi: Identifiable {
    let id: Int64
    let name: String
    let group: RoutePoiGroup
    let address: String?

    /// 현재 위치에서의 거리(m).
    let distanceMeters: Int

    /// 사용자가 지도를 눌러 직접 찍은 것인가.
    ///
    /// 한국에서는 에어비앤비 숙소가 POI 로 등록돼 있지 않은 경우가 많다. 그래서
    /// 「핀 찍기」를 열어 두었고, 그렇게 만든 것은 우리 DB 에 없으므로 표시를 남긴다.
    var isPinned = false

    var metaLine: String {
        var parts = [group.label]
        if let address, !address.isEmpty {
            parts.append(address)
        }
        parts.append("\(distanceMeters) m")
        return parts.joined(separator: " · ")
    }
}
