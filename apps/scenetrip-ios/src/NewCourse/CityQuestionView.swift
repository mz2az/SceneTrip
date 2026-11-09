import SceneApiClient
import SwiftUI

/// 도시로 여행 짜기 — 도시 → 며칠 → 어떻게 → 추천 코스 | 직접 고르기 (MZ2AZ-547 Q1–Q3, 웹 `proto/ProtoScreens.tsx` `QuestionFlow`).
///
/// 단계 이동은 `CityQuestionFlow` 가 정한다. 홈 카드의 도시 칩으로 들어왔으면 「며칠?」 부터고 그때 뒤로는 닫기다.
struct CityQuestionView: View {
    let onClose: () -> Void

    @State private var flow: CityQuestionFlow
    @ObservedObject private var store = CityPlacesStore.shared

    init(startCity: String?, onClose: @escaping () -> Void) {
        var flow = CityQuestionFlow(startCity: startCity)
        Self.applyBackdoor(&flow)
        _flow = State(initialValue: flow)
        self.onClose = onClose
    }

    /// 확인용 뒷문 — `-cityFlow 부산 -cityDays 2 -cityHow plan|pick` 이면 추천 코스·직접 고르기까지 바로 간다(프로세스에 한 번).
    private static var backdoorUsed = false
    private static func applyBackdoor(_ flow: inout CityQuestionFlow) {
        let defaults = UserDefaults.standard
        guard !backdoorUsed, flow.startCity != nil, defaults.integer(forKey: "cityDays") > 0 else { return }
        backdoorUsed = true
        flow.pickDays(defaults.integer(forKey: "cityDays"))
        switch defaults.string(forKey: "cityHow") {
        case "plan": flow.planForMe()
        case "pick": flow.pickMyself()
        default: break
        }
    }

    var body: some View {
        Group {
            switch flow.step {
            case .city: cityStep
            case .days: daysStep
            case .how: howStep
            case .plan: planStep
            case .pick: pickStep
            }
        }
        .task { await store.loadCities() }
        // 고른 도시의 촬영지는 미리 받아 둔다 — 「며칠?」·「어떻게?」 에 답하는 동안이면 넉넉하다.
        .task(id: flow.city) {
            if let key = flow.city {
                await store.loadPlaces(key)
            }
        }
    }

    private func back() {
        if flow.back() {
            onClose()
        }
    }

    private var stepDots: (at: Int, of: Int)? {
        flow.stepNumber.map { (at: $0, of: 3) }
    }

    private var cityName: String {
        store.city(flow.city)?.name ?? flow.city ?? ""
    }

    // MARK: Q1 도시

