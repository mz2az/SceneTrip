import SceneApiClient
import SwiftUI

/// 「새 여행 코스」 시트 — 세 갈래를 한 묶음의 세 줄로 (MZ2AZ-547 F1·F2, 웹 `proto/ProtoFork.tsx`).
///
/// ① 도시로 시작(질문 흐름) ② 작품으로 [AI](작품 고르기 → AI) ③ 담은 곳으로(보관함을 날마다 나눠 넣기).
/// 줄마다 왼쪽에 무엇으로 시작하는지 보이는 사진 한 장(도시·포스터·담은 곳), 오른쪽 화살표, 줄 사이 얇은 선 — 앱 설정 화면 같은 묶음.
/// 여는 곳: 홈 떠 있는 줄 「+ 새 코스」(코스 0개면 「여행 코스 짜기」), 코스 목록 「코스 추가」.
struct NewCourseSheet: View {
    let onChoose: (NewCourseStart) -> Void

    @EnvironmentObject private var routes: RouteStore
    @ObservedObject private var cities = CityPlacesStore.shared
    @StateObject private var cart = CartStore()

    /// 시트 높이 — 머리 + 세 줄 묶음에 맞춘다(웹 368 은 바닥 안전영역 포함, iOS 시트는 따로 띄워 330 이면 남는 바닥이 없다 — 시뮬레이터 실측).
    static let height: CGFloat = 330

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Capsule().fill(Color(.systemGray3)).frame(width: 40, height: 5)
                .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 2) {
                Text("새 여행 코스")
                    .font(.system(size: 22, weight: .heavy))
                    .accessibilityAddTraits(.isHeader)
                Text("어떻게 시작할까요?")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 4)
            VStack(spacing: 0) {
                row(
                    first: true, photo: cityPhoto, symbol: "mappin.and.ellipse",
                    title: tr("도시로 시작"), caption: tr("어디로·며칠만 고르면 코스 완성")
                ) { onChoose(.city(nil)) }
                    .coachTarget("fork-city")
                row(
                    photo: poster.map { .remote($0) }, symbol: "sparkles",
                    title: tr("작품으로"), tag: "AI",
                    caption: tr("좋아하는 작품을 고르면 촬영지를 이어 줘요")
                ) { onChoose(.works) }
                    .coachTarget("fork-shows")
                row(
                    photo: savedPhoto.map { .remote($0) }, symbol: "bag",
                    title: tr("담은 곳으로"), caption: NewCourseText.savedCaption(count: savedCount)
                ) { onChoose(.saved) }
                    .coachTarget("fork-saved")
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(.systemGroupedBackground))
        .presentationDetents([.height(Self.height)])
        .presentationCornerRadius(30)
        .presentationDragIndicator(.hidden)
        // 처음 안내(MZ2AZ-547 C3) — 세 갈래를 하나씩 짚는다. 시트가 다 올라온 뒤에.
        .coachTour(.forkOptions, active: true)
        .task {
            async let citiesJob: Void = cities.loadCities()
            async let cartJob: Void = cart.refresh()
            async let worksJob: Void = routes.refreshMaterials()
            _ = await (citiesJob, cartJob, worksJob)
        }
    }

    private var cityPhoto: HomeCityRules.Photo? {
        cities.shownCities.lazy.compactMap { cities.photo($0.key) }.first
            ?? CityCovers.all.first.map { .asset($0.asset) }
    }

    private var poster: String? {
        routes.sortedWorks.first { $0.posterUrl.map(NewCoursePhoto.usable) == true }?.posterUrl
    }

    private var savedItems: [CartItem] {
        cart.items.filter { $0.delisted != true }
    }

    private var savedCount: Int {
        savedItems.count
    }

    private var savedPhoto: String? {
        savedItems.first { $0.imageUrl.map(NewCoursePhoto.usable) == true }?.imageUrl
    }

    private func row(
        first: Bool = false,
        photo: HomeCityRules.Photo?,
        symbol: String,
        title: String,
        tag: String? = nil,
        caption: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                art(photo, symbol: symbol)
                    .padding(.vertical, 12)
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(verbatim: title)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            if let tag {
                                Text(verbatim: tag)
                                    .font(.system(size: 11, weight: .heavy))
                                    .foregroundStyle(NewCourseStyle.purple)
                                    .padding(.horizontal, 6).padding(.vertical, 1)
                                    .background(RoundedRectangle(cornerRadius: 8).fill(NewCourseStyle.purple.opacity(0.12)))
                            }
                        }
                        Text(verbatim: caption)
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .padding(.vertical, 12).padding(.trailing, 12)
                .frame(maxHeight: .infinity)
                .overlay(alignment: .top) {
                    if !first {
                        Rectangle().fill(Color(.separator)).frame(height: 0.5)
                    }
                }
            }
            .padding(.leading, 12)
            .fixedSize(horizontal: false, vertical: true)
            .contentShape(.rect)
        }
        .buttonStyle(.press)
    }

    private func art(_ photo: HomeCityRules.Photo?, symbol: String) -> some View {
        ZStack {
            NewCourseStyle.brandFill
            switch photo {
            case let .asset(name):
                Image(name).resizable().scaledToFill()
            case let .remote(url):
                NewCoursePhoto(url: url)
            case nil:
                Image(systemName: symbol).font(.system(size: 20)).foregroundStyle(.white)
            }
        }
        .frame(width: 52, height: 52)
        .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
        .accessibilityHidden(true)
    }
}
