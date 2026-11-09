import SwiftUI

/// 지도 맨 위 편의시설 칩 줄 (MZ2AZ-547 M1·M3, 웹 `proto/protoPoi.tsx` `MapPoiBar`).
///
/// 🔍 · 전체 · 음식점 · 명소 · 숙소 · 교통. 켜진 칩은 갈래 색 바탕에 흰 글자와 개수, 꺼진 칩은 흰 바탕.
/// 그 아래 가운데에 상태 알약 하나 — 「찾는 중」 / 「↻ 이 지역에서 다시 찾기」 / 「조금 더 확대하면 보여요」.
/// 무엇을 언제 받는지는 `MapPoiChips`·`MapPoiFetch` 가 정하고 여기는 그 답을 그린다.
struct MapPoiBar: View {
    let chips: MapPoiChips
    let counts: [RoutePoiGroup: Int]
    let pill: MapPoiPill
    let onSearch: () -> Void
    let onToggle: (RoutePoiGroup) -> Void
    let onAll: () -> Void
    let onRefresh: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    searchButton
                    allChip
                    ForEach(RoutePoiGroup.allCases) { group in
                        groupChip(group)
                    }
                }
                .padding(.horizontal, 10)
            }
            statusPill
        }
        .padding(.top, 10)
    }

    /// 검색 — 칩 줄 맨 앞. 평소엔 동그라미 하나라 지도를 가리지 않는다.
    private var searchButton: some View {
        Button(action: onSearch) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color(.label))
                .frame(width: 34, height: 34)
                .background(Circle().fill(Color(.systemBackground)))
                .overlay(Circle().strokeBorder(Color(.systemGray5), lineWidth: 1))
                .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("장소 검색"))
    }

    /// 전체 — 갈래를 고르지 않고 찾기. 넷이 다 켜진 것과 같다.
    private var allChip: some View {
        Button(action: onAll) {
            chipFace(on: chips.allOn, fill: TabBar.homePurple) {
                Text("전체").font(.system(size: 13, weight: .semibold))
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(chips.allOn ? .isSelected : [])
    }

    private func groupChip(_ group: RoutePoiGroup) -> some View {
        let isOn = chips.isOn(group)
        let tone = RoutePoiTone.of(group)
        return Button { onToggle(group) } label: {
            chipFace(on: isOn, fill: tone) {
                HStack(spacing: 6) {
                    Circle().fill(isOn ? Color.white : tone).frame(width: 8, height: 8)
                    Text(group.label).font(.system(size: 13, weight: .semibold))
                    if isOn, let count = counts[group] {
                        Text("\(count)").font(.system(size: 12)).foregroundStyle(.white.opacity(0.85))
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    private func chipFace(on isOn: Bool, fill: Color, @ViewBuilder content: () -> some View) -> some View {
        content()
            .foregroundStyle(isOn ? Color.white : Color(.label))
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(Capsule().fill(isOn ? fill : Color(.systemBackground)))
            .overlay(Capsule().strokeBorder(isOn ? Color.clear : Color(.systemGray5), lineWidth: 1))
            .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
            .frame(minHeight: 44)
            .contentShape(.rect)
    }

    @ViewBuilder private var statusPill: some View {
        switch pill {
        case .none:
            EmptyView()
        case .searching:
            MapStatusPill {
                ProgressView().controlSize(.small)
                Text("찾는 중").font(.system(size: 13, weight: .semibold))
            }
        case .zoomIn:
            MapStatusPill {
                Image(systemName: "plus.magnifyingglass").font(.system(size: 14))
                Text("조금 더 확대하면 보여요").font(.system(size: 13, weight: .semibold))
            }
        case .refresh:
            MapRefreshPill(action: onRefresh)
        }
    }
}

/// 흰 알약 — 상태 한 줄.
struct MapStatusPill<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack(spacing: 6, content: content)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background(Capsule().fill(Color(.systemBackground)))
            .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
    }
}

/// 「↻ 이 지역에서 다시 찾기」 — 칩 줄 아래와 검색 결과 지도가 같이 쓴다.
struct MapRefreshPill: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MapStatusPill {
                Image(systemName: "arrow.clockwise").font(.system(size: 14, weight: .semibold))
                Text("이 지역에서 다시 찾기").font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(TabBar.homePurple)
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