    private var cityStep: some View {
        NewCourseScreen(step: stepDots, onBack: back) {
            CityHero()
        } content: {
            if store.status == .failed {
                VStack(alignment: .leading, spacing: 10) {
                    Text("도시 목록을 못 불러왔어요")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                    NewCourseSecondaryButton(title: tr("다시 시도")) {
                        Task { await store.retry() }
                    }
                }
            } else {
                CityGrid(
                    cities: store.shownCities,
                    loading: store.status == .idle || store.status == .loading,
                    photo: { store.photo($0) }
                ) { key in
                    withAnimation(.easeOut(duration: 0.2)) { flow.pickCity(key) }
                }
            }
            Button(action: onClose) {
                Text("아직 몰라요. 그냥 둘러볼게요")
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .frame(minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
        } footer: {
            EmptyView()
        }
    }

    // MARK: Q2 며칠

    private var daysStep: some View {
        NewCourseScreen(step: stepDots, onBack: back) {
            NewCourseTitle(text: String(format: tr("%@에서 며칠 머무세요?"), cityName))
            VStack(spacing: 10) {
                ForEach(CityQuestionFlow.dayOptions, id: \.self) { count in
                    choice(NewCourseText.days(count), on: flow.days == count) {
                        withAnimation(.easeOut(duration: 0.2)) { flow.pickDays(count) }
                    }
                }
            }
        }
    }

    private func choice(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: label)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(on ? .white : .primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 16).padding(.horizontal, 18)
                .background(
                    RoundedRectangle(cornerRadius: NewCourseStyle.radiusM)
                        .fill(on ? NewCourseStyle.purple : Color(.systemBackground))
                        .shadow(color: .black.opacity(0.08), radius: 5, y: 2)
                )
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    // MARK: Q3 어떻게

    private var howStep: some View {
        let days = flow.days ?? 1
        let count = store.city(flow.city)?.placeCount
        return NewCourseScreen(step: stepDots, onBack: back) {
            NewCourseTitle(text: tr("코스를 어떻게 짤까요?"))
            HowCard(
                primary: true,
                symbol: "sparkles",
                title: tr("알아서 짜 주세요"),
                detail: String(
                    format: tr("%@ 인기 촬영지를 동선대로 이어 %@ 코스를 바로 만들어요. 빼고 바꾸는 건 그다음에."),
                    cityName, NewCourseText.days(days)
                )
            ) { withAnimation(.easeOut(duration: 0.2)) { flow.planForMe() } }
            HowCard(
                primary: false,
                symbol: "checkmark.circle.fill",
                title: tr("직접 고를게요"),
                detail: String(
                    format: tr("%@ 촬영지 %@곳을 보고 가고 싶은 곳만 담아요."),
                    cityName, count.map(String.init) ?? ""
                )
            ) { withAnimation(.easeOut(duration: 0.2)) { flow.pickMyself() } }
        }
    }

    // MARK: R · S

    private var planStep: some View {
        CityPlanPreview(cityKey: flow.city ?? "", days: flow.days ?? 1, onBack: back)
    }

    private var pickStep: some View {
        CityPickView(cityKey: flow.city ?? "", days: flow.days ?? 1, onBack: back)
    }
}

/// 「어떻게 짤까요?」 의 두 카드 — 같은 크기, 「알아서」 가 위에 채운 색(웹 결정 3).
private struct HowCard: View {
    let primary: Bool
    let symbol: String
    let title: String
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(primary ? .white : NewCourseStyle.purple)
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: title)
                        .font(.system(size: 19, weight: .heavy))
                        .foregroundStyle(primary ? .white : .primary)
                    Text(verbatim: detail)
                        .font(.system(size: 15))
                        .foregroundStyle(primary ? .white.opacity(0.92) : .secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(primary ? .white : NewCourseStyle.purple)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: NewCourseStyle.radiusL)
                    .fill(primary ? NewCourseStyle.purple : Color(.systemBackground))
                    .shadow(color: .black.opacity(0.08), radius: 5, y: 2)
            )
            .overlay {
                if !primary {
                    RoundedRectangle(cornerRadius: NewCourseStyle.radiusL).strokeBorder(NewCourseStyle.purple, lineWidth: 1.5)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// 첫 질문의 사진 머리 — 홈 카드와 같은 고정 8장이 4초마다 겹쳐 바뀌고, 아래쪽은 화면 바탕색으로 녹아든다 (웹 `CityHero`).
struct CityHero: View {
    @State private var at = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let slides = CityCovers.all

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            CitySlideImages(slides: slides, at: at, zooms: !reduceMotion)
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.35), location: 0),
                    .init(color: .black.opacity(0), location: 0.28),
                    .init(color: .black.opacity(0.15), location: 0.52),
                    .init(color: .black.opacity(0.7), location: 0.82),
                    .init(color: Color(.systemGroupedBackground), location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image("haetae-face").resizable().scaledToFit().frame(width: 40, height: 40)
                        .accessibilityHidden(true)
                    Text("드라마·영화 촬영지로 짜는 한국 여행")
                        .font(.system(size: 14, weight: .semibold))
                        .shadow(color: .black.opacity(0.4), radius: 2, y: 1)
                }
                Text("한국 어디로 여행 가세요?")
                    .font(.system(size: 32, weight: .heavy))
                    .lineSpacing(2)
                    .shadow(color: .black.opacity(0.35), radius: 5, y: 2)
                    .accessibilityAddTraits(.isHeader)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 20).padding(.bottom, 34)
        }
        .overlay(alignment: .topTrailing) {
            if slides.indices.contains(at) {
                Text(verbatim: caption(slides[at]))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.9))
                    .lineLimit(1)
                    .shadow(color: .black.opacity(0.5), radius: 1.5, y: 1)
                    .padding(.trailing, 14)
                    .safeAreaPadding(.top)
                    .padding(.top, 52)
                    .animation(.easeInOut(duration: CitySlideRules.crossfade), value: at)
                    .accessibilityHidden(true)
            }
        }
        // 높이 min(화면 46%, 390), 최소 300(웹 `min(46dvh, 390px)`).
        .containerRelativeFrame(.vertical) { height, _ in max(300, min(height * 0.46, 390)) }
        .frame(maxWidth: .infinity)
        .background(NewCourseStyle.brandFill)
        .clipped()
        .task(id: reduceMotion) { await rotate() }
    }

    private func caption(_ cover: CityCover) -> String {
        let lang = AppLanguage.current
        let base = HomeCityRules.caption(cover, lang: lang)
        return "\(base) · \(lang == .ko ? cover.workKo : cover.workEn)"
    }

    private func rotate() async {
        guard CitySlideRules.advances(count: slides.count, reduceMotion: reduceMotion) else {
            at = 0
            return
        }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(CitySlideRules.interval))
            guard !Task.isCancelled else { return }
            at = CitySlideRules.next(after: at, count: slides.count)
        }
    }
}

/// 도시 칸 2열 — 사진, 이름 17, 「촬영지 N곳」. 받는 중이면 회색 칸 (웹 `CityGrid`).
struct CityGrid: View {
    let cities: [PlaceCity]
    let loading: Bool
    let photo: (String) -> HomeCityRules.Photo?
    let onPick: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            if loading, cities.isEmpty {
                ForEach(0 ..< CityPlacesStore.shownCities, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: NewCourseStyle.radiusM)
                        .fill(Color(.systemGray5).opacity(0.6))
                        .frame(height: 92)
                        .accessibilityHidden(true)
                }
            }
            ForEach(cities, id: \.key) { city in
                Button { onPick(city.key) } label: { cell(city) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: tr("%@, 촬영지 %lld곳"), city.name, city.placeCount))
            }
        }
    }

    /// 사진은 칸 크기에 맞춘 덮개로 깐다 — 채우기(scaledToFill) 사진을 칸의 자식으로 두면 칸이 사진만큼 커져 이름이 밖으로 밀린다(시뮬레이터 실측).
    private func cell(_ city: PlaceCity) -> some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 92)
            .background(NewCourseStyle.brandFill)
            .overlay {
                switch photo(city.key) {
                case let .asset(name): Image(name).resizable().scaledToFill()
                case let .remote(url): NewCoursePhoto(url: url)
                case nil: EmptyView()
                }
            }
            .overlay {
                LinearGradient(colors: [.black.opacity(0.05), .black.opacity(0.65)], startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: city.name).font(.system(size: 17, weight: .heavy))
                    Text(String(format: tr("촬영지 %lld곳"), city.placeCount))
                        .font(.system(size: 12)).opacity(0.9)
                }
                .foregroundStyle(.white)
                .padding(.leading, 12).padding(.bottom, 10)
            }
            .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM))
            .contentShape(.rect)
    }
}
