import SwiftUI

/// 앱을 열면 처음 보이는 화면.
///
/// ## 빈 시간을 채우는 것이지 만드는 것이 아니다
///
/// `SceneTripApp.init` 이 지도 SDK 인증과 서버 주소를 세우고, 첫 화면이 인기 촬영지를
/// 부른다. 그동안 지금은 **흰 화면**이 보인다. 스플래시는 그 자리에 그림을 얹는 것이지
/// 없던 기다림을 새로 만드는 것이 아니다.
///
/// ## 왜 1.9초인가
///
/// 핀이 떨어져 튕기는 데 1.08초, 워드마크가 다 올라오는 데 1.22초가 든다. 1.9초면
/// 그 뒤로 0.7초쯤 머물러 문구를 읽을 새가 있다. 더 짧으면 애니메이션이 잘리고, 더
/// 길면 **여는 것을 가로막는 화면**이 된다 — 애플도 런치 스크린을 「빨리 사라져야
/// 하는 것」으로 못 박아 두었다.
struct SplashView: View {
    /// 다 보여 준 뒤 부른다.
    let onDone: () -> Void

    /// 착지했는가. 바닥 그림자가 이때 퍼진다.
    @State private var landed = false
    /// 글자를 올릴 때가 됐는가.
    @State private var lettering = false

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(PinImage.light),
                    Color(PinImage.deep),
                    Color(red: 0.357, green: 0.294, blue: 0.769), // #5B4BC4
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            RoadTracery().ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                ZStack(alignment: .bottom) {
                    // 착지 그림자. 핀과 따로 움직여야 「바닥에 꽂혔다」로 보인다.
                    Ellipse()
                        .fill(Color(red: 0.16, green: 0.13, blue: 0.38))
                        .frame(width: 108, height: 22)
                        .scaleEffect(x: landed ? 1 : 0.2)
                        .opacity(landed ? 0.16 : 0)
                        // 프레임 바닥이 아니라 **핀 끝** 밑에 깔아야 한다. 그림 안에서
                        // 핀 끝은 프레임 바닥보다 위에 있다.
                        .offset(y: 11 - PinoMascot.tipInset(width: 216))

                    PinoMascot(pose: .plain, tone: .onDeep, width: 216)
                        .background(alignment: .top) {
                            // 뒤에서 도는 빛무리. 어두운 바탕에서 실루엣을 띄운다.
                            Circle()
                                .fill(.white.opacity(0.28))
                                .frame(width: 190, height: 190)
                                .blur(radius: 26)
                                .offset(y: 4)
                        }
                        .modifier(PinDrop())
                }

                Spacer().frame(height: 30)

                // **글자만** 늦게 올라온다. 여기에 마스코트까지 넣었다가 낙하가
                // 시작하기도 전에 통째로 투명해져서 아무도 낙하를 못 봤다(실측).
                VStack(spacing: 12) {
                    Text("SceneTrip")
                        .font(.system(size: 44, weight: .heavy))
                        .kerning(-1.2)
                        .foregroundStyle(.white)

                    Text("Stand where the scene happened")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.white.opacity(0.82))
                }
                .opacity(lettering ? 1 : 0)
                .offset(y: lettering ? 0 : 14)

                Spacer()
            }
        }
        .task {
            // 낙하가 1.08초, 글자가 1.22초에 끝난다. 1.9초면 다 보여 주고도
            // 0.7초쯤 머문다 — 그보다 짧으면 문구를 읽을 새가 없다.
            withAnimation(.easeOut(duration: 0.42).delay(0.52)) { landed = true }
            withAnimation(.easeOut(duration: 0.5).delay(0.72)) { lettering = true }
            try? await Task.sleep(for: .milliseconds(1900))
            onDone()
        }
    }
}

// MARK: - 낙하

/// 핀이 지도에 꽂히듯 위에서 떨어져 한 번 눌렸다 편다.
///
/// ## `keyframeAnimator` 로 짰다가 걷어냈다
///
/// 키프레임으로 쓰면 짧고 읽기 좋았는데 **애니메이션이 끝나자 마스코트가 사라졌다**
/// (실측). `repeating: false` 는 다 돌고 나면 값을 초깃값으로 되돌리고, 그 초깃값이
/// 「화면 위 320pt 밖, 투명」이었다. 스플래시에 그림자만 남고 고양이가 없었다.
///
/// 그래서 상태를 직접 든다. 끝나면 그 자리에 **머문다**.
///
/// 튕김은 스프링이 알아서 낸다. 눌림만 따로 두는데, 그것은 세로가 줄고 가로가 느는
/// 별개의 변형이라 위치와 같은 곡선을 탈 수 없기 때문이다.
private struct PinDrop: ViewModifier {
    @State private var fall: CGFloat = -340
    @State private var shown = false
    /// 0 이 평소, 1 이 가장 눌린 상태.
    @State private var squash: CGFloat = 0

    func body(content: Content) -> some View {
        content
            // 바닥을 기준으로 눌러야 한다. 가운데를 기준으로 하면 꼬리 끝이 땅을 파고든다.
            .scaleEffect(x: 1 + squash * 0.12, y: 1 - squash * 0.12, anchor: .bottom)
            .offset(y: fall)
            .opacity(shown ? 1 : 0)
            .task {
                withAnimation(.easeOut(duration: 0.18)) { shown = true }
                withAnimation(.spring(response: 0.52, dampingFraction: 0.52)) { fall = 0 }

                // 착지 순간에 맞춘다. 스프링이 처음 바닥에 닿는 때다.
                try? await Task.sleep(for: .milliseconds(430))
                withAnimation(.easeOut(duration: 0.09)) { squash = 1 }
                try? await Task.sleep(for: .milliseconds(95))
                withAnimation(.spring(response: 0.34, dampingFraction: 0.45)) { squash = 0 }
            }
    }
}

// MARK: - 바탕

/// 바탕에 아주 흐리게 깔리는 길과 교차점. **지도라고 말하지 않고 지도로 보이게** 한다.
///
/// 진짜 지도 타일을 깔지 않는 이유는 두 가지다 — 인증이 끝나기 전이라 아직 그릴 수
/// 없고, 그릴 수 있더라도 첫 화면이 네트워크를 기다리게 된다.
private struct RoadTracery: View {
    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height = geometry.size.height

            ZStack {
                Path { path in
                    path.move(to: .init(x: -20, y: height * 0.24))
                    path.addCurve(
                        to: .init(x: width + 20, y: height * 0.32),
                        control1: .init(x: width * 0.36, y: height * 0.28),
                        control2: .init(x: width * 0.62, y: height * 0.27)
                    )
                    path.move(to: .init(x: -20, y: height * 0.73))
                    path.addCurve(
                        to: .init(x: width + 20, y: height * 0.77),
                        control1: .init(x: width * 0.30, y: height * 0.70),
                        control2: .init(x: width * 0.68, y: height * 0.80)
                    )
                    path.move(to: .init(x: width * 0.18, y: -20))
                    path.addCurve(
                        to: .init(x: width * 0.27, y: height + 20),
                        control1: .init(x: width * 0.06, y: height * 0.36),
                        control2: .init(x: width * 0.34, y: height * 0.64)
                    )
                    path.move(to: .init(x: width * 0.76, y: -20))
                    path.addCurve(
                        to: .init(x: width * 0.84, y: height + 20),
                        control1: .init(x: width * 0.64, y: height * 0.34),
                        control2: .init(x: width * 0.92, y: height * 0.66)
                    )
                }
                .stroke(.white.opacity(0.13), lineWidth: 1)
            }
        }
    }
}

#Preview {
    SplashView(onDone: {})
}
