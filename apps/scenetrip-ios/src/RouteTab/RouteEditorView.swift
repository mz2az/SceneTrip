import SceneApiClient
import SwiftUI

/// 코스 편집 화면. AI 초안도 직접 짜기도 **여기로 모인다.**
///
/// 8/11 회의 확정: *"AI로 초안을 만들고 바로 저장시키는 게 아니고, 초안이 만들어지고
/// 거기서 수정할 수 있게 또 해 줘야 되겠네요"*. 그래서 이 화면은 **작업 사본**을 들고
/// 있다가 「코스 만들기」를 눌러야 `RouteStore` 에 넣는다 — 취소하면 아무것도 남지 않는다.
///
/// 여기서 할 수 있는 일은 회의에서 하나씩 확정된 것들이다.
/// - 일차를 ＋/− 로 더하고 뺀다 (뒤쪽부터, 장소가 있으면 멈추고 알린다)
/// - 목록을 끌어 방문 순서를 바꾼다
/// - 체류 시간을 고른다 (기본 30분)
/// - **동선 최적화** — 직선거리 기준으로 순서를 다시 잡는다. 길찾기 API 를 부르지 않는다.
/// - 장바구니에서 담기 / 지도에 직접 핀 찍기
///
/// **거리(km)는 보여 주고 예상 소요 시간은 보여 주지 않는다** (8/11 회의 2부 확정).
/// 직선거리에서 시간을 지어내면 사용자는 그것을 실제 이동 시간으로 읽는다.
struct RouteEditorView: View {
    @EnvironmentObject private var store: RouteStore
    @Environment(\.dismiss) private var dismiss

    /// 장바구니는 **검색 탭에서 이어진다.** 기기 UUID 가 같으므로 새로 만들어도 서버에
    /// 있는 그 장바구니가 온다 — 검색 탭의 `CartStore` 를 끌어오려면 그 파일을 고쳐야
    /// 하는데 검색 탭은 동결이다.
    @StateObject private var cart = CartStore()

    /// 상태를 `private` 로 잠그지 않는다. 지도·일차·요약 줄은 같은 타입의 확장이지만
    /// **다른 파일**(`RouteEditorControls.swift`)에 있고, Swift 의 `private` 는 파일
    /// 경계에서 끊긴다. 검색 탭도 `SearchTabOverlays.swift` 를 떼면서 같은 선택을 했다.
    @State var course: RouteCourse
    @State var dayIndex = 0
    @State var fitToken = 0
    @State var pinning = false
    @State var pendingPin: RoutePin?
    @State var showCart = false
    @State var stayTarget: RouteStop?
    @State var directionsTarget: RouteStop?
    @State var blockedDay: Int?

    let isNew: Bool

    init(course: RouteCourse, isNew: Bool) {
        _course = State(initialValue: course)
        self.isNew = isNew
    }

    var stops: [RouteStop] {
        course.days.indices.contains(dayIndex) ? course.days[dayIndex].stops : []
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if course.madeByAI, isNew {
                // 토스트는 몇 초 뒤 사라져 그 사이에 화면을 안 본 사람은 놓친다.
                // 저장 전이라는 사실은 저장할 때까지 화면에 남아 있어야 한다.
                RouteAIBanner(text: "AI 가 짠 일정입니다 · 아직 저장 전")
                    .padding(.horizontal, 16).padding(.bottom, 8)
            }
            map
            dayTabs
            summary
            actions
            stopList
            bottomBar
        }
        .background(Color(.systemGroupedBackground))
        .task { await cart.refresh() }
        .sheet(isPresented: $showCart) {
            RouteCartSheet(cart: cart) { picked in
                add(picked)
            }
        }
        .sheet(item: $pendingPin) { pin in
            RoutePinSheet(pin: pin) { name, category in
                add([RouteMock.pinnedPlace(
                    name: name, category: category,
                    lat: pin.latitude, lng: pin.longitude
                )], pinned: true)
            }
        }
        .sheet(item: $stayTarget) { stop in
            RouteStaySheet(stop: stop) { minutes in
                setStay(stop, minutes: minutes)
            }
        }
        .sheet(item: $directionsTarget) { stop in
            RouteDirectionsSheet(stop: stop)
        }
        .alert("일차를 뺄 수 없습니다", isPresented: Binding(
            get: { blockedDay != nil },
            set: {
                if !$0 {
                    blockedDay = nil
                }
            }
        )) {
            Button("확인", role: .cancel) {}
        } message: {
            Text("\((blockedDay ?? 0) + 1)일차에 담은 장소를 먼저 빼 주세요")
        }
    }

    // MARK: 머리와 발

    private var topBar: some View {
        HStack {
            Button("취소") { dismiss() }
            Spacer()
            Text(course.title).font(.headline).lineLimit(1)
            Spacer()
            Button(isNew ? "만들기" : "저장") {
                store.save(course)
                dismiss()
            }
            .font(.body.weight(.semibold))
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Color(.systemBackground))
    }

    private var bottomBar: some View {
        HStack(spacing: 10) {
            // 「코스 시작」은 저장된 코스에만 있다 — 아직 만들지도 않은 일정을 여행
            // 중으로 만들 수는 없다.
            if !isNew {
                Button(course.isRunning ? "여행 종료" : "코스 시작") {
                    course.isRunning.toggle()
                    store.save(course)
                }
                .buttonStyle(.bordered)
            }
            Button {
                store.save(course)
                dismiss()
            } label: {
                Text(isNew ? "코스 만들기" : "저장하고 닫기")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(16)
        .background(Color(.systemBackground))
    }

    // MARK: 목록

    private var stopList: some View {
        List {
            ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                RouteStopRow(
                    stop: stop,
                    number: index + 1,
                    // 다음 장소까지의 직선거리. 마지막 장소 뒤에는 갈 곳이 없다.
                    nextKilometers: index + 1 < stops.count
                        ? RouteGeometry.kilometers(stop.place, stops[index + 1].place)
                        : nil,
                    running: course.isRunning,
                    onStay: { stayTarget = stop },
                    onDirections: { directionsTarget = stop }
                )
            }
            // **편집 모드를 켜지 않는다.** 켜면 드래그 손잡이가 늘 보이는 대신 행 안의
            // 버튼(체류 시간 칩·길찾기)이 눌리지 않는다 — iOS 가 편집 중 행의 탭을
            // 자기 것으로 가져간다. 목록을 길게 눌러 끄는 방식은 편집 모드 없이도
            // 되므로, 눌리는 쪽을 지키고 손잡이는 행 안에 그림으로 남겼다.
            .onMove { source, destination in
                course.days[dayIndex].stops.move(fromOffsets: source, toOffset: destination)
            }
            .onDelete { offsets in
                course.days[dayIndex].stops.remove(atOffsets: offsets)
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

    // MARK: 담기

    private func add(_ places: [PlaceSummary], pinned: Bool = false) {
        course.days[dayIndex].stops += places.map { RouteStop(place: $0, isPinned: pinned) }
        fitToken += 1
    }

    private func setStay(_ stop: RouteStop, minutes: Int) {
        guard let index = course.days[dayIndex].stops.firstIndex(where: { $0.id == stop.id })
        else { return }
        course.days[dayIndex].stops[index].stayMinutes = minutes
    }
}
