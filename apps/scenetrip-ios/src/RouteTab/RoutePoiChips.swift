import SwiftUI

/// 편의시설 **갈래별 켜고 끄기 칩** — 전체 · ●음식점 12 · ●숙소 2 ….
///
/// 편집 화면(일차 탭 아래)과 길찾기 화면(요약 줄 위)이 같은 것을 쓴다 — 두 화면의
/// 칩이 다르게 생기면 같은 기능인 줄 모른다. 「전체」는 마스터 스위치다: 다 켜져
/// 있으면 다 끄고, 하나라도 꺼져 있으면 다 켠다.
struct RoutePoiChips: View {
    /// 세는 대상. 갈래별 개수가 0이면 그 칩은 아예 안 나온다.
    let places: [RouteGuide.Place]

    @Binding var groupsOn: Set<RoutePoiGroup>

    /// 갈래를 껐다. 그 갈래에서 골라 둔 핀을 놓는 일은 **화면이 안다** — 편집
    /// 화면은 지도에서 사라진 핀을 놓고, 길찾기 화면은 챗봇 핀이면 놔둔다.
    var onGroupOff: (RoutePoiGroup) -> Void = { _ in }

    /// 편의시설 갈래 앞에 끼워 넣는 칩 — 길찾기 화면의 「성지」(코스 번호 핀)가
    /// 이것으로 들어온다. 「전체」 마스터 스위치의 소관 밖이다.
    struct Extra: Identifiable {
        let id: String
        let label: String
        let tone: Color
        let isOn: Bool
        let tap: () -> Void
    }

    var extras: [Extra] = []

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(extras) { extra in
                    chip(extra.label, tone: extra.tone, isOn: extra.isOn, tap: extra.tap)
                }
                let allOn = groupsOn.count == RoutePoiGroup.allCases.count
                chip("전체", tone: nil, isOn: allOn) {
                    if allOn {
                        groupsOn = []
                        RoutePoiGroup.allCases.forEach(onGroupOff)
                    } else {
                        groupsOn = Set(RoutePoiGroup.allCases)
                    }
                }
                ForEach(RoutePoiGroup.allCases) { group in
                    let count = places.count { $0.poiGroup == group }
                    if count > 0 {
                        chip("\(group.label) \(count)",
                             tone: RoutePoiTone.of(group),
                             isOn: groupsOn.contains(group))
                        {
                            if groupsOn.contains(group) {
                                groupsOn.remove(group)
                                onGroupOff(group)
                            } else {
                                groupsOn.insert(group)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func chip(
        _ label: String, tone: Color?, isOn: Bool, tap: @escaping () -> Void
    ) -> some View {
        Button(action: tap) {
            HStack(spacing: 5) {
                if let tone {
                    Circle().fill(tone).frame(width: 7, height: 7)
                }
                Text(label).font(.caption.weight(isOn ? .semibold : .regular))
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(
                Capsule().fill(isOn ? Color.accentColor.opacity(0.14) : Color(.systemGray6))
            )
            .overlay(
                Capsule().strokeBorder(
                    isOn ? Color.accentColor.opacity(0.5) : .clear, lineWidth: 1
                )
            )
            .foregroundStyle(isOn ? Color.primary : Color.secondary)
        }
        .buttonStyle(.plain)
    }
}
