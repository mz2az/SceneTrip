import SwiftUI

/// 첫 실행에 한 번 보여 주는 사용법 넉 장.
///
/// ## 고른 언어로 한 벌만 나온다
///
/// 이 앱은 **외국인이 쓰는 앱**이다(계획서 §1). 사용법보다 먼저 언어를 묻고
/// (`LanguagePickView`), 여기서는 그 언어로만 적는다 (MZ2AZ-343). 원문은 한국어이고
/// 그릴 때 `tr()` 로 번역한다 — 영어 문장은 번역 표(`Localizable.strings`)에 있다.
///
/// ## 넉 장인 이유
///
/// 앱이 실제로 하는 일이 넷이라서다 — 검색 / AI 코스 / 길찾기 / 반경 POI·챗봇.
/// 다섯 장을 넘어가면 사람이 「건너뛰기」를 누른다. 그래서 기능 하나에 한 장씩,
/// 넘치는 것은 넣지 않는다.
struct OnboardingView: View {
    let onDone: () -> Void

    @State private var page = 0

    private static let lessons: [Lesson] = [
        Lesson(
            pose: .magnifier,
            title: "그 장면,\n어디서 찍었을까",
            body: "드라마·영화 제목으로도, 장면 설명으로도 찾아요.\n실제 촬영지가 바로 지도에 뜹니다."
        ),
        Lesson(
            pose: .sparkle,
            title: "속도만 정하세요.\n일정은 해태가 짭니다.",
            // 7 과 3 은 지어낸 수가 아니라 계약 `GuidePlanRequest.pace` 의 값이다(빡빡 7 · 여유 3,
            // 계수는 에이전트 설정). 그쪽을 고치면 이 문장도 함께 고쳐야 한다(MZ2AZ-321).
            body: "빡빡하게는 하루 7곳, 널널하게는 3곳.\n가까운 곳끼리 묶어 일차별로 나눠 줘요."
        ),
        Lesson(
            pose: .paw,
            title: "가고 싶어지면\n길찾기를 누르세요",
            body: "지금 서 있는 자리에서 지하철·버스,\n걷는 골목 하나까지 안내해요."
        ),
        Lesson(
            pose: .speech,
            title: "가는 길에 먹고,\n막히면 물어보세요",
            body: "주변의 음식점·명소·교통·숙소를 보여 줘요.\n한국어는 해태가 맡습니다."
        ),
    ]

    private var isLast: Bool {
        page == Self.lessons.count - 1
    }

    var body: some View {
        VStack(spacing: 0) {
            // 마지막 장에는 두지 않는다 — 거기 버튼이 이미 「시작하기」다.
            HStack {
                Spacer()
                Button("건너뛰기") { finish() }
                    .font(.system(size: 17))
                    .foregroundStyle(.secondary)
                    .opacity(isLast ? 0 : 1)
                    .disabled(isLast)
            }
            .frame(height: 44)
            .padding(.horizontal, 16)

            TabView(selection: $page) {
                ForEach(Array(Self.lessons.enumerated()), id: \.offset) { index, lesson in
                    LessonPage(lesson: lesson, index: index)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            PageDots(count: Self.lessons.count, current: page)
                .padding(.top, 8)
                .padding(.bottom, 22)

            Button {
                if isLast {
                    finish()
                } else {
                    withAnimation(.easeInOut(duration: 0.28)) { page += 1 }
                }
            } label: {
                Text(isLast ? tr("시작하기") : tr("다음"))
                    .font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.accentColor, in: .rect(cornerRadius: 12))
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
        }
        .background(Color(.systemBackground))
        .onAppear { AppAnalytics.log(.tutorialBegin) }
    }

    private func finish() {
        AppAnalytics.log(.tutorialComplete)
        OnboardingFlag.markSeen()
        onDone()
    }
}

/// `title`·`body` 는 한국어 원문이다 — 그릴 때 `tr()` 로 번역한다.
private struct Lesson {
    let pose: Pino.Pose
    let title: String
    let body: String
}

// MARK: - 한 장

private struct LessonPage: View {
    let lesson: Lesson
    let index: Int

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)

            ZStack {
                Backdrop(index: index)
                PinoMascot(pose: lesson.pose, width: mascotWidth)
                    .offset(mascotShift)
            }
            .frame(height: 300)

            Spacer(minLength: 0)

            Text(tr(lesson.title))
                .font(.system(size: 28, weight: .bold))
                .kerning(-0.5)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Text(tr(lesson.body))
                .font(.system(size: 16)).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).lineSpacing(3)
                .padding(.top, 12)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
    }

    /// 곁들인 그림이 한쪽에 몰린 장에서는 피노를 반대쪽으로 비켜 세운다.
    ///
    /// ③ 은 특히 작다. 경로선이 화면을 가로지르는 장이라 **경로가 주인공**이고,
    /// 처음에 다른 장과 같은 크기로 두었더니 앞발이 경로 위에 얹혀 「2호선 · 6개 역」을
    /// 통째로 가렸다(실측). 피노는 왼쪽 위로 물러나 경로가 시작되는 쪽을 가리킨다.
    private var mascotWidth: CGFloat {
        index == 2 ? 128 : 186
    }

    private var mascotShift: CGSize {
        switch index {
        case 1: .init(width: 62, height: 0)
        case 2: .init(width: -100, height: -74)
        default: .zero
        }
    }
}

