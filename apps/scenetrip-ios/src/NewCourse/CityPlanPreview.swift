import SceneApiClient
import SwiftUI

/// 「알아서 짜 주세요」 — 도시 추천 코스 미리보기 (MZ2AZ-547 R1–R5, 웹 `proto/ProtoScreens.tsx` `PlanPreview`).
///
/// 그 도시 촬영지(인기순)를 받아 **앱 안에서** 잇는다(`CityPlanRules.plan`, 서버 창구 없음). 카드를 꾹 눌러 그날 안에서 순서를 바꾸고,
/// 사진 위 작은 단추로 다른 곳으로 바꾸거나 뺀다. 「이 코스로 만들기」 → 서버에 저장(`POST`+`PUT`) → 정한 여행에 코스 번호 →
/// 방금 만든 코스로 표시하고 계획 화면을 편집 상태로 연다(R5·R6).
struct CityPlanPreview: View {
    let cityKey: String
    let days: Int
    let onBack: () -> Void

    /// 도시 질문은 속도를 묻지 않는다 — 보통(하루 5곳).
    static let pace = TripPace.normal

    @EnvironmentObject private var routes: RouteStore
    @ObservedObject private var store = CityPlacesStore.shared
    @ObservedObject private var router = TabRouter.shared

    @State private var plan: [[PlaceSummary]]?
    @State private var busy = false
    @State private var failed = false

    private var cityName: String {
        store.city(cityKey)?.name ?? cityKey
    }

    private var places: [PlaceSummary]? {
        store.places[cityKey]
    }

    var body: some View {
        Group {
            if let plan {
                preview(plan)
            } else {
                planning
            }
        }
        .task(id: places?.count) { await makePlan() }
    }

    private func makePlan() async {
        guard plan == nil else { return }
        guard let places else {
            await store.loadPlaces(cityKey)
            return
        }
        // 「짜는 중」 을 잠깐 보인다 — 바로 바뀌면 무엇이 일어났는지 모른다(웹과 같은 0.9초).
        try? await Task.sleep(for: .milliseconds(900))
        guard !Task.isCancelled else { return }
        let made = CityPlanRules.plan(places: places, days: days, pace: Self.pace)
        plan = made
        // 확인용 뒷문 — `-cityAutoSave 1` 이면 짜자마자 「이 코스로 만들기」 를 누른 것처럼 저장한다(프로세스에 한 번, 서버에 코스가 생긴다).
        if UserDefaults.standard.bool(forKey: "cityAutoSave"), !Self.autoSaved {
            Self.autoSaved = true
            await save(made)
        }
    }

    private static var autoSaved = false

    private var planning: some View {
        NewCourseScreen(onBack: onBack) {
            NewCourseTitle(text: tr("코스를 짜는 중이에요…"))
            if store.placeFailures.contains(cityKey) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("촬영지를 못 불러왔어요").font(.system(size: 15)).foregroundStyle(.secondary)
                    NewCourseSecondaryButton(title: tr("다시 시도")) {
                        Task { await store.loadPlaces(cityKey) }
                    }
                }
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("촬영지를 동선대로 잇고 있어요").font(.system(size: 15)).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func preview(_ plan: [[PlaceSummary]]) -> some View {
        NewCourseScreen(onBack: onBack) {
            VStack(alignment: .leading, spacing: 6) {
                NewCourseTitle(text: NewCourseText.planTitle(city: cityName, days: days))
                Text("꾹 눌러 순서를 바꾸고, 빼거나 다른 곳으로 바꾼 뒤 저장하세요")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
            }
            if plan.allSatisfy(\.isEmpty) {
                Text("이 도시는 촬영지가 적어서 코스를 못 짰어요. 뒤로 가서 「직접 고를게요」 를 눌러 주세요")
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(plan.enumerated()), id: \.offset) { day, stops in
                PlanDayList(
                    day: day,
                    stops: stops,
                    onMove: { from, to in self.plan = CityPlanRules.moved(plan, day: day, from: from, to: to) },
                    onSwap: { index in self.plan = CityPlanRules.swapped(plan, day: day, index: index, among: places ?? []) },
                    onRemove: { index in
                        withAnimation(.easeOut(duration: 0.2)) {
                            self.plan = CityPlanRules.removed(plan, day: day, index: index)
                        }
                    }
                )
            }
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if failed {
                    Text("저장하지 못했어요. 다시 눌러 주세요").font(.system(size: 13)).foregroundStyle(.red)
                }
                NewCoursePrimaryButton(
                    busy ? tr("저장하는 중…") : tr("이 코스로 만들기"),
                    disabled: busy || plan.allSatisfy(\.isEmpty)
                ) { Task { await save(plan) } }
            }
        }
    }

    private func save(_ plan: [[PlaceSummary]]) async {
        busy = true
        failed = false
        let course = CityPlanRules.course(
            title: NewCourseText.courseTitle(city: cityName, days: days),
            plan: plan, pace: Self.pace, madeByAI: true
        )
        let saved = await routes.save(course, origin: "city")
        busy = false
        guard let id = saved?.serverId else {
            failed = true
            return
        }
        TripMemoryStore.shared.set(TripMemo(city: cityKey, days: days, pace: Self.pace, courseId: id))
        router.courseCreated(id: id, city: cityName, days: days)
    }
}

