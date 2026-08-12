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
            pending: pendingPin
        ) { pin in
            // 한 번 찍으면 모드를 끈다. 켜 둔 채로 두면 시트를 닫는 손짓이 다음 핀이 된다.
            pinning = false
            pendingPin = pin
        }
        .frame(height: 210)
        .overlay(alignment: .top) {
            if pinning {
                Text("지도를 눌러 장소를 찍으세요")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .padding(.top, 10)
            }
        }
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

    var actions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                // 동선 최적화는 **지금 보고 있는 일차 안에서만** 순서를 바꾼다.
                // 일차를 넘나들며 옮기면 사용자가 나눠 둔 하루가 무너진다.
                action("동선 최적화", symbol: "arrow.triangle.swap") {
                    course.days[dayIndex].stops = RouteGeometry.optimized(stops)
                    fitToken += 1
                }
                action("장바구니", symbol: "bag") { showCart = true }
                action(pinning ? "핀 찍기 취소" : "핀 찍기", symbol: "mappin.and.ellipse") {
                    pinning.toggle()
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 10)
        }
        .background(Color(.systemBackground))
    }

    private func action(
        _ label: String,
        symbol: String,
        run: @escaping () -> Void
    ) -> some View {
        Button(action: run) {
            Label(label, systemImage: symbol)
                .font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Capsule().fill(Color(.systemGray6)))
        }
        .buttonStyle(.plain)
    }
}
