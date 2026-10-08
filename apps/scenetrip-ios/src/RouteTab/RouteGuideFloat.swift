import SwiftUI

/// 화면 오른쪽 아래에 늘 떠 있는 **해태 「내가 도와줄게!」** — 가이드 챗봇의 유일한 입구.
///
/// 지도 안 오른쪽 위에 두었을 때는 말풍선 폭 때문에 「내 위치」·「발자취」 동그라미가
/// 왼쪽으로 밀렸고, 동작 줄의 「AI 가이드」 단추와 하는 일이 같아 둘이 됐다
/// (2026-09-16 사용자 지적 — 직선거리 계획 지도와 길찾기 지도를 한 화면으로 합치며
/// 생긴 중복). 그래서 단추는 없애고 이 동그라미 하나를 **지도가 아니라 화면**에 띄운다.
///
/// **꾹 누르면 옮길 수 있다.** 옮긴 자리는 기기에 남아 다음에 열어도 그대로다 —
/// 카드·시트가 자주 겹치는 자리라 사용자가 스스로 비켜 둘 수 있어야 한다.
///
/// 그림은 `RouteGuideChipBody` 다. **`RouteGuideChip`(단추)을 쓰지 않는다** — 단추가
/// 길게 누르기를 먼저 먹어 끌기가 시작되지 않았다(2026-09-16 사용자 확인). 탭과 끌기를
/// 여기서 직접 다룬다.
struct RouteGuideFloatingChip: View {
    /// 열려 있는 가이드 창·핀 찍기 중에는 숨긴다. 창은 같은 구석에서 나오고, 핀 찍기는
    /// 지도를 눌러야 하는데 손에 걸린다.
    var hidden = false
    /// 화면 바닥에 뜬 정보 카드·성지 카드의 **윗변 높이**(바닥에서). 카드가 없으면 nil.
    ///
    /// 카드는 이 동그라미의 기본 자리 바로 위에 뜬다 — 말풍선이 「경로에 추가」 단추와 장면 설명 끝을
    /// 덮었다(MZ2AZ-367, 2026-10-08 실기). 카드가 떠 있는 동안은 **말풍선을 접고 카드 위로 비킨다.**
    var cardTop: CGFloat?
    /// 카드를 비켜 선 자리에서 **덮으면 안 되는 단추**의 틀(화면 좌표) — 일차 머리줄의 「머무는 시간 ›」.
    /// 비켜 선 자리가 마침 그 줄이다. 367 때는 글자뿐이라 덮어도 됐는데 이제 단추다(MZ2AZ-368 검증).
    var keepClear: CGRect?
    var onTap: () -> Void = {}

    /// 기본 자리(오른쪽 아래)에서 얼마나 옮겼나. 기기에 남는다.
    @AppStorage("scenetrip.guideChip.dx") private var storedX: Double = 0
    @AppStorage("scenetrip.guideChip.dy") private var storedY: Double = 0

    /// 끌고 있는 동안의 임시 이동. 손을 떼면 `stored*` 에 더해진다.
    @State private var dragging: CGSize = .zero
    /// 길게 눌러 「들린」 상태. 커지고 그림자가 짙어져 지금 끌 수 있다는 것을 보인다.
    @State private var lifted = false

    /// 기본 자리의 여백 — 편집 화면의 아래 단추 줄 위, 오른쪽 가장자리에서 12pt.
    private let edge: CGFloat = 12
    private let bottom: CGFloat = 76

    var body: some View {
        GeometryReader { proxy in
            if !hidden {
                chip(in: proxy.size, clear: Self.clearance(of: keepClear, in: proxy.frame(in: .global)))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.trailing, edge)
                    .padding(.bottom, bottom)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: hidden)
        .allowsHitTesting(!hidden)
    }

