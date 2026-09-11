import CoreLocation
import SceneApiClient
import SwiftUI

/// 편집 화면과 **가이드 챗봇** 사이 (MZ2AZ-321).
///
/// 두 방향이다. 질문마다 화면 상태를 실어 보내고(`guideContext`), 답에 실린 `effects`·`ui` 를
/// 화면에 적용한다(`applyGuideAnswer`). 화면에서 일어난 일은 모델도 알아야 한다 — 발자국이
/// 다 찍혔는데 「방문 여부는 알려지지 않았어요」라고 답했다(2026-09-05 사용자 지적).
/// 질문마다 답을 적는 대신 **상태의 항목을 넓힌다.**
extension RouteEditorView {
    // MARK: 요청에 싣는 것

    /// 가이드에게 줄 화면 상태 — **지금 일차의 번호 핀 그대로.**
    ///
    /// 순서를 바꾸거나 동선 최적화를 누르면 번호가 달라지는데, 그때마다 다시 만들어지므로
    /// 모델이 보는 번호와 지도의 번호가 어긋나지 않는다(2026-08-27 사용자 지적 — 앞서 아예
    /// 안 보내서 「2번이 어디냐」를 몰랐다).
    ///
    /// `plan` 은 편집 중인 일정 전체다. 없으면 「2일차에서 빼 줘」가 거절되고, 편집 중엔 앱이
    /// 정본이라 서버가 DB 에서 대신 넣지 않는다(계약 `GuideContext.plan`).
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
            trip: guideTrip,
            plan: RouteGuidePlan.plan(from: course)
        )
    }

    /// 가이드에게 줄 **여행 상태** — 단계·일차·가는 곳·남은 직선거리·걸은 거리.
    /// 여행 중이 아니면 `nil` 이다(계약 `GuideTrip`: 없으면 여행 중이 아닌 것).
    var guideTrip: RouteGuide.Context.Trip? {
        guard course.isRunning else { return nil }
        let phase: RouteGuide.Context.Trip.Phase = switch trip.phase {
        case .idle: .plan
        case .guiding: .guiding
        case .arrived: .arrived
        }
        var meters: Int?
        if let target = trip.target, let here = trip.here {
            let from = CLLocation(latitude: here.latitude, longitude: here.longitude)
            let to = CLLocation(latitude: target.place.latitude, longitude: target.place.longitude)
            meters = Int(from.distance(from: to))
        }
        // 지도의 발자국과 같은 창(최근 하루)으로 잰다. 소수 첫째 자리 — 계약이 그렇게 적었다.
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
            targetMeters: meters, walkedKilometers: (walked * 10).rounded() / 10
        )
    }

    // MARK: 답에서 받은 명령

    /// 답에 실린 `effects` 와 `ui` 를 화면에 적용한다. 모르는 명령은 무시한다 — 에이전트가
    /// 명령을 늘려도 옛 앱이 깨지지 않게(계약 `GuideEffect`·`GuideUiDirective`).
    func applyGuideAnswer(_ answer: RouteGuide.Answer) {
        for effect in answer.effects {
            applyGuideEffect(effect)
        }
        for directive in answer.ui.compactMap(RouteGuide.Directive.init(contract:)) {
            applyGuideDirective(directive, places: answer.places)
        }
    }

    /// `plan.*` 은 편집 사본을 **갈아 끼운다. 저장하지 않는다** — 저장은 「완료」의
    /// `PUT /courses/{id}` 뿐이라 「취소」가 공짜로 남는다. `cart.*` 는 서버가 이미 저장했으니
    /// 장바구니만 다시 읽는다.
    private func applyGuideEffect(_ effect: GuideEffect) {
        switch effect.op {
        case "plan.draft", "plan.revise", "plan.move":
            guard let plan = effect.plan else { return }
            replaceDraft(with: plan)
        case "cart.add", "cart.remove":
            Task { await cart.refresh() }
        default:
            break
        }
    }

    private func replaceDraft(with plan: GuidePlan) {
        var next = RouteGuidePlan.course(
            from: plan, title: course.title, startDate: course.startDate, pace: course.pace
        )
        // 서버 id 와 여행 상태는 사본의 것이다 — 초안은 내용만 바꾼다.
        next.serverId = course.serverId
        next.isRunning = course.isRunning
        course = next
        dayIndex = min(dayIndex, max(course.days.count - 1, 0))
        focusedStop = nil
        fitToken += 1
    }

    /// 명령은 **의도 수준**이다 — 「2일차를 보여 줘」라고 오고, 어느 화면을 어떻게 띄울지는
    /// 앱이 정한다(계약 `GuideUiDirective`). iOS 와 Android 가 서로 다른 앱이 되지 않게.
    private func applyGuideDirective(_ directive: RouteGuide.Directive, places: [RouteGuide.Place]) {
        switch directive {
        case let .mapFocus(ids):
            // 그 핀들이 다 보이게. 지도는 가이드 장소가 있으면 그것에만 맞춘다(`RouteMapView.fit`).
            guide.picked = nil
            previewPlaces = guidePlaces(ids, in: places).map(\.asPlaceSummary)
            fitToken += 1
        case let .routeDraw(ids):
            // 순서대로 핀을 찍는다. 임의 장소 사이의 선은 아직 없다(계획 guide-app.md §3).
            guide.picked = nil
            previewPlaces = guidePlaces(ids, in: places).map(\.asPlaceSummary)
            fitToken += 1
        case let .placeCard(id):
            guide.picked = places.first { $0.serverId == id }
        case let .courseOpen(day):
            openDay(day)
            panelDetent = .medium
        case let .courseFocus(day, changed):
            openDay(day)
            // 바뀐 줄을 잠깐 강조 — 무엇이 바뀌었는지 눈에 보여야 바뀐 줄 안다. 고른 줄 표시
            // (`isFocused`)가 이미 그 일을 하므로 첫 번째 바뀐 곳을 고른다.
            let names = Set(changed)
            focusedStop = stops.first { names.contains($0.place.name) }
        case .sheetCollapse:
            showGuide = false
        }
    }

    private func openDay(_ day: Int) {
        guard !course.days.isEmpty else { return }
        dayIndex = min(max(day - 1, 0), course.days.count - 1)
        fitToken += 1
    }

    /// `ui` 의 `placeIds` 는 이번 턴 `places[].id` 를 가리킨다. 순서는 명령의 순서다.
    private func guidePlaces(_ ids: [Int64], in places: [RouteGuide.Place]) -> [RouteGuide.Place] {
        ids.compactMap { id in places.first { $0.serverId == id } }
    }
}

/// 타입 본문 길이(swiftlint 350줄) 때문에 여기 둔다 — 초안 알림줄은 가이드·마법사 결과의 일부다.
extension RouteEditorView {
    /// 초안의 알림줄 — 접힌 한 줄(「뺀 곳 7 · 주의 3」)이고 누르면 펼쳐진다. 열 줄을 다 펼쳐 두면
    /// 지도가 밀려 내려간다(2026-09-11 실측). 저장하면 사라지는 값이라 저장 전에만 보인다.
    @ViewBuilder var draftNotes: some View {
        if !course.draftNotes.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { showDraftNotes.toggle() }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "info.circle").font(.caption2)
                        Text(RouteGuidePlan.notesSummary(course.draftNotes)).font(.caption2.weight(.medium))
                        Image(systemName: showDraftNotes ? "chevron.up" : "chevron.down").font(.system(size: 9))
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(.secondary)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                if showDraftNotes {
                    ForEach(course.draftNotes, id: \.self) { note in
                        Text(note)
                            .font(.caption2).foregroundStyle(.secondary)
                            .lineLimit(2)
                            .padding(.leading, 18)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.bottom, 8)
        }
    }
}
