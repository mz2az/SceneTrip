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
    var onTap: () -> Void = {}

    /// 기본 자리(오른쪽 아래)에서 얼마나 옮겼나. 기기에 남는다.
    @AppStorage("scenetrip.guideChip.dx") private var storedX: Double = 0
    @AppStorage("scenetrip.guideChip.dy") private var storedY: Double = 0

    /// 끌고 있는 동안의 임시 이동. 손을 떼면 `stored*` 에 더해진다.
    @State private var dragging: CGSize = .zero
    /// 길게 눌러 「들린」 상태. 커지고 그림자가 짙어져 지금 끌 수 있다는 것을 보인다.
    @State private var lifted = false

    /// 기본 자리의 여백 — 「저장하고 닫기」 줄 위, 오른쪽 가장자리에서 12pt.
    private let edge: CGFloat = 12
    private let bottom: CGFloat = 76

    var body: some View {
        GeometryReader { proxy in
            if !hidden {
                chip(in: proxy.size)
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
    private func chip(in size: CGSize) -> some View {
        RouteGuideChipBody()
            .contentShape(.rect)
            .scaleEffect(lifted ? 1.08 : 1)
            .shadow(color: .black.opacity(lifted ? 0.3 : 0), radius: 10, y: 4)
            .offset(clamped(in: size))
            .onTapGesture { onTap() }
            // 꾹 누른 뒤에만 끌린다 — 그냥 끌면 지도·시트의 손짓과 싸운다.
            .gesture(moveGesture(in: size))
            .animation(.spring(duration: 0.25), value: lifted)
            .transition(.scale(scale: 0.4, anchor: .bottomTrailing).combined(with: .opacity))
    }

    private func moveGesture(in size: CGSize) -> some Gesture {
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
                if case let .second(true, drag?) = value {
                    let next = clamp(CGSize(
                        width: storedX + drag.translation.width,
                        height: storedY + drag.translation.height
                    ), in: size)
                    storedX = next.width
                    storedY = next.height
                }
                dragging = .zero
                lifted = false
            }
    }

    private func clamped(in size: CGSize) -> CGSize {
        clamp(CGSize(width: storedX + dragging.width, height: storedY + dragging.height), in: size)
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
    func guideFloatingChip(hidden: Bool, onTap: @escaping () -> Void) -> some View {
        overlay { RouteGuideFloatingChip(hidden: hidden, onTap: onTap) }
    }
}
