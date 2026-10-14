import Foundation

/// POI별 실제 좌표를 보존한다. 같은 위치의 서로 다른 장소를 합치거나 옮기지 않는다.
enum PoiMarkerRules {
    static func validPlaces(_ places: [RouteGuide.Place]) -> [RouteGuide.Place] {
        var seen: Set<String> = []
        return places.filter { place in
            place.latitude.isFinite && place.longitude.isFinite
                && abs(place.latitude) <= 85.05112878 && abs(place.longitude) <= 180
                && seen.insert(place.id).inserted
        }
    }
}
