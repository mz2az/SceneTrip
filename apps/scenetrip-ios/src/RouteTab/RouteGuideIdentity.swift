import SceneApiClient

extension RouteGuidePlan {
    /// 서로 다른 표의 같은 숫자는 다른 대상이다. 이름·좌표로 ID를 복원하지 않는다.
    enum Identity: Hashable {
        case place(Int64)
        case poi(Int64)
        case pin(Int64)

        var kind: RouteStop.Kind {
            switch self {
            case .place: .place
            case let .poi(id): .poi(id)
            case .pin: .pin
            }
        }
    }

    static func identity(of stop: GuidePlanStop) -> Identity? {
        let ids = [stop.placeId, stop.poiId, stop.customPinId].compactMap { $0 }
        guard ids.count == 1, ids[0] > 0 else { return nil }
        if let id = stop.placeId {
            return .place(id)
        }
        if let id = stop.poiId {
            return .poi(id)
        }
        return stop.customPinId.map(Identity.pin)
    }

    private static func identity(of stop: RouteStop) -> Identity? {
        guard !stop.placeMissing else { return nil }
        switch stop.kind {
        case .place: return stop.placeId.map(Identity.place)
        case let .poi(id): return .poi(id)
        case .pin: return stop.customPinId.map(Identity.pin)
        }
    }

    /// 숨김·폐업 상태는 같은 대상의 모든 행에 이어받는다.
    /// 순서·일차가 바뀌어도 유일하게 대응하는 항목만 서버 번호·방문 상태를 이어받는다.
    /// 같은 대상을 두 번 담은 코스는 GuidePlan에 항목 번호가 없어 임의 매칭하지 않는다.
    static func revising(_ original: RouteCourse, with plan: GuidePlan) -> RouteCourse {
        var next = course(from: plan, title: original.title, startDate: original.startDate, pace: original.pace)
        next.serverId = original.serverId
        next.detailLoaded = original.detailLoaded
        next.isRunning = original.isRunning
        let oldStops = Dictionary(grouping: original.days.flatMap(\.stops), by: identity(of:))
        let newStops = Dictionary(grouping: next.days.flatMap(\.stops), by: identity(of:))
        let unavailable = oldStops.compactMapValues { $0.compactMap(\.unavailable).first }
        for day in next.days.indices {
            for index in next.days[day].stops.indices {
                var stop = next.days[day].stops[index]
                guard let key = identity(of: stop) else { continue }
                stop.unavailable = unavailable[key]
                if let matches = oldStops[key], matches.count == 1,
                   newStops[key]?.count == 1, let previous = matches.first
                {
                    stop.serverItemId = previous.serverItemId
                    stop.visited = previous.visited
                    stop.place.type = previous.place.type
                    stop.poiText = previous.poiText
                }
                next.days[day].stops[index] = stop
            }
        }
        return next
    }
}
