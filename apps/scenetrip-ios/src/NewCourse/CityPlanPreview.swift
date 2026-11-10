import SceneApiClient
import SwiftUI

/// 「알아서 짜 주세요」 — 도시 추천 코스 미리보기 (MZ2AZ-547 R1–R5, 웹 `proto/ProtoScreens.tsx` `PlanPreview`).
///
/// 서버 AI 일정(`POST /guide/plan {city}`, MZ2AZ-548)을 응답만으로 카드로 그린다 — 도시 촬영지 목록은 받지 않고, 못 받으면 앱 계산으로
/// 대신하지 않고 「다시 시도」 를 보인다(2026-11-10 사용자 결정, 계획 city-ai-course.md §5). 사진 위 작은 단추로 다른 곳으로 바꾸거나 뺀다
/// (꾹 눌러 순서 바꾸기는 뺐다). 「이 코스로 만들기」 → 서버에 저장(`POST`+`PUT`) → 정한 여행에 코스 번호 →
/// 방금 만든 코스로 표시하고 계획 화면을 편집 상태로 연다(R5·R6).
struct CityPlanPreview: View {
    let cityKey: String
    let days: Int
    let onBack: () -> Void

    /// 도시 질문은 속도를 묻지 않는다 — 보통(하루 5곳).
    static let pace = TripPace.normal
    /// 「다른 곳으로 바꾸기」 에서 물을 근처 촬영지 수.
    static let nearbyLimit = 10

    @EnvironmentObject private var routes: RouteStore
    @ObservedObject private var store = CityPlacesStore.shared
    @ObservedObject private var router = TabRouter.shared

    @State private var plan: [[PlaceSummary]]?
    /// AI 일정을 못 받았다 — 「다시 시도」.
    @State private var planFailed = false
    /// 다시 시도를 누를 때마다 오른다 — 일정을 다시 묻는다.
    @State private var attempt = 0
    @State private var busy = false
    @State private var failed = false

    private var cityName: String {
        store.city(cityKey)?.name ?? cityKey
    }

    var body: some View {
        Group {
            if let plan {
                preview(plan)
            } else {
                planning
            }
        }
        .task(id: attempt) { await makePlan() }
    }

    private func makePlan() async {
        guard plan == nil else { return }
        planFailed = false
        guard let made = await aiPlan() else {
            if !Task.isCancelled {
                planFailed = true
            }
            return
        }
        guard !Task.isCancelled else { return }
        plan = made
        // 확인용 뒷문 — `-cityAutoSave 1` 이면 짜자마자 「이 코스로 만들기」 를 누른 것처럼 저장한다(프로세스에 한 번, 서버에 코스가 생긴다).
        if UserDefaults.standard.bool(forKey: "cityAutoSave"), !Self.autoSaved {
            Self.autoSaved = true
            await save(made)
        }
    }

    private static var autoSaved = false

    /// 서버 AI 일정 — 못 받거나(503·시간 초과·네트워크) 카드로 그릴 곳이 하나도 없으면 nil.
    private func aiPlan() async -> [[PlaceSummary]]? {
        let request = CityPlanRules.aiRequest(city: cityKey, days: days, pace: Self.pace)
        guard let reply = try? await GuideAPI.planWithGuide(guidePlanRequest: request) else { return nil }
        let made = CityPlanRules.fromGuide(reply.plan)
        return made.isEmpty ? nil : made
    }

    /// 「다른 곳으로 바꾸기」 — 그 카드 자리에서 가까운 그 도시 촬영지를 물어 아직 코스에 없는 첫 곳으로. 못 받으면 그대로.
    private func swap(day: Int, index: Int) async {
        guard let current = plan, current.indices.contains(day), current[day].indices.contains(index) else { return }
        let spot = current[day][index]
        guard let nearby = try? await PlacesAPI.listPlacesWithRequestBuilder(
            lat: spot.latitude, lng: spot.longitude, sort: .distance, limit: Self.nearbyLimit, city: cityKey
        ).coordinateSource(.userSelected).execute().body.items else { return }
        guard let latest = plan else { return }
        plan = CityPlanRules.swapped(latest, day: day, index: index, among: nearby)
    }

    private var planning: some View {
        NewCourseScreen(onBack: onBack) {
            NewCourseTitle(text: tr("코스를 짜는 중이에요…"))
            if planFailed {
                VStack(alignment: .leading, spacing: 10) {
                    Text("코스를 못 짰어요").font(.system(size: 15)).foregroundStyle(.secondary)
                    NewCourseSecondaryButton(title: tr("다시 시도")) { attempt += 1 }
                }
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("AI 가 동선을 짜고 있어요").font(.system(size: 15)).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func preview(_ plan: [[PlaceSummary]]) -> some View {
        NewCourseScreen(onBack: onBack) {
            VStack(alignment: .leading, spacing: 6) {
                NewCourseTitle(text: NewCourseText.planTitle(city: cityName, days: days))
                Text("빼거나 다른 곳으로 바꾼 뒤 저장하세요")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(plan.enumerated()), id: \.offset) { day, stops in
                PlanDayList(
                    day: day,
                    stops: stops,
                    onSwap: { index in Task { await swap(day: day, index: index) } },
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

/// 하루치 카드 줄 (R4). 꾹 눌러 순서 바꾸기는 뺐다(2026-11-10 사용자 결정 — 스크롤과 엉켜 동작하지 않았다).
private struct PlanDayList: View {
    let day: Int
    let stops: [PlaceSummary]
    let onSwap: (Int) -> Void
    let onRemove: (Int) -> Void

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
                    PlanSpotCard(place: place, number: index + 1, onSwap: { onSwap(index) }, onRemove: { onRemove(index) })
                        .animation(.easeOut(duration: 0.16), value: stops.map(\.id))
                }
            }
        }
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