/// 하루치 카드 줄 — 꾹 0.3초 눌러 들고 끌면 그날 안에서 순서가 바뀐다 (R4, 웹 `useHoldReorder`).
///
/// 들리기 전에 8pt 넘게 움직이면 넘기기로 보고 놓아 준다(`LongPressGesture` 의 `maximumDistance`). 들린 카드는 1.02배·그림자·햅틱.
/// 이웃 카드 높이의 절반을 넘으면 자리를 바꾼다(`HoldReorderRules.step`).
private struct PlanDayList: View {
    let day: Int
    let stops: [PlaceSummary]
    let onMove: (Int, Int) -> Void
    let onSwap: (Int) -> Void
    let onRemove: (Int) -> Void

    @State private var dragging: Int64?
    @State private var offset: Double = 0
    /// 지금까지 자리를 바꾸느라 덜어 낸 거리 — 손가락의 전체 움직임에서 뺀다.
    @State private var consumed: Double = 0
    @State private var heights: [Int64: Double] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(String(format: tr("%lld일차"), day + 1)).font(.system(size: 17, weight: .heavy))
                Text(String(format: tr("%lld곳"), stops.count)).font(.system(size: 13)).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            VStack(spacing: 12) {
                ForEach(Array(stops.enumerated()), id: \.element.id) { index, place in
                    card(place, index: index)
                }
            }
        }
        .sensoryFeedback(.impact, trigger: dragging) { _, new in new != nil }
    }

    private func card(_ place: PlaceSummary, index: Int) -> some View {
        let lifted = dragging == place.id
        return PlanSpotCard(
            place: place, number: index + 1,
            onSwap: { onSwap(index) }, onRemove: { onRemove(index) }
        )
        .background {
            GeometryReader { geo in
                Color.clear
                    .onAppear { heights[place.id] = Double(geo.size.height) }
                    .onChange(of: geo.size.height) { _, height in heights[place.id] = Double(height) }
            }
        }
        .scaleEffect(lifted ? 1.02 : 1)
        .shadow(color: .black.opacity(lifted ? 0.14 : 0), radius: 10, y: 6)
        .offset(y: lifted ? offset : 0)
        .zIndex(lifted ? 2 : 0)
        .animation(lifted ? nil : .easeOut(duration: 0.16), value: stops.map(\.id))
        .gesture(hold(place.id))
        .accessibilityActions {
            if index > 0 {
                Button(tr("위로 옮기기")) { onMove(index, index - 1) }
            }
            if index < stops.count - 1 {
                Button(tr("아래로 옮기기")) { onMove(index, index + 1) }
            }
        }
    }

    private func hold(_ id: Int64) -> some Gesture {
        LongPressGesture(minimumDuration: HoldReorderRules.holdDuration, maximumDistance: HoldReorderRules.slop)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .global))
            .onChanged { value in
                switch value {
                case .first(true):
                    if dragging == nil {
                        dragging = id
                        offset = 0
                        consumed = 0
                    }
                case let .second(true, drag):
                    if dragging == nil {
                        dragging = id
                        consumed = 0
                    }
                    follow(id, translation: Double(drag?.translation.height ?? 0))
                default:
                    break
                }
            }
            .onEnded { _ in
                withAnimation(.easeOut(duration: 0.16)) {
                    dragging = nil
                    offset = 0
                }
                consumed = 0
            }
    }

    private func follow(_ id: Int64, translation: Double) {
        guard let index = stops.firstIndex(where: { $0.id == id }) else { return }
        let below: Double? = stops.indices.contains(index + 1) ? heights[stops[index + 1].id] : nil
        let above: Double? = stops.indices.contains(index - 1) ? heights[stops[index - 1].id] : nil
        let dy = translation - consumed
        let step = HoldReorderRules.step(index: index, count: stops.count, offset: dy, below: below, above: above)
        if step.to != index {
            consumed += dy - step.offset
            withAnimation(.easeOut(duration: 0.16)) { onMove(index, step.to) }
        }
        offset = step.offset
    }
}

/// 추천 코스의 촬영지 카드 — 사진 16:9 위, 그 아래 「어느 작품의」(보라 표) 「어떤 장소」(이름) 「어떤 장면」(설명) (R3).
struct PlanSpotCard: View {
    let place: PlaceSummary
    let number: Int
    let onSwap: () -> Void
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { NewCoursePhoto(url: place.imageUrl) }
                .clipped()
                .overlay(alignment: .topLeading) {
                    Text(verbatim: "\(number)")
                        .font(.system(size: 13, weight: .heavy).monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .frame(minWidth: 26, minHeight: 26)
                        .background(Capsule().fill(NewCourseStyle.purple))
                        .padding(10)
                        .accessibilityHidden(true)
                }
                .overlay(alignment: .topTrailing) {
                    HStack(spacing: -6) {
                        PhotoRoundButton(symbol: "arrow.clockwise", label: tr("다른 곳으로 바꾸기"), action: onSwap)
                        PhotoRoundButton(symbol: "xmark", label: tr("빼기"), action: onRemove)
                    }
                    .padding(2)
                }
            VStack(alignment: .leading, spacing: 6) {
                if let work = place.contents?.first?.title, !work.isEmpty {
                    WorkTag(title: work)
                }
                Text(verbatim: place.name)
                    .font(.system(size: 17, weight: .heavy))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                if let scene = place.sceneDescription?.trimmingCharacters(in: .whitespacesAndNewlines), !scene.isEmpty {
                    Text(verbatim: scene)
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                } else if let address = place.address, !address.isEmpty {
                    Text(verbatim: address).font(.system(size: 13)).foregroundStyle(.tertiary).lineLimit(1)
                }
            }
            .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusL))
        .shadow(color: .black.opacity(0.08), radius: 5, y: 2)
        .contentShape(.rect)
        .accessibilityElement(children: .contain)
    }
}
