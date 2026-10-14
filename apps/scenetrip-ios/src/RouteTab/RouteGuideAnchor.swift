import CoreLocation

/// 코스 항목 유무와 무관한 챗봇 검색 기준(MZ2AZ-444).
struct RouteGuideAnchor {
    let spot: TripSpot
    let isMapCenter: Bool

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: spot.latitude, longitude: spot.longitude)
    }

    static func resolve(current: TripSpot?, mapCenter: TripSpot?, preferMap: Bool) -> Self? {
        let current = current.flatMap(valid)
        let center = mapCenter.flatMap(valid)
        if preferMap, let center {
            return Self(spot: center, isMapCenter: true)
        }
        if let current {
            return Self(spot: current, isMapCenter: false)
        }
        return center.map { Self(spot: $0, isMapCenter: true) }
    }

    private static func valid(_ spot: TripSpot) -> TripSpot? {
        let coordinate = CLLocationCoordinate2D(latitude: spot.latitude, longitude: spot.longitude)
        return CLLocationCoordinate2DIsValid(coordinate) ? spot : nil
    }
}