// MARK: - 곁들인 그림

private struct Backdrop: View {
    let index: Int

    var body: some View {
        switch index {
        case 0: MapFragment()
        case 1: DayCards()
        case 2: LegTrace()
        default: RadiusChips()
        }
    }
}

/// ① 지도 조각과 다른 촬영지 핀들.
private struct MapFragment: View {
    private static let spots: [(CGFloat, CGFloat)] = [(-104, -78), (112, 98), (108, -108)]

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .fill(Color(.systemGroupedBackground))
                .frame(width: 268, height: 236)
                .overlay {
                    Path { path in
                        path.move(to: .init(x: 0, y: 92))
                        path.addLine(to: .init(x: 268, y: 92))
                        path.move(to: .init(x: 0, y: 176))
                        path.addLine(to: .init(x: 268, y: 176))
                        path.move(to: .init(x: 78, y: 0))
                        path.addLine(to: .init(x: 78, y: 236))
                        path.move(to: .init(x: 196, y: 0))
                        path.addLine(to: .init(x: 196, y: 236))
                    }
                    .stroke(Color(.systemGray4), lineWidth: 7)
                    .frame(width: 268, height: 236)
                    .clipShape(.rect(cornerRadius: 20))
                }

            // 아직 안 고른 촬영지들. 흐리게 두어 피노가 앞에 서 보이게 한다.
            //
            // 자리를 `CGPoint` 로 두고 `id: \.self` 를 쓰면 안 된다 — `CGPoint` 의
            // Hashable 은 **iOS 18 부터**이고 우리 하한은 17 이다. 차례를 열쇠로 쓴다.
            ForEach(Array(Self.spots.enumerated()), id: \.offset) { _, spot in
                MiniPin(tint: Color(.systemGray3))
                    .offset(x: spot.0, y: spot.1)
            }
        }
    }
}

/// ② 일차 카드 세 장.
private struct DayCards: View {
    private struct Day {
        let number: Int
        /// 한국어 원문 — 그릴 때 `tr()` 로 번역한다.
        let places: [String]
        /// 셋째 장은 흐리게 — 「더 있다」를 잘린 카드 없이 말한다.
        let fade: Double
    }

    private static let days: [Day] = [
        Day(number: 1, places: ["덕수궁 돌담길", "정동길 · 서울시청"], fade: 1),
        Day(number: 2, places: ["북촌한옥마을", "삼청동길 · 경복궁"], fade: 1),
        Day(number: 3, places: ["주문진 방파제"], fade: 0.55),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Self.days, id: \.number) { day in
                VStack(alignment: .leading, spacing: 3) {
                    Text(String(format: tr("%d일차"), day.number))
                        .font(.system(size: 11, weight: .heavy))
                        .foregroundStyle(Color(PinImage.deep))
                    ForEach(day.places, id: \.self) { place in
                        Text(tr(place)).font(.system(size: 12))
                    }
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .frame(width: 132, alignment: .leading)
                .background(Color(.systemBackground), in: .rect(cornerRadius: 12))
                .shadow(color: .black.opacity(0.10), radius: 5, y: 2)
                .opacity(day.fade)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 2)
    }
}

