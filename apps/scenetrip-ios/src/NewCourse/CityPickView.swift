import SceneApiClient
import SwiftUI

/// 「직접 고를게요」 — 도시 촬영지 목록에서 담기 (MZ2AZ-547 S1, 웹 `proto/protoParts.tsx` `CityPicker`).
///
/// 담기·빼기는 진짜 보관함(`POST/DELETE /cart/items`)이다. 아래 「N곳으로 코스 만들기」 는 고른 곳을 날마다 나눠
/// (`CityPlanRules.splitDays`) 「{도시} 여행」 으로 저장하고 추천 코스(R5)와 같은 길로 계획 화면을 연다.
/// 며칠은 질문(Q2) 값을 쓴다 — 다시 묻지 않는다.
struct CityPickView: View {
    let cityKey: String
    let days: Int
    let onBack: () -> Void

    /// 목록에 늘어놓는 수.
    static let limit = 30

    @EnvironmentObject private var routes: RouteStore
    @ObservedObject private var store = CityPlacesStore.shared
    @ObservedObject private var router = TabRouter.shared
    @StateObject private var cart = CartStore()

    @State private var busy = false
    @State private var failed = false

    private var cityName: String {
        store.city(cityKey)?.name ?? cityKey
    }

    private var places: [PlaceSummary] {
        Array((store.places[cityKey] ?? []).prefix(Self.limit))
    }

    /// 이 도시 목록 가운데 담은 곳 — 목록 순서대로.
    private var chosen: [PlaceSummary] {
        places.filter { cart.contains($0.id) }
    }

    var body: some View {
        NewCourseScreen(onBack: onBack) {
            VStack(alignment: .leading, spacing: 6) {
                NewCourseTitle(text: String(format: tr("%@의 촬영지"), cityName))
                Text("가 보고 싶은 곳을 담으면 동선을 이어 코스로 만들어 드려요")
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
            }
            VStack(spacing: 10) {
                if store.places[cityKey] == nil {
                    if store.placeFailures.contains(cityKey) {
                        NewCourseSecondaryButton(title: tr("다시 시도")) {
                            Task { await store.loadPlaces(cityKey) }
                        }
                    } else {
                        ProgressView().padding(24)
                    }
                } else if places.isEmpty {
                    Text("아직 이 도시의 촬영지가 없어요. 뒤로 가서 다른 도시를 골라 주세요")
                        .font(.system(size: 15)).foregroundStyle(.secondary)
                }
                ForEach(places, id: \.id) { place in
                    SpotRow(place: place, saved: cart.contains(place.id)) {
                        Task {
                            if cart.contains(place.id) {
                                await cart.remove(placeId: place.id)
                            } else {
                                await cart.add(placeId: place.id, sourceContentId: place.contents?.first?.contentId)
                            }
                        }
                    }
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if failed {
                    Text("코스를 만들지 못했어요. 다시 눌러 주세요").font(.system(size: 13)).foregroundStyle(.red)
                }
                NewCoursePrimaryButton(buttonTitle, disabled: chosen.isEmpty || busy) {
                    Task { await create() }
                }
            }
        }
        .task {
            async let placesJob: Void = store.loadPlaces(cityKey)
            async let cartJob: Void = cart.refresh()
            _ = await (placesJob, cartJob)
        }
    }

    private var buttonTitle: String {
        if busy {
            return tr("만드는 중…")
        }
        return chosen.isEmpty ? tr("가 볼 곳을 담아 주세요") : String(format: tr("%lld곳으로 코스 만들기"), chosen.count)
    }

    private func create() async {
        busy = true
        failed = false
        let course = CityPlanRules.course(
            title: NewCourseText.pickedTitle(city: cityName),
            plan: CityPlanRules.splitDays(chosen, days: days),
            pace: CityPlanPreview.pace, madeByAI: false
        )
        let saved = await routes.save(course, origin: "city")
        busy = false
        guard let id = saved?.serverId else {
            failed = true
            return
        }
        TripMemoryStore.shared.set(TripMemo(city: cityKey, days: days, pace: CityPlanPreview.pace, courseId: id))
        router.courseCreated(id: id, city: cityName, days: days)
    }
}

/// 촬영지 한 줄 — 사진 72 · 이름 15 두 줄 · film+작품 · 장면 설명(없으면 주소) 두 줄 · [＋ 담기]/[✓ 담음] (웹 `SpotRow`).
struct SpotRow: View {
    let place: PlaceSummary
    let saved: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            NewCoursePhoto(url: place.imageUrl)
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: place.name)
                    .font(.system(size: 15, weight: .bold))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                if let work = place.contents?.first?.title, !work.isEmpty {
                    WorkTag(title: work, compact: true)
                }
                Text(verbatim: detail)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onToggle) {
                HStack(spacing: 4) {
                    Image(systemName: saved ? "checkmark" : "plus").font(.system(size: 13, weight: .bold))
                    Text(saved ? "담음" : "담기").font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(saved ? .white : NewCourseStyle.purple)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(Capsule().fill(saved ? NewCourseStyle.purple : Color(.systemGray6)))
                .contentShape(.rect)
            }
            .buttonStyle(.press)
            .frame(maxHeight: .infinity)
            .accessibilityAddTraits(saved ? .isSelected : [])
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: NewCourseStyle.radiusM)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.08), radius: 5, y: 2)
        )
    }

    private var detail: String {
        let scene = place.sceneDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return scene.isEmpty ? (place.address ?? "") : scene
    }
}
