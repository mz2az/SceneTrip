import SwiftUI

/// 편집 화면의 **위쪽 절반** — 지도, 일차 탭(＋/−), 요약 줄, 동작 버튼.
///
/// `RouteEditorView.swift` 에서 떼어 냈다. 검색 탭이 `SearchTabOverlays.swift` 로
/// 나눈 것과 같은 이유이며, 나누는 선도 같은 자리에 그었다: 상태 선언과 화면 뼈대는
/// 그쪽에, **눈에 보이는 부품**은 이쪽에.
extension RouteEditorView {
    // MARK: 지도

    /// 지도는 화면 위쪽에 고정이다. 코스에서 "어디를 어떤 순서로 도는가" 는 목록보다
    /// 지도가 먼저 답한다 — 순서를 바꾸면 선이 바뀌는 것이 바로 보여야 한다.
    var map: some View {
        RouteMapView(
            stops: stops,
            fitToken: fitToken,
            pinning: pinning,
            pending: pendingPin,
            showingMe: showingMe,
            focused: focusedStop,
            previews: previewPlaces,
            guidePlaces: visibleGuidePlaces,
            pickedGuide: visiblePickedGuide,
            onTapGuide: { guide.picked = $0 },
            bottomInset: panelHeight
        ) { pin in
            // 한 번 찍으면 모드를 끈다. 켜 둔 채로 두면 시트를 닫는 손짓이 다음 핀이 된다.
            pinning = false
            pendingPin = pin
        }
        // 높이를 고정하지 않는다 — 이제 지도가 화면을 다 깔고 일정 시트가 그 위에
        // 얹힌다(`RouteEditorView` 참고).
        .overlay(alignment: .top) {
            if pinning {
                Text("지도를 눌러 장소를 찍으세요")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .padding(.top, 10)
            }
        }
        // 「내 위치」 **토글**. 오른쪽 위, 핀 찍는 동안에는 숨긴다 — 지도를 눌러야
        // 하는데 버튼이 손에 걸린다.
        //
        // 검색 탭은 「누르면 그 자리로 날아가는」 버튼인데 여기서는 토글이다. 코스
        // 화면에서 그냥 날아가면 **촬영지가 화면 밖으로 나가** 무엇을 보던 화면인지
        // 알 수 없다(2026-08-24 사용자 지적). 켜 두면 목록에서 장소를 고를 때마다
        // 「나와 그곳이 같이 보이는 크기」로 맞는다.
        //
        // 챗봇 단추는 여기 없다. 지도 위에 띄웠더니 오른쪽 위를 가렸다(2026-08-27
        // 사용자 지적) — 일정 시트의 동작 줄(`actions`)로 내렸다.
        .overlay(alignment: .topTrailing) {
            if !pinning {
                locateButton
                    .padding(10)
            }
        }
    }

