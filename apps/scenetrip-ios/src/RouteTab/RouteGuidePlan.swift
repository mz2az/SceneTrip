import Foundation
import SceneApiClient

/// 계약의 일정 초안(`GuidePlan`) ↔ 화면의 코스(`RouteCourse`) (MZ2AZ-321).
///
/// **저장된 코스와 다른 모양이고 일부러 합치지 않았다** — 계약이 「견적서와 계약서」라고 부른다.
/// 초안에는 도착 시각과 「넣지 못한 곳」이 있고 DB id 가 없다. 그래서 `RouteBridge` 와 따로 둔다.
///
/// 두 방향이 다 필요하다. 마법사와 챗봇의 `plan.*` 이 준 초안을 화면에 그리고(→ `RouteCourse`),
/// 챗봇으로 고치려면 편집 중인 사본을 **같은 모양으로** 되돌려 보낸다(→ `GuidePlan`). 편집 중에는
/// 앱이 정본이라 서버가 DB 에서 대신 넣지 않는다.
enum RouteGuidePlan {
    // MARK: 계약 → 화면

    /// 초안을 코스로. `placeId` 가 없는 정지점은 **이름으로 대체하지 않는다** — 그 줄은
    /// `placeMissing` 으로 남고 저장에서 빠진다(`RouteBridge.replace`). 좌표가 없는 정지점은
    /// 그릴 수 없으므로 뺀 곳으로 적어 두고 넣지 않는다.
    static func course(
        from plan: GuidePlan, title: String, startDate: Date?, pace: RoutePace
    ) -> RouteCourse {
        var skipped: [String] = []
        let days = plan.days.sorted { $0.day < $1.day }.map { day in
            RouteDay(stops: day.stops.sorted { $0.order < $1.order }.compactMap { stop in
                guard let latitude = stop.latitude, let longitude = stop.longitude else {
                    skipped.append("\(stop.name) — 좌표 없음")
                    return nil
                }
                return RouteStop(
                    place: PlaceSummary(
                        id: stop.placeId ?? Self.missingId(for: stop),
                        name: stop.name,
                        type: nil,
                        address: stop.address,
                        latitude: latitude,
                        longitude: longitude
                    ),
                    stayMinutes: stop.dwellMinutes ?? RouteStop.defaultStayMinutes,
                    arriveMinute: stop.arriveMinute,
                    placeMissing: stop.placeId == nil
                )
            })
        }
        return RouteCourse(
            title: title,
            startDate: startDate,
            pace: pace,
            days: days.isEmpty ? [RouteDay()] : days,
            madeByAI: true,
            draftNotes: notes(from: plan) + skipped.map { "뺀 곳 · \($0)" }
        )
    }

    /// 사용자에게 알릴 것 — 뺀 곳과 이유, 에이전트의 주의, 거리의 근거.
    /// 「왜 빠졌는지」를 볼 수 있어야 한다: 요청한 작품의 촬영지가 말없이 사라지면 추천이 틀렸다고 느낀다.
    static func notes(from plan: GuidePlan) -> [String] {
        var out: [String] = []
        for day in plan.days {
            for dropped in day.dropped ?? [] {
                out.append("\(day.day)일차에서 뺀 곳 · \(dropped.name) — \(dropped.reason)")
            }
        }
        out += plan.notes ?? []
        if plan.travelBasis == .straightLine {
            out.append("거리는 직선 어림이에요 — 실제 길은 더 길 수 있어요")
        }
        return out
    }

    /// 「09:00」. 계약은 0시 기준 정수 분(`540`)만 주고 표시 문자열은 앱이 만든다.
    static func clock(_ minute: Int) -> String {
        let bounded = ((minute % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", bounded / 60, bounded % 60)
    }

    // MARK: 화면 → 계약

    /// 편집 중인 코스를 초안 모양으로. 챗봇의 `GuideContext.plan` 에 실린다.
    ///
    /// 도착 시각을 모르는 줄(손으로 담은 곳)은 `0` 으로 보낸다 — 계약이 필수로 요구하고, 에이전트는
    /// 매 턴 이 사본으로 갈아 끼운 뒤 다시 계산한다.
    static func plan(from course: RouteCourse) -> GuidePlan {
        GuidePlan(
            pace: course.pace == .loose ? .relaxed : .packed,
            days: course.days.enumerated().map { dayIndex, day in
                GuidePlanDay(
                    day: dayIndex + 1,
                    stops: day.stops.enumerated().map { index, stop in
                        GuidePlanStop(
                            order: index + 1,
                            placeId: stop.savablePlaceId,
                            name: stop.place.name,
                            address: stop.place.address,
                            latitude: stop.place.latitude,
                            longitude: stop.place.longitude,
                            arriveMinute: stop.arriveMinute ?? 0,
                            dwellMinutes: stop.stayMinutes
                        )
                    }
                )
            }
        )
    }

    /// `placeId` 가 없는 정지점의 임시 id. 음수라 촬영지 id 와 겹치지 않고, 직접 찍은 핀(`RouteMock.
    /// pinnedPlace`)과도 다른 자리(`-1_000_000_000` 아래)를 쓴다 — 저장 여부가 다른 둘이 섞이면 안 된다.
    private static func missingId(for stop: GuidePlanStop) -> Int64 {
        -1_000_000_000 - Int64(abs(stop.name.hashValue % 1_000_000)) - Int64(stop.order)
    }
}
