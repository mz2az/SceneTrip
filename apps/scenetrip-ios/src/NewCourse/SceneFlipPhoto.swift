import SceneApiClient
import SwiftUI

// 추천 코스 카드의 사진 칸 — 누르면 세로 축으로 돌아 뒷면에 그 작품의 장면 그림 (MZ2AZ-547 §6, 계획 motion-works-drawer-parity.md).
// 고르기·숫자는 `SceneFlipRules`, 여기는 장소 상세를 받아 기억하는 것과 그리기.

/// 카드 뒷면의 장면 그림 — 장소마다 `GET /places/{placeId}` 를 한 번 받아 기억한다. 동시에 4개까지.
@MainActor
final class SceneFlipStore: ObservableObject {
    static let shared = SceneFlipStore()

    /// 장소 번호 → 뒷면 그림. 받았는데 그림이 없으면 값이 nil, 아직 안 받았으면 키가 없다.
    @Published private(set) var images: [Int64: String?] = [:]
    private var asked: Set<Int64> = []
    private var running = 0
    private var waiting: [PlaceSummary] = []

    /// 사용자가 한 번이라도 뒤집었나 — 그 뒤로는 처음 한 번 살짝 돌기를 하지 않는다(앱에 기억).
    var hasFlipped: Bool {
        get { UserDefaults.standard.bool(forKey: Self.flippedKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.flippedKey) }
    }

    private static let flippedKey = "sceneFlipSeen"

    /// 카드가 보였다 — 처음이면 받을 차례에 넣는다.
    func load(_ place: PlaceSummary) {
        guard asked.insert(place.id).inserted else { return }
        waiting.append(place)
        pump()
    }

    /// 뒤집을 수 있나(그림을 받아 두었나).
    func flippable(_ id: Int64) -> Bool {
        images[id].flatMap { $0 } != nil
    }

    private func pump() {
        while running < SceneFlipRules.maxConcurrentDetailFetches, !waiting.isEmpty {
            let place = waiting.removeFirst()
            running += 1
            Task {
                let detail = try? await PlacesAPI.getPlace(placeId: place.id)
                images[place.id] = detail.flatMap {
                    SceneFlipRules.sceneImageUrl(detail: $0, workId: place.contents?.first?.contentId)
                }
                running -= 1
                pump()
            }
        }
    }
}

/// 사진 칸 — 앞면은 장소 사진, 뒷면은 장면 그림. 그림이 없으면 뒤집히지 않고 알약도 없다.
struct SceneFlipPhoto: View {
    let place: PlaceSummary
    /// 이 화면이 처음 뜰 때 한 번 살짝 돌 카드인가(`SceneFlipRules.peekIndex`).
    let peek: Bool

    @ObservedObject private var store = SceneFlipStore.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flipped = false
    @State private var peekAngle: Double = 0
    @State private var peeked = false

    private var sceneUrl: String? {
        store.images[place.id].flatMap { $0 }
    }

    private var work: String? {
        place.contents?.first?.title.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private var angle: Double {
        (flipped ? 180 : 0) + peekAngle
    }

    /// 90° 를 넘으면 뒷면이 보인다.
    private var showingBack: Bool {
        reduceMotion ? flipped : abs(angle.truncatingRemainder(dividingBy: 360)) > 90
    }

    var body: some View {
        ZStack {
            NewCoursePhoto(url: place.imageUrl)
                .opacity(showingBack ? 0 : 1)
            if let sceneUrl {
                NewCoursePhoto(url: sceneUrl)
                    // 뒷면은 미리 뒤집어 둔다 — 돌고 나면 바로 보인다.
                    .rotation3DEffect(.degrees(reduceMotion ? 0 : 180), axis: (x: 0, y: 1, z: 0))
                    .opacity(showingBack ? 1 : 0)
            }
        }
        .overlay(alignment: .bottomLeading) { pill }
        .rotation3DEffect(.degrees(reduceMotion ? 0 : angle), axis: (x: 0, y: 1, z: 0), perspective: 0.5)
        .contentShape(.rect)
        .onTapGesture(perform: toggle)
        .accessibilityElement()
        .accessibilityLabel(Text(showingBack ? tr("장소 사진 보기") : tr("장면 그림 보기")))
        .accessibilityAddTraits(sceneUrl == nil ? [] : .isButton)
        .accessibilityAction { toggle() }
        .onAppear { store.load(place) }
        .task(id: peek) { await peekOnce() }
    }

    /// 뒤집히는 카드에만 — 앞면 「장면 보기」, 뒷면 「「작품」 속 장면」.
    @ViewBuilder private var pill: some View {
        if sceneUrl != nil {
            HStack(spacing: 5) {
                Image(systemName: showingBack ? "photo" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 11, weight: .bold))
                Text(showingBack ? backLabel : tr("장면 보기"))
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 9).padding(.vertical, 5)
            .background(Capsule().fill(.black.opacity(0.55)))
            .padding(10)
            // 알약 글은 돌아간 면에 맞춰 바로 읽히게 — 뒷면일 때 거꾸로 보이지 않게 다시 뒤집는다.
            .rotation3DEffect(.degrees(showingBack && !reduceMotion ? 180 : 0), axis: (x: 0, y: 1, z: 0))
            .accessibilityHidden(true)
        }
    }

    private var backLabel: String {
        work.map { String(format: tr("「%@」 속 장면"), $0) } ?? tr("장면 그림")
    }

    private func toggle() {
        guard sceneUrl != nil else { return }
        store.hasFlipped = true
        if reduceMotion {
            withAnimation(.easeInOut(duration: SceneFlipRules.reducedMotionFade)) { flipped.toggle() }
        } else {
            withAnimation(.easeInOut(duration: SceneFlipRules.flipDuration)) { flipped.toggle() }
        }
    }

    /// 처음 한 번 살짝(25°) 돌았다 돌아온다 — 뒤집힌다는 것을 알려 준다.
    private func peekOnce() async {
        guard peek, !peeked, !reduceMotion else { return }
        peeked = true
        try? await Task.sleep(for: .seconds(SceneFlipRules.peekDelay))
        guard !Task.isCancelled, !flipped else { return }
        withAnimation(.easeOut(duration: 0.3)) { peekAngle = SceneFlipRules.peekAngle }
        try? await Task.sleep(for: .milliseconds(320))
        withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { peekAngle = 0 }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
