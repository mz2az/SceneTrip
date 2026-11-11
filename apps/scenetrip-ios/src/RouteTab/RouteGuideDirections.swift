import SwiftUI

/// 가이드 답의 장소 카드 「길찾기」(MZ2AZ-554) — 누르면 무엇을 어떤 순서로 하나.
///
/// - 로그인 안 했으면 길찾기 로그인 안내만 띄운다. 코스에 담지도, 안내를 켜지도 않는다.
/// - 코스에 없는 곳이면 먼저 담는다(가이드의 기존 담기 길, `addGuidePlace`).
/// - 여행 중이 아니면 그곳을 목적지로 여행을 시작하고, 이미 여행 중이면 목적지만 바꾼다.
///
/// 웹 `route/guide/guideRules.ts` 의 `guideDirectionsSteps` 와 같은 값이다.
enum RouteGuideDirections {
    enum Step: Equatable {
        case signIn, add, startTrip, switchTarget
    }

    static func steps(signedIn: Bool, inCourse: Bool, tripRunning: Bool) -> [Step] {
        guard signedIn else { return [.signIn] }
        return (inCourse ? [] : [.add]) + [tripRunning ? .switchTarget : .startTrip]
    }
}

extension RouteEditorView {
    /// 가이드 카드의 「길찾기」를 눌렀다 — `RouteGuideDirections.steps` 를 차례로 한다.
    func guideDirections(_ place: RouteGuide.Place) {
        let auth = AuthStore.shared
        let steps = RouteGuideDirections.steps(
            signedIn: auth.signedIn, inCourse: isAdded(place), tripRunning: course.isRunning
        )
        if steps.contains(.signIn) {
            // 검색 탭 「길찾기」와 같은 안내(`PlaceDirections.askSignIn`). 로그인한 뒤 다시 누르면 이어진다.
            auth.promptSignIn()
            auth.message = tr("로그인하면 바로 길안내를 시작해요")
            return
        }
        if steps.contains(.add) {
            addGuidePlace(place)
        }
        // 담긴 일차가 지금 보는 일차가 아니면 그 일차로 옮긴다 — 안내 번호는 일차 안의 순서다.
        if !stops.contains(where: { place.isSameSpot(as: $0) }),
           let day = course.days.firstIndex(where: { $0.stops.contains { place.isSameSpot(as: $0) } }) {
            dayIndex = day
        }
        guard let stop = stops.first(where: { place.isSameSpot(as: $0) }) else { return }
        // 창을 접어 지도와 안내 띠가 보이게 한다.
        showGuide = false
        if steps.contains(.startTrip) {
            startTripRunning(to: stop)
        } else {
            startTrip(to: stop)
        }
    }

    /// 여행 전이면 코스를 「여행 중」으로 바꾼 **뒤에** 그곳으로 안내를 켠다(「코스 시작」과 같은 순서 —
    /// 서버가 여행 중을 안 뒤에 길을 묻지 않으면 거절된다, 2026-09-17). 갈 수 없는 곳이면 카드만 연다.
    private func startTripRunning(to stop: RouteStop) {
        guard stop.canNavigate else {
            pickedStop = stop
            return
        }
        course.isRunning = true
        let dayNo = dayIndex + 1
        Task {
            await store.setRunning(course, true, dayNo: dayNo)
            guard course.isRunning else { return }
            startTrip(to: PlacePoiLink.row(of: stop, in: stops) ?? stop)
        }
    }
}