    /// 손짓은 **그림에 직접** 붙인다. 바깥의 가득 채운 `frame` 에 붙이면 빈 화면까지
    /// 손짓을 먹어 지도와 싸운다.
    private func chip(in size: CGSize, clear: Clearance?) -> some View {
        RouteGuideChipBody(bubble: cardTop == nil)
            .contentShape(.rect)
            .scaleEffect(lifted ? 1.08 : 1)
            .shadow(color: .black.opacity(lifted ? 0.3 : 0), radius: 10, y: 4)
            .offset(clamp(spot(dragging: dragging, clear: clear), in: size))
            .onTapGesture { onTap() }
            // 꾹 누른 뒤에만 끌린다 — 그냥 끌면 지도·시트의 손짓과 싸운다.
            .gesture(moveGesture(in: size, clear: clear))
            .animation(.spring(duration: 0.25), value: lifted)
            .animation(.spring(duration: 0.3), value: cardTop)
            .animation(.spring(duration: 0.3), value: clear)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(tr("내가 도와줄게!"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { onTap() }
            .transition(.scale(scale: 0.4, anchor: .bottomTrailing).combined(with: .opacity))
    }

    private func moveGesture(in size: CGSize, clear: Clearance?) -> some Gesture {
        LongPressGesture(minimumDuration: 0.3)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .onChanged { value in
                switch value {
                case .first(true):
                    // 길게 누르기가 성사됐다 — 아직 끌지 않았어도 들어 올린 표시를 낸다.
                    lifted = true
                case let .second(true, drag):
                    lifted = true
                    dragging = drag?.translation ?? .zero
                default:
                    break
                }
            }
            .onEnded { value in
                // 꾹 눌렀다 그대로 떼면 저장하지 않는다 — 카드를 비켜 서 있던 자리가 제자리로 굳는다.
                if case let .second(true, drag?) = value, Self.moved(drag.translation) {
                    // **보이던 그 자리**를 남긴다 — 카드를 비켜 서 있던 채로 끌었으면 거기서부터 센다.
                    let next = clamp(spot(dragging: drag.translation, clear: clear), in: size)
                    storedX = next.width
                    storedY = next.height
                }
                dragging = .zero
                lifted = false
            }
    }

    /// 지금 서 있을 자리(기본 자리에서 잰 이동) — 저장된 자리 + 카드·단추를 비킨 만큼 + 끄는 만큼.
    private func spot(dragging: CGSize, clear: Clearance?) -> CGSize {
        CGSize(
            width: Self.offsetX(
                stored: CGSize(width: storedX, height: storedY), dragging: dragging.width,
                cardTop: cardTop, clear: clear, bottom: bottom, edge: edge
            ),
            height: Self.offsetY(stored: storedY, dragging: dragging.height, cardTop: cardTop, bottom: bottom)
        )
    }

    /// 덮으면 안 되는 단추의 자리 — 이 동그라미와 같은 잣대(오른쪽 가장자리·바닥에서 잰 거리)로.
    struct Clearance: Equatable {
        /// 단추 왼쪽 변이 오른쪽 가장자리에서 얼마나 떨어져 있나.
        var left: CGFloat
        /// 단추 아랫변·윗변의 높이(바닥에서).
        var low: CGFloat
        var high: CGFloat
    }

    static func clearance(of button: CGRect?, in screen: CGRect) -> Clearance? {
        guard let button, !button.isEmpty else { return nil }
        return Clearance(
            left: screen.maxX - button.minX, low: screen.maxY - button.maxY, high: screen.maxY - button.minY
        )
    }

    /// 카드를 비켜 올라선 자리가 단추와 겹치면 **그 단추 왼쪽으로** `gap` 만큼 더 비킨다.
    ///
    /// 위로 더 올리지 않는다 — 그 위는 일차 ＋/− 단추다. 왼쪽은 「2곳 · 직선 25.5 km」 글자뿐이다.
    /// `offsetY` 와 같은 규칙을 따른다: 카드가 없으면 저장된 자리, **사용자가 스스로 카드보다 위에 둔
    /// 자리는 건드리지 않고**(거기가 단추 위여도 그 사람이 둔 자리다), 끄는 만큼은 비킨 자리에 더한다.
    static func offsetX(
        stored: CGSize, dragging: CGFloat = 0, cardTop: CGFloat?, clear: Clearance?,
        bottom: CGFloat, edge: CGFloat, side: CGFloat = 46, gap: CGFloat = 8
    ) -> CGFloat {
        let kept = stored.width + dragging
        guard let cardTop, let clear else { return kept }
        // 우리가 올린 것이 아니면(이미 더 위) 손대지 않는다.
        guard stored.height > -(cardTop + gap - bottom) else { return kept }
        // 올라선 동그라미(접혀서 `side` 정사각)의 아랫변 높이는 카드 윗변 + gap 이다.
        let foot = cardTop + gap
        let overlapsY = foot < clear.high && foot + side > clear.low
        // 단추는 오른쪽 끝까지 가므로, 동그라미 오른쪽 변이 단추 왼쪽 변보다 오른쪽이면 겹친다.
        let overlapsX = edge - stored.width < clear.left
        guard overlapsY, overlapsX else { return kept }
        return -(clear.left + gap - edge) + dragging
    }

    /// 카드가 떠 있으면 그 윗변보다 `gap` 만큼 위에 선다. **사용자가 이미 더 위로 옮겨 뒀으면 그대로** —
    /// 비켜 둔 자리를 우리가 다시 옮기지 않는다. 그냥 카드가 닫히면 제자리로 돌아온다(저장된 자리는 안 바꾼다).
    ///
    /// **끄는 만큼은 비킨 자리에 더한다.** 저장된 자리에 더한 뒤 비키면 끌어도 손가락을 안 따라오고,
    /// 놓은 자리와 저장된 자리가 어긋나 카드를 닫을 때 엉뚱한 데 나타났다(MZ2AZ-367 검증).
    /// 손을 뗄 때 저장하는 값도 이것이다 — 보이던 그 자리.
    static func offsetY(
        stored: CGFloat, dragging: CGFloat = 0, cardTop: CGFloat?, bottom: CGFloat, gap: CGFloat = 8
    ) -> CGFloat {
        guard let cardTop else { return stored + dragging }
        return min(stored, -(cardTop + gap - bottom)) + dragging
    }

    /// 옮긴 것으로 칠 만큼 끌었나. 꾹 누른 손가락은 가만히 있어도 몇 pt 는 흔들린다.
    static func moved(_ translation: CGSize, slop: CGFloat = 6) -> Bool {
        hypot(translation.width, translation.height) >= slop
    }

    /// 화면 밖으로 못 나간다. 기본 자리가 오른쪽 아래이므로 왼쪽·위로만 갈 수 있고,
    /// 오른쪽·아래로는 0 까지다. 동그라미와 말풍선 폭만큼은 남겨 둔다.
    private func clamp(_ offset: CGSize, in size: CGSize) -> CGSize {
        let minX = -(size.width - edge - 180) // 말풍선까지 포함한 폭
        let minY = -(size.height - bottom - 60)
        return CGSize(
            width: min(0, max(minX, offset.width)),
            height: min(0, max(minY, offset.height))
        )
    }
}

extension View {
    /// 편집 화면 위에 해태 동그라미를 띄운다. `RouteEditorView` 본문 길이(swiftlint 350줄)
    /// 때문에 한 줄로 부른다.
    func guideFloatingChip(
        hidden: Bool, cardTop: CGFloat? = nil, keepClear: CGRect? = nil, onTap: @escaping () -> Void
    ) -> some View {
        overlay { RouteGuideFloatingChip(hidden: hidden, cardTop: cardTop, keepClear: keepClear, onTap: onTap) }
    }
}
