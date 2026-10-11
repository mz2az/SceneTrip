import SwiftUI

/// 안내와 일정은 같은 목록 안에서 끝까지 넘겨 본다(MZ2AZ-388).
extension RouteEditorView {
    var stopList: some View {
        ScrollViewReader { proxy in
            stopRows
                // **다음 갈 곳이 맨 위에** — 안내가 켜지면 그 목적지, 도착하면 그다음 미방문 곳으로
                // 목록을 밀어 올린다(2026-09-04 사용자 요청). 다녀온 줄은 위로 흘러가 남는다.
                .onChange(of: trip.target?.id) { _, id in
                    if let id {
                        withAnimation {
                            if trip.phase == .guiding {
                                proxy.scrollTo("course-panel-header", anchor: .top)
                            } else {
                                proxy.scrollTo(id, anchor: .top)
                            }
                        }
                    }
                }
                .onChange(of: trip.phase) { _, phase in
                    if phase == .arrived, let next = nextUnvisited?.stop.id {
                        withAnimation { proxy.scrollTo(next, anchor: .top) }
                    }
                }
        }
    }

    private var stopRows: some View {
        List {
            VStack(spacing: 0) {
                tripBanner
                dayTabs
                poiFilter
                summary
                actions
            }
            .id("course-panel-header")
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                RouteStopRow(
                    stop: stop,
                    number: index + 1,
                    // 다음 장소까지의 직선거리. 마지막 장소 뒤에는 갈 곳이 없다.
                    nextKilometers: index + 1 < stops.count
                        ? RouteGeometry.kilometers(stop.place, stops[index + 1].place)
                        : nil,
                    running: course.isRunning,
                    isFocused: focusedStop?.id == stop.id,
                    works: workTitles(for: stop),
                    // 첫 줄에 「출발 고정」, 마지막 줄에 「도착 고정」. 한 곳뿐이면
                    // 고정할 것이 없다 — 그 하나가 출발이자 도착이라 뜻이 없다.
                    pinKind: stops.count > 1
                        ? (index == 0 ? .start : (index == stops.count - 1 ? .end : nil))
                        : nil,
                    isPinned: index == 0 ? pinStart : pinEnd,
                    // 도착하면 「안내 중」은 내린다 — 그 자리는 「다녀옴」의 것이다(2026-09-03
                    // 사용자 지적: 도착했는데 안내 중이 남아 있었다).
                    isTarget: trip.phase == .guiding && trip.target?.id == stop.id,
                    // 여행 중이면 어느 곳이든 「길찾기」 — 다녀온 곳도 다시 갈 수 있다(2026-09-04
                    // 사용자 지적: 코스를 또 만들 필요는 없다). 이 지도에 경로가 그려진다.
                    onNavigate: course.isRunning ? { startTrip(to: stop) } : nil,
                    onFocus: {
                        // **한 번 더 누르면 놓는다.** 놓을 방법이 없으면 한 곳을
                        // 고른 뒤 경로 전체를 다시 볼 수가 없다(2026-08-25 사용자 지적).
                        if focusedStop?.id == stop.id {
                            focusedStop = nil
                            fitToken += 1 // 일차 전체가 다시 보이게 맞춘다.
                        } else {
                            focusedStop = stop
                        }
                    },
                    onTogglePin: {
                        if index == 0 {
                            pinStart.toggle()
                        } else {
                            pinEnd.toggle()
                        }
                    }
                )
                .id(stop.id)
            }
            // **편집 모드를 켜지 않는다.** 켜면 드래그 손잡이가 늘 보이는 대신 행 안의
            // 버튼(체류 시간 칩·길찾기)이 눌리지 않는다 — iOS 가 편집 중 행의 탭을
            // 자기 것으로 가져간다. 목록을 길게 눌러 끄는 방식은 편집 모드 없이도
            // 되므로, 눌리는 쪽을 지키고 손잡이는 행 안에 그림으로 남겼다.
            .onMove { source, destination in
                course.days[dayIndex].stops.move(fromOffsets: source, toOffset: destination)
                // 손으로 순서를 바꿨다 — 동선이 낡았을 수 있다. 다시 권한다.
                optimizeNudge = course.days[dayIndex].stops.count >= 2
            }
            .onDelete { offsets in
                course.days[dayIndex].stops.remove(atOffsets: offsets)
                optimizeNudge = course.days[dayIndex].stops.count >= 2
            }

            if stops.isEmpty {
                Text("아직 담은 장소가 없습니다\n장바구니에서 담거나 지도에 핀을 찍어 보세요")
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
            }
        }
        .listStyle(.plain)
    }
}
