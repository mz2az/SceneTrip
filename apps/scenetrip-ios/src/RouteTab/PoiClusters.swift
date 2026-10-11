import Foundation

/// 현재 받은 POI를 지도 축척의 64pt 반경으로 묶는다(MZ2AZ-403). 원본 ID·구성원은 버리지 않는다.
enum PoiClusters {
    enum Tuning {
        static let radius = 64.0
        static let tileSize = 256.0
        static let maximumZoom = 21.0
        static let maximumLatitude = 85.05112878
    }

    struct Cluster: Equatable {
        let places: [RouteGuide.Place]

        var latitude: Double {
            places.reduce(0) { $0 + $1.latitude } / Double(places.count)
        }

        var longitude: Double {
            places.reduce(0) { $0 + $1.longitude } / Double(places.count)
        }

        func opensList(zoom: Double, maximumZoom: Double) -> Bool {
            guard let first = places.first else { return false }
            return zoom >= maximumZoom || places.allSatisfy {
                $0.latitude == first.latitude && $0.longitude == first.longitude
            }
        }
    }

    private struct Cell: Hashable {
        let column: Int
        let row: Int
    }

    private struct Point {
        let horizontal: Double
        let vertical: Double

        var cell: Cell {
            Cell(column: Int(floor(horizontal / Tuning.radius)), row: Int(floor(vertical / Tuning.radius)))
        }

        func squaredDistance(to other: Point) -> Double {
            pow(horizontal - other.horizontal, 2) + pow(vertical - other.vertical, 2)
        }
    }

    static func make(
        _ places: [RouteGuide.Place], zoom: Double, pickedId: String? = nil
    ) -> [Cluster] {
        guard zoom.isFinite else { return [] }
        var groups: [[RouteGuide.Place]] = []
        var anchors: [Point] = []
        var buckets: [Cell: [Int]] = [:]
        var seen: Set<String> = []
        let width = Tuning.tileSize * pow(2, min(max(zoom, 0), Tuning.maximumZoom))
        for place in places.sorted(by: { $0.id < $1.id }) {
            guard let point = project(place, width: width), seen.insert(place.id).inserted else { continue }
            let match = nearest(point, anchors: anchors, buckets: buckets)
            if place.id != pickedId, let match {
                groups[match].append(place)
            } else {
                let index = groups.count
                groups.append([place])
                anchors.append(point)
                // 고른 POI는 묶음의 후보가 되지 않는다. 선택 상태가 다른 가게로 옮겨지지 않는다.
                if place.id != pickedId {
                    buckets[point.cell, default: []].append(index)
                }
            }
        }
        return groups.map { Cluster(places: $0) }
    }

    private static func project(_ place: RouteGuide.Place, width: Double) -> Point? {
        guard place.latitude.isFinite, place.longitude.isFinite,
              abs(place.latitude) <= Tuning.maximumLatitude, abs(place.longitude) <= 180
        else { return nil }
        let sine = sin(place.latitude * .pi / 180)
        return Point(
            horizontal: (place.longitude + 180) / 360 * width,
            vertical: (0.5 - log((1 + sine) / (1 - sine)) / (4 * .pi)) * width
        )
    }

    private static func nearest(_ point: Point, anchors: [Point], buckets: [Cell: [Int]]) -> Int? {
        var best: Int?
        var distance = Tuning.radius * Tuning.radius
        for columnOffset in -1 ... 1 {
            for rowOffset in -1 ... 1 {
                let cell = Cell(column: point.cell.column + columnOffset, row: point.cell.row + rowOffset)
                for index in buckets[cell] ?? [] {
                    let gap = point.squaredDistance(to: anchors[index])
                    if gap < distance || (gap == distance && index < (best ?? Int.max)) {
                        best = index
                        distance = gap
                    }
                }
            }
        }
        return best
    }
}
