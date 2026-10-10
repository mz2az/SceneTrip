import Foundation

/// 서버가 준 이동 포함 합계. 편집 중에는 같은 경로의 체류 차이만 반영한다(MZ2AZ-390).
struct RouteDayEstimate: Hashable {
    private struct Target: Hashable {
        let kind: RouteStop.Kind
        let placeId: Int64?
        let latitude: Double
        let longitude: Double
        let missing: Bool

        init(_ stop: RouteStop) {
            kind = stop.kind
            placeId = stop.placeId
            latitude = stop.place.latitude
            longitude = stop.place.longitude
            missing = stop.placeMissing
        }
    }

    private let targets: [Target]
    private let dwellMinutes: Int
    private let totalMinutes: Int

    init(stops: [RouteStop], totalMinutes: Int) {
        targets = stops.map(Target.init)
        dwellMinutes = RouteStop.stayTotal(stops)
        self.totalMinutes = totalMinutes
    }

    func total(for stops: [RouteStop]) -> Int? {
        guard totalMinutes >= dwellMinutes, targets == stops.map(Target.init) else { return nil }
        return totalMinutes + RouteStop.stayTotal(stops) - dwellMinutes
    }

    static func label(total: Int?, stops: [RouteStop]) -> String {
        if let total {
            return String(format: tr("약 %@(이동 포함)"), RouteFormat.minutes(total))
        }
        return String(format: tr("머무는 시간 %@"), RouteFormat.minutes(RouteStop.stayTotal(stops)))
    }
}