    private var locateButton: some View {
        Button {
            showingMe.toggle()
        } label: {
            // 과녁 십자(dot.scope) — 검색 탭의 현위치 버튼과 같은 모양이다.
            // 켜짐은 모양이 아니라 배경색으로 구별한다.
            Image(systemName: "dot.scope")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(showingMe ? .white : Color.accentColor)
                .frame(width: 44, height: 44)
                .background(
                    Circle().fill(
                        showingMe
                            ? AnyShapeStyle(Color.accentColor)
                            : AnyShapeStyle(.ultraThinMaterial)
                    )
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: 편의시설 필터

    /// 챗봇이 찍어 준 핀의 **갈래별 켜고 끄기.** 핀이 하나라도 있어야 나온다 —
    /// 없는데 필터부터 보이면 무엇을 거르는 줄인지 알 수 없다.
    ///
    /// 「전체」는 마스터 스위치다. 다 켜져 있으면 끄고, 하나라도 꺼져 있으면 다 켠다.
    @ViewBuilder
    var poiFilter: some View {
        if !guide.places.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    let allOn = poiGroupsOn.count == RoutePoiGroup.allCases.count
                    chip("전체", tone: nil, isOn: allOn) {
                        poiGroupsOn = allOn ? [] : Set(RoutePoiGroup.allCases)
                        if poiGroupsOn.isEmpty {
                            guide.picked = nil
                        }
                    }
                    ForEach(RoutePoiGroup.allCases) { group in
                        let count = guide.places.count { $0.poiGroup == group }
                        if count > 0 {
                            chip("\(group.label) \(count)",
                                 tone: RoutePoiTone.of(group),
                                 isOn: poiGroupsOn.contains(group))
                            {
                                if poiGroupsOn.contains(group) {
                                    poiGroupsOn.remove(group)
                                    // 감춘 갈래의 고른 핀은 놓는다 — 지도에 없는
                                    // 것을 계속 골라 두면 카드만 남는다.
                                    if guide.picked?.poiGroup == group {
                                        guide.picked = nil
                                    }
                                } else {
                                    poiGroupsOn.insert(group)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.top, 8)
            .background(Color(.systemBackground))
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

    // MARK: 일차

    /// 일차 탭과 ＋/−. 8/11 회의 확정 — *"1일차 이렇게 플러스를 주는 거예요"*,
    /// *"빼기도 넣어야 되겠네"*.
    ///
    /// 일차는 **밑줄 탭**이고 요약은 상자 없는 띠다. 둘 다 회색 캡슐이면 읽기만 하는
    /// 요약이 누를 수 있는 것처럼 보인다(목업 설계 메모).
    var dayTabs: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(course.days.indices, id: \.self) { index in
                        dayTab(index)
                    }
                }
                .padding(.horizontal, 4)
            }
            Button {
                removeDay()
            } label: {
                Image(systemName: "minus.circle")
            }
            .disabled(course.days.count <= RouteCourse.dayLimit.lowerBound)
            Button {
                course.days.append(RouteDay())
                dayIndex = course.days.count - 1
            } label: {
                Image(systemName: "plus.circle")
            }
            .disabled(course.days.count >= RouteCourse.dayLimit.upperBound)
        }
        .padding(.horizontal, 16).padding(.top, 10)
        .background(Color(.systemBackground))
    }

    private func dayTab(_ index: Int) -> some View {
        let isOn = index == dayIndex
        return VStack(spacing: 6) {
            Text("\(index + 1)일차")
                .font(.subheadline.weight(isOn ? .semibold : .regular))
                .foregroundStyle(isOn ? Color.accentColor : .secondary)
            Rectangle()
                .fill(isOn ? Color.accentColor : .clear)
                .frame(height: 2)
        }
        .contentShape(.rect)
        .onTapGesture {
            dayIndex = index
            // 일차가 바뀌면 고른 장소를 놓는다 — 안 놓으면 다른 일차의 장소를
            // 계속 보고 있게 되고, 지도는 그 일차 전체로 맞춰야 한다.
            focusedStop = nil
            fitToken += 1
        }
    }

    /// 일차 빼기 — **뒤에서부터** 지우되 장소가 들어 있으면 멈추고 알린다.
    /// 담아 둔 장소가 소리 없이 사라지는 것보다 한 번 더 묻는 편이 낫다(목업 설계 메모).
    private func removeDay() {
        let last = course.days.count - 1
        guard course.days[last].stops.isEmpty else {
            blockedDay = last
            return
        }
        course.days.removeLast()
        dayIndex = min(dayIndex, course.days.count - 1)
    }

    // MARK: 요약과 동작

    /// 곳 수와 **직선거리**. 예상 소요 시간은 없다 — 8/11 회의 2부 확정.
    var summary: some View {
        HStack(spacing: 6) {
            Text("\(stops.count)곳")
            Text("·")
            Text("직선 \(RouteFormat.kilometers(RouteGeometry.totalKilometers(stops)))")
            Spacer()
            // 소요 시간이 왜 없는지 적어 둔다 — 그냥 비어 있으면 빠뜨린 것으로 읽힌다.
            Text("이동 시간은 여행 중에")
                .foregroundStyle(.tertiary)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Color(.systemBackground))
    }

    /// 네 가지가 **한 줄에 다 보인다.** 앞서 가로 스크롤이라 「핀 찍기」가 오른쪽
    /// 밖으로 밀려 있었는데, 밀려 있으면 없는 것과 같다 — 무엇을 할 수 있는지
    /// 화면을 밀어 봐야 아는 UI 는 직관적이지 않다(2026-08-25 사용자 지적).
    ///
    /// 넷이 들어가게 **글자를 짧게** 했다 — 「장소 검색」→「검색」. 출발·도착 고정은
    /// 여기서 뺐다. 그것은 **어느 줄을 고정하는가**의 이야기라 그 줄 옆에 있어야
    /// 뜻이 통한다(`RouteStopRow`).
    var actions: some View {
        HStack(spacing: 8) {
            // 동선 최적화는 **지금 보고 있는 일차 안에서만** 순서를 바꾼다.
            // 일차를 넘나들며 옮기면 사용자가 나눠 둔 하루가 무너진다.
            action("동선 최적화", symbol: "arrow.triangle.swap", highlight: optimizeNudge) {
                course.days[dayIndex].stops = RouteGeometry.optimized(
                    stops, pinStart: pinStart, pinEnd: pinEnd
                )
                fitToken += 1
                optimizeNudge = false // 권한 일을 했다 — 반짝임은 여기까지
            }
            // 장바구니를 거치지 않고 **여기서 바로** 찾아 담는다.
            action("검색", symbol: "magnifyingglass") { showSearch = true }
            action("장바구니", symbol: "bag") { showCart = true }
            action(pinning ? "취소" : "핀 찍기", symbol: "mappin.and.ellipse") {
                pinning.toggle()
            }
            // AI 가이드. 지도 위에 떠 있던 단추를 내렸다 — 시트 안이라 지도를
            // 가리지 않고, 자리도 다른 동작들과 같은 줄이라 찾아 헤매지 않는다.
            guideAction
        }
        .padding(.horizontal, 16).padding(.bottom, 10)
        .background(Color(.systemBackground))
    }

    /// 다른 동작과 같은 꼴이되 **피노 색 그라데이션**으로 눈에 띈다 — AI 가
    /// 하는 일임을 색으로 말한다(`RouteChatButton` 과 같은 색).
    private var guideAction: some View {
        Button {
            showGuide = true
        } label: {
            VStack(spacing: 3) {
                Image(systemName: "sparkles").font(.system(size: 15))
                Text("AI 가이드").font(.caption2).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10).fill(
                    LinearGradient(
                        colors: [Color(PinImage.light), Color(PinImage.deep)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            )
        }
        .buttonStyle(.plain)
    }

    /// 넷이 한 줄에 들어가야 하므로 **아이콘 위, 글자 아래**로 쌓는다. 나란히 두면
    /// 402pt 폭에 「동선 최적화」 하나가 절반을 먹는다.
    private func action(
        _ label: String,
        symbol: String,
        highlight: Bool = false,
        run: @escaping () -> Void
    ) -> some View {
        Button(action: run) {
            VStack(spacing: 3) {
                Image(systemName: symbol).font(.system(size: 15))
                Text(label).font(.caption2).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6)))
            .modifier(ActionNudge(on: highlight))
        }
        .buttonStyle(.plain)
    }
}

/// 눌러 달라고 **숨 쉬듯 반짝이는** 강조. 장소가 새로 담겨 순서가 낡았을 때
/// 동선 최적화 단추에 씌운다 — 한 번 누르면 벗겨져 평소 회색으로 돌아간다.
private struct ActionNudge: ViewModifier {
    let on: Bool
    @State private var glow = false

    func body(content: Content) -> some View {
        content
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.accentColor.opacity(on ? (glow ? 0.26 : 0.08) : 0))
                    .allowsHitTesting(false)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        Color.accentColor.opacity(on ? (glow ? 0.9 : 0.35) : 0),
                        lineWidth: 1.5
                    )
                    .allowsHitTesting(false)
            )
            .foregroundStyle(on ? Color.accentColor : Color.primary)
            .onChange(of: on, initial: true) { _, now in
                guard now else {
                    glow = false
                    return
                }
                glow = false
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                    glow = true
                }
            }
    }
}