/// ③ 도보(점선) → 대중교통(실선) → 도보. 길찾기 결과 화면이 실제로 그리는 모양이다.
private struct LegTrace: View {
    var body: some View {
        ZStack {
            Path { path in
                path.move(to: .init(x: 42, y: 246))
                path.addCurve(to: .init(x: 90, y: 208),
                              control1: .init(x: 62, y: 232), control2: .init(x: 70, y: 216))
            }
            .stroke(Color.accentColor, style: .init(lineWidth: 5, lineCap: .round, dash: [1, 9]))

            Path { path in
                path.move(to: .init(x: 90, y: 208))
                path.addCurve(to: .init(x: 200, y: 92),
                              control1: .init(x: 136, y: 186), control2: .init(x: 162, y: 120))
            }
            .stroke(Color(PinImage.deep), style: .init(lineWidth: 6, lineCap: .round))

            Path { path in
                path.move(to: .init(x: 200, y: 92))
                path.addCurve(to: .init(x: 244, y: 54),
                              control1: .init(x: 220, y: 78), control2: .init(x: 230, y: 62))
            }
            .stroke(Color.accentColor, style: .init(lineWidth: 5, lineCap: .round, dash: [1, 9]))

            Circle()
                .fill(Color(.systemBackground))
                .overlay(Circle().stroke(Color.accentColor, lineWidth: 5))
                .frame(width: 18, height: 18)
                .position(x: 42, y: 246)
            Circle().fill(Color(PinImage.deep)).frame(width: 12, height: 12).position(x: 90, y: 208)
            Circle().fill(Color(PinImage.deep)).frame(width: 12, height: 12).position(x: 200, y: 92)
            MiniPin(tint: Color(PinImage.deep)).position(x: 244, y: 44)

            Group {
                caption("현재 위치", at: .init(x: 60, y: 268))
                caption("도보 4분", at: .init(x: 130, y: 220))
                caption("2호선 · 6개 역", at: .init(x: 196, y: 154))
                caption("도보 6분", at: .init(x: 246, y: 78))
            }
        }
        .frame(width: 300, height: 300)
    }

    private func caption(_ text: String, at point: CGPoint) -> some View {
        Text(tr(text))
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
            .position(point)
    }
}

/// ④ 반경과 갈래 넷. 색은 `RoutePoiTone.of` 을 그대로 부른다 — 두 벌로 적으면
/// 튜토리얼에서 본 색과 실제 화면 색이 갈린다.
private struct RadiusChips: View {
    private struct Chip {
        let group: RoutePoiGroup
        /// 한국어 원문 — 그릴 때 `tr()` 로 번역한다.
        let label: String
        let place: CGSize
    }

    /// 자리는 눈대중이 아니라 **피노를 피해** 잡은 값이다. 처음 배치에서는 말풍선이
    /// 명소를 덮고, 음식점이 왼쪽 귀를 자르고, 숙소가 꼬리에 얹혔다(실측).
    ///
    /// 피노가 차지하는 자리(폭 186 기준, 가운데 기준):
    /// 귀 위쪽 y −121, 몸통 x −80…81, 꼬리 x 28…81 · y −9…47, 말풍선 x 62…139 · y −115…−65.
    private static let chips: [Chip] = [
        Chip(group: .food, label: "음식점", place: .init(width: -116, height: -100)),
        Chip(group: .sight, label: "명소", place: .init(width: 122, height: 4)),
        Chip(group: .transit, label: "교통", place: .init(width: -112, height: 66)),
        Chip(group: .stay, label: "숙소", place: .init(width: 104, height: 92)),
    ]

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.accentColor.opacity(0.06))
                .overlay(Circle().stroke(
                    Color.accentColor.opacity(0.45),
                    style: .init(lineWidth: 1.5, dash: [6, 6])
                ))
                .frame(width: 262, height: 262)

            ForEach(Self.chips, id: \.label) { chip in
                HStack(spacing: 6) {
                    Circle().fill(RoutePoiTone.of(chip.group)).frame(width: 9, height: 9)
                    Text(tr(chip.label)).font(.system(size: 13, weight: .semibold))
                }
                .padding(.leading, 8)
                .padding(.trailing, 12)
                .padding(.vertical, 6)
                .background(Color(.systemBackground), in: .capsule)
                .shadow(color: .black.opacity(0.10), radius: 5, y: 2)
                .offset(chip.place)
            }
        }
    }
}

/// 곁들인 그림에 쓰는 작은 핀. 지도의 `PinImage` 는 `NMFOverlayImage` 라 SwiftUI 에
/// 바로 못 얹으므로 같은 물방울만 다시 그린다 — 삽화용이라 그림자·테두리는 뺀다.
private struct MiniPin: View {
    let tint: Color

    var body: some View {
        Path { path in
            path.addArc(
                center: .init(x: 13, y: 13),
                radius: 13,
                startAngle: .degrees(135),
                endAngle: .degrees(45),
                clockwise: false
            )
            path.addLine(to: .init(x: 13, y: 34))
            path.closeSubpath()
        }
        .fill(tint)
        .frame(width: 26, height: 34)
    }
}

// MARK: - 점

private struct PageDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0 ..< count, id: \.self) { index in
                Capsule()
                    .fill(index == current ? Color.accentColor : Color(.systemGray4))
                    .frame(width: index == current ? 20 : 7, height: 7)
                    .animation(.easeInOut(duration: 0.22), value: current)
            }
        }
    }
}

#Preview {
    OnboardingView(onDone: {})
}
