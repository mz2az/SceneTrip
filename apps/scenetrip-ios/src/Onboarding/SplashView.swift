import SwiftUI

/// 앱을 켤 때 한 번 — 해태가 앞발로 「SceneTrip」 글자 윗변을 잡고 그 사이로 머리를 빼꼼 내민다 (MZ2AZ-549).
///
/// 순서(약 1.5초): 글자 → 앞발 둘이 윗변에 걸침 → 머리가 글자 뒤에서 뿅 → 아래 문구. 시안
/// https://claude.ai/artifact/T3WBPosqMhpmjkpPPf3uJJ 와 같은 시간이다. 그림은 사용자가 준 구름 갈기 해태 얼굴과 발바닥.
///
/// 시작 화면은 MZ2AZ-526 에서 첫 실행 덮개와 함께 걷었다(광고 방문자가 덮개에서 빠져나갔다). 그래서 **켤 때(콜드 스타트)만**
/// 보이고, 누르면 바로 넘어가며, 동작 줄이기면 움직이지 않고 짧게 보인다. 바탕은 시스템 LaunchScreen 과 같은 흰색이라 이어진다.
struct SplashView: View {
    let onDone: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var word = false
    @State private var leftPaw = false
    @State private var rightPaw = false
    @State private var head = false
    @State private var tagline = false
    @State private var finished = false

    /// 머리가 올라오는 칸의 높이 — 아래 가장자리가 글자 윗변이고, 머리는 이 칸 아래에서 올라와 잘려 보인다.
    private static let stageHeight: CGFloat = 122
    private static let headWidth: CGFloat = 156
    private static let pawWidth: CGFloat = 44

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            VStack(spacing: 0) {
                Color.clear
                    .frame(width: 200, height: Self.stageHeight)
                    .overlay(alignment: .bottom) {
                        Image("splash-haetae-face")
                            .resizable()
                            .scaledToFit()
                            .frame(width: Self.headWidth)
                            .offset(y: head ? 10 : Self.stageHeight + 20)
                    }
                    .clipped()
                    .overlay(alignment: .bottom) {
                        HStack(spacing: 200 - Self.pawWidth * 2 - 88) {
                            paw("splash-paw-left", shown: leftPaw)
                            paw("splash-paw-right", shown: rightPaw)
                        }
                        .offset(y: -3)
                    }
                    .zIndex(1)
                Text(verbatim: "SceneTrip")
                    .font(.system(size: 44, weight: .black))
                    .tracking(-0.5)
                    .foregroundStyle(TabBar.homePurple)
                    .opacity(word ? 1 : 0)
                    // 글자 줄 위쪽 여백만큼 올려, 머리가 잘리는 선을 글자 윗변에 붙인다.
                    .padding(.top, -12)
                Text(verbatim: "Stand where the scene happened")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .padding(.top, 12)
                    .opacity(tagline ? 1 : 0)
            }
            .offset(y: -14)
        }
        .contentShape(.rect)
        .onTapGesture(perform: finish)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "SceneTrip"))
        .task { await play() }
    }

    private func paw(_ name: String, shown: Bool) -> some View {
        Image(name)
            .resizable()
            .scaledToFit()
            .frame(width: Self.pawWidth)
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 26)
            .scaleEffect(shown ? 1 : 0.9)
    }

    private func play() async {
        if reduceMotion {
            word = true
            leftPaw = true
            rightPaw = true
            head = true
            tagline = true
            try? await Task.sleep(for: .milliseconds(700))
            finish()
            return
        }
        withAnimation(.easeOut(duration: 0.35)) { word = true }
        await wait(0.35)
        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { leftPaw = true }
        await wait(0.07)
        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { rightPaw = true }
        await wait(0.30)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.62)) { head = true }
        await wait(0.38)
        withAnimation(.easeOut(duration: 0.3)) { tagline = true }
        await wait(0.55)
        finish()
    }

    private func wait(_ seconds: Double) async {
        try? await Task.sleep(for: .seconds(seconds))
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        onDone()
    }
}
