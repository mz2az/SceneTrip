import CoreLocation
import SwiftUI

/// 편집 화면이 **가이드 챗봇에게 주는 화면 상태.**
///
/// `RouteEditorView.swift` 에서 떼어 냈다(타입 길이 한도). 화면에서 일어난 일은
/// 모델도 알아야 한다 — 발자국이 다 찍혔는데 「방문 여부는 알려지지 않았어요」라고
/// 답했다(2026-09-05 사용자 지적). 질문마다 답을 적는 대신 **상태의 항목을 넓힌다.**
extension RouteEditorView {
    /// 가이드에게 줄 화면 상태 — **지금 일차의 번호 핀 그대로.**
    ///
    /// 순서를 바꾸거나 동선 최적화를 누르면 번호가 달라지는데, 그때마다 다시
    /// 만들어지므로 모델이 보는 번호와 지도의 번호가 어긋나지 않는다
    /// (2026-08-27 사용자 지적 — 앞서 아예 안 보내서 「2번이 어디냐」를 몰랐다).
    var guideContext: RouteGuide.Context {
        RouteGuide.Context(
            stops: stops.enumerated().map { index, stop in
                .init(
                    number: index + 1,
                    name: stop.place.name,
                    kind: stop.place.type,
                    latitude: stop.place.latitude,
                    longitude: stop.place.longitude,
                    visited: stop.visited
                )
            },
            picked: focusedStop.map {
                .init(
                    number: 0, name: $0.place.name, kind: $0.place.type,
                    latitude: $0.place.latitude, longitude: $0.place.longitude
                )
            },
            trip: guideTrip
        )
    }

    /// 가이드에게 줄 **여행 상태** — 단계·일차·가는 곳·남은 직선거리·걸은 거리.
    /// 발자국이 찍힌 것을 모델이 모르면 말이 안 된다(2026-09-05 사용자 지적).
    var guideTrip: RouteGuide.Context.Trip {
        let phase = switch trip.phase {
        case .idle: "plan"
        case .guiding: "guiding"
        case .arrived: "arrived"
        }
        var meters: Int?
        if let target = trip.target, let here = trip.here {
            let from = CLLocation(latitude: here.latitude, longitude: here.longitude)
            let to = CLLocation(latitude: target.place.latitude, longitude: target.place.longitude)
            meters = Int(from.distance(from: to))
        }
        // 지도의 발자국과 같은 창(최근 하루)으로 잰다.
        let since = Date().addingTimeInterval(-24 * 3600)
        let recent = footprints.points.filter { $0.at >= since }
        let walked = zip(recent, recent.dropFirst()).reduce(0.0) { sum, pair in
            sum + CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
                .distance(from: CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude)) / 1000
        }
        return .init(
            phase: phase, course: course.title,
            day: dayIndex + 1, days: course.days.count,
            targetNumber: trip.target == nil ? nil : trip.targetNumber,
            targetMeters: meters, walkedKilometers: walked
        )
    }
}
