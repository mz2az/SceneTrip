import SceneApiClient
import SwiftUI

/// 홈 맨 위 「어디로 여행 가세요?」 사진 카드 (MZ2AZ-547 H1–H3, 웹 `proto/ProtoScreens.tsx` `AskCard`).
///
/// 앱에 실린 도시 사진 8장(`CityCovers`)이 4초마다 겹쳐 바뀌고, 위에는 질문, 아래에는 지금 사진의 「도시 · 촬영지」 와
/// 도시 알약 줄(`GET /places/cities`, 서버 순서). 알약을 누르면 그 도시로 새 코스를 짓기 시작한다(`onPick`).
/// 가운데는 비워 사진이 보이게 하고 위·아래만 글자가 읽힐 만큼 어둡게 한다(웹 2026-11-09 사용자 지적).
struct HomeCityCard: View {
    let cities: [PlaceCity]
    let onPick: (PlaceCity) -> Void

    @State private var at = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let slides = CityCovers.all

    var body: some View {
        ZStack(alignment: .topLeading) {
            CitySlideImages(slides: slides, at: at, zooms: !reduceMotion)
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.55), location: 0),
                    .init(color: .black.opacity(0.12), location: 0.34),
                    .init(color: .black.opacity(0), location: 0.52),
                    .init(color: .black.opacity(0.1), location: 0.66),
                    .init(color: .black.opacity(0.6), location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
            .allowsHitTesting(false)
            content
        }
        .frame(height: 250)
        .background(LinearGradient(
            colors: [Color(PinImage.light), Color(PinImage.deep)],
            startPoint: .topLeading, endPoint: .bottomTrailing
        ))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 5, y: 2)
        .padding(.horizontal, 20)
        // 움직임 줄이기면 첫 장에 멈춘다. 설정이 바뀌면 다시 건다.
        .task(id: reduceMotion) { await rotate() }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("어디로 여행 가세요?")
                    .font(.system(size: 24, weight: .heavy))
                    .shadow(color: .black.opacity(0.35), radius: 4, y: 2)
                    .accessibilityAddTraits(.isHeader)
                Text("도시를 고르면 그곳 드라마·영화 촬영지로 코스를 만들어 드려요")
                    .font(.system(size: 14))
                    .opacity(0.92)
                    .shadow(color: .black.opacity(0.4), radius: 2, y: 1)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 12)
            if slides.indices.contains(at) {
                Text(HomeCityRules.caption(slides[at], lang: AppLanguage.current))
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .opacity(0.9)
                    .shadow(color: .black.opacity(0.5), radius: 1.5, y: 1)
                    .contentTransition(.opacity)
                    // 사진과 같은 박자로 겹쳐 바뀐다 — 글만 먼저 바뀌면 잠깐 다음 도시 이름이 앞 사진 위에 뜬다.
                    .animation(.easeInOut(duration: CitySlideRules.crossfade), value: at)
                    .accessibilityHidden(true)
            }
            chips
        }
        .foregroundStyle(.white)
        .padding(.top, 16).padding(.horizontal, 18).padding(.bottom, 6)
    }

    /// 도시 알약 — 사진 위의 작은 반투명 알약(높이 32). 누르는 칸은 위아래로 늘려 44 를 지킨다(웹 2026-11-08).
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(HomeCityRules.chips(cities), id: \.key) { city in
                    Button { onPick(city) } label: { chip(city) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(format: tr("%@, 촬영지 %lld곳"), city.name, city.placeCount))
                }
            }
            .padding(.horizontal, 18)
        }
        .padding(.horizontal, -18)
        .frame(height: 44)
    }

    private func chip(_ city: PlaceCity) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(city.name).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
            Text("\(city.placeCount)").font(.system(size: 11)).foregroundStyle(.white.opacity(0.75))
        }
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(.ultraThinMaterial.opacity(0.6), in: Capsule())
        .background(Capsule().fill(.white.opacity(0.18)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.45), lineWidth: 1))
        .environment(\.colorScheme, .dark)
        .frame(height: 44)
        .contentShape(.rect)
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

/// 겹쳐 바뀌는 도시 사진들 — 지금 장만 보이고(1.2초에 걸쳐 겹침), 지금 장은 5초 동안 1.06배로 천천히 커진다.
/// 지금 장과 앞·뒤 장만 그려 둔다(`CitySlideRules.isMounted`). P3 의 질문 화면 사진 머리도 이것을 쓴다.
struct CitySlideImages: View {
    let slides: [CityCover]
    let at: Int
    /// 움직임 줄이기면 false — 커지지 않는다.
    var zooms = true

    var body: some View {
        ZStack {
            ForEach(Array(slides.enumerated()), id: \.element.key) { index, slide in
                if CitySlideRules.isMounted(index, current: at, count: slides.count) {
                    let shown = index == at
                    Color.clear
                        .overlay {
                            Image(slide.asset)
                                .resizable()
                                .scaledToFill()
                                .animation(.easeOut(duration: CitySlideRules.zoomDuration)) {
                                    $0.scaleEffect(shown && zooms ? CitySlideRules.zoomScale : 1)
                                }
                        }
                        .clipped()
                        .animation(.easeInOut(duration: CitySlideRules.crossfade)) {
                            $0.opacity(shown ? 1 : 0)
                        }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
