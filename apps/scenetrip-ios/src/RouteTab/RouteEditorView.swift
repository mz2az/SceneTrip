import CoreLocation
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

    /// 「내 위치」 토글이 켜져 있는가. `RouteMapView.showingMe` 참고.
    @State var showingMe = false

    /// 목록에서 고른 장소. 지도가 여기로 옮겨 간다.
    @State var focusedStop: RouteStop?

    /// 시트에서 **담을까 보고 있는** 곳. 지도에 빨간 고양이로 뜬다.
    /// 시트를 닫으면 비운다 — 담지 않고 나갔는데 빨간 핀이 남아 있으면 안 된다.
    @State var previewPlaces: [PlaceSummary] = []
    @State var pinning = false
    @State var pendingPin: RoutePin?
    @State var showCart = false
    @State var showSearch = false

    /// 일정 시트가 얼마나 올라와 있나. **반이 기본이다** — 지도와 일정을 같이 본다.
    @State var panelDetent: BottomSheet<AnyView>.Detent = .medium

    /// 일정 시트가 지금 덮고 있는 높이. 지도 카메라가 이 위 영역에만 맞춘다.
    @State var panelHeight: CGFloat = 0

    /// 여행 가이드 대화창이 열려 있는가.
    @State var showGuide = false

    /// 「주변」의 기준이 되는 자리. 가이드가 이것으로 찾는다.
    @StateObject var guideLocator = RouteLocator()

    /// 가이드와의 대화. **시트 밖에서 든다** — 닫아도 남아야 한다.
    @StateObject var guide = RouteGuideSession()

    /// 지도에 보여 줄 편의시설 갈래. 기본은 전부 — 끄는 것은 사용자의 선택이다.
    @State var poiGroupsOn: Set<RoutePoiGroup> = Set(RoutePoiGroup.allCases)

    /// 동선 최적화 단추가 **반짝여야 하는가.** 장소가 새로 담기면 켜진다 — 방금
    /// 담긴 곳은 줄 맨 끝이라 순서가 대개 엉망이 된다. 한 번 최적화하면 꺼진다.
    @State var optimizeNudge = false
    @State var stayTarget: RouteStop?
    @State var directionsTarget: RouteStop?
    @State var blockedDay: Int?

    /// 동선 최적화가 손대지 않을 자리.
    ///
    /// **둘 다 선택이고 서로 무관하다.** 숙소에서 나와 아무 데서나 끝내는 사람은
    /// 출발만, 아무 데서나 시작해 숙소로 돌아오는 사람은 도착만 켠다. 현재 위치에서
    /// 출발하는 사람은 둘 다 끈다 — 그 사람에게는 목록 첫 줄이 출발지가 아니다.
    ///
    /// 출발을 기본으로 켜 두는 것은 앞서 늘 그렇게 동작했기 때문이다. 끄는 것은
    /// 한 번 누르면 되지만, 켜져 있는 줄 모르고 쓰던 사람의 결과가 갑자기 달라지면
    /// 그쪽이 더 놀랍다.
    @State var pinStart = true
    @State var pinEnd = false

    let isNew: Bool

    init(course: RouteCourse, isNew: Bool) {
        _course = State(initialValue: course)
        self.isNew = isNew
    }

    var stops: [RouteStop] {
        course.days.indices.contains(dayIndex) ? course.days[dayIndex].stops : []
    }

    /// 갈래 필터를 통과한 가이드 장소. 지도는 이것만 그린다.
    var visibleGuidePlaces: [RouteGuide.Place] {
        guide.places.filter { poiGroupsOn.contains($0.poiGroup) }
    }

    /// 고른 장소도 갈래가 꺼져 있으면 지도에서 감춘다.
    var visiblePickedGuide: RouteGuide.Place? {
        guide.picked.flatMap { poiGroupsOn.contains($0.poiGroup) ? $0 : nil }
    }

    /// 이 가이드 장소가 이미 코스(어느 일차든)에 들어 있는가. `RouteDedupe` 와
    /// 같은 열쇠(이름+좌표)로 본다 — 담을 때 걸러지는 기준 그대로다.
    func isAdded(_ place: RouteGuide.Place) -> Bool {
        let key = RouteDedupe.key(place.asPlaceSummary)
        return course.days.contains { day in
            day.stops.contains { RouteDedupe.key($0.place) == key }
        }
    }

    /// 카드의 체크를 한 번 더 눌렀다 — **모든 일차에서** 뺀다. 담을 때와 같은
    /// 열쇠로 지우므로, 담은 것과 다른 것이 지워질 일은 없다.
    func removeGuidePlace(_ place: RouteGuide.Place) {
        let key = RouteDedupe.key(place.asPlaceSummary)
        for index in course.days.indices {
            course.days[index].stops.removeAll { RouteDedupe.key($0.place) == key }
        }
        fitToken += 1
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if course.madeByAI, isNew {
                // 토스트는 몇 초 뒤 사라져 그 사이에 화면을 안 본 사람은 놓친다.
                // 저장 전이라는 사실은 저장할 때까지 화면에 남아 있어야 한다.
                RouteAIBanner(text: course.filledFromPopular
                    ? "고른 작품의 촬영지가 아직 없어 인기 장소로 채웠습니다"
                    : "AI 가 짠 일정입니다 · 아직 저장 전")
                    .padding(.horizontal, 16).padding(.bottom, 8)
            }
            // **지도가 주인공이다.** 앞서 지도가 210pt 고정이고 목록이 나머지를
            // 다 먹었는데, 코스를 짜는 동안 정작 「어디를 도는가」가 우표만 했다
            // (2026-08-27 사용자 지적). 지도를 화면에 다 깔고 일정은 **끌어 올렸다
            // 내렸다 하는 시트**로 얹는다 — 검색 탭과 같은 부품, 같은 손맛이다.
            ZStack(alignment: .bottom) {
                map
                BottomSheet(
                    detent: $panelDetent, topInset: 8,
                    // 지도 40 : 일정 60. 반반이었는데 일정 쪽에 단추가 늘며
                    // 좁아졌다(2026-08-28 사용자 요청).
                    mediumFraction: 0.60,
                    onHeightChange: { panelHeight = $0 }
                ) {
                    // `AnyView` 는 detent 타입을 맞추기 위한 것이다 — `Detent` 가
                    // 제네릭 안에 살아서 상태 선언이 내용 타입을 미리 못 안다.
                    AnyView(VStack(spacing: 0) {
                        dayTabs
                        poiFilter
                        summary
                        actions
                        stopList
                    })
                }
            }
            bottomBar
        }
        .background(Color(.systemGroupedBackground))
        // 정보 카드는 **화면 바닥**에 띄운다. 앞서 지도(210pt) 위에 얹었더니 카드가
        // 지도보다 커서 화면 위로 뚫고 나갔다(2026-08-27 사용자 지적). 가이드
        // 시트가 열려 있을 때는 여기 안 띄운다 — 시트가 바닥을 덮고 있어서 가려진다.
        // 그때는 시트 안에 뜬다(`RouteGuideSheet`).
        .overlay(alignment: .bottom) {
            if let picked = guide.picked, !showGuide {
                RoutePlaceCard(
                    place: picked,
                    onAdd: {
                        add([picked.asPlaceSummary], pinned: true)
                        guide.picked = nil // 담았으면 카드는 할 일을 다 했다
                    },
                    added: isAdded(picked),
                    onRemove: { removeGuidePlace(picked) },
                    onClose: { guide.picked = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 90) // 「저장하고 닫기」 줄 위
            }
        }
        .task {
            await cart.refresh()
            // 가이드가 「주변」을 찾으려면 자리가 있어야 한다. 미리 물어 둔다 —
            // 단추를 누른 뒤에 물으면 그만큼 기다린다.
            guideLocator.start()
        }
        // 저장이 실패하면 이유를 말한다. 버튼이 안 먹는 것처럼 보이면 사용자는
        // 같은 버튼을 계속 누르게 된다.
        .alert("저장하지 못했습니다", isPresented: Binding(
            get: { store.failure != nil },
            set: {
                if !$0 {
                    store.clearFailure()
                }
            }
        )) {
            Button("확인") { store.clearFailure() }
        } message: {
            Text(store.failure?.message ?? "")
        }
        .sheet(isPresented: $showGuide) {
            RouteGuideSheet(
                session: guide,
                here: guideHere,
                context: guideContext,
                onAdd: { add([$0], pinned: true) },
                isAdded: isAdded,
                onRemove: removeGuidePlace
            )
        }
        .sheet(isPresented: $showSearch) {
            RouteSearchSheet(
                taken: takenPlaceIds,
                onPreview: { previewPlaces = $0 },
                onAdd: { add($0) }
            )
            .onDisappear { previewPlaces = [] }
        }
        .sheet(isPresented: $showCart) {
            RouteCartSheet(
                cart: cart,
                taken: takenPlaceIds,
                onPreview: { previewPlaces = $0 },
                onPick: { add($0) }
            )
            .onDisappear { previewPlaces = [] }
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
            RouteNavView(stop: stop, dayStops: stops)
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
                Task { await saveAndClose() }
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
                    // 상태만 바꾼다 — 코스 내용을 함께 덮어쓰면 편집 중이던 것이
                    // 저장돼 버려 「시작」이 「저장」을 겸하게 된다.
                    // 지금 보고 있는 일차에서 시작한다 — 서버가 `currentDayNo` 를
                    // 요구하고, 1일차를 지나 보고 있다면 그 일차가 맞다.
                    Task { await store.setRunning(course, course.isRunning, dayNo: dayIndex + 1) }
                }
                .buttonStyle(.bordered)
            }
            Button {
                Task { await saveAndClose() }
            } label: {
                Text(isNew ? "코스 만들기" : "저장하고 닫기")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        // 줄을 낮게 잡는다 — 이 줄이 먹는 만큼 일정이 좁아진다(2026-08-28
        // 사용자 요청: 단추 위아래와 글자를 줄여 아래쪽을 넓힌다).
        .font(.subheadline)
        .controlSize(.regular)
        .padding(.horizontal, 16).padding(.vertical, 8)
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
                    isFocused: focusedStop?.id == stop.id,
                    works: workTitles(for: stop),
                    // 첫 줄에 「출발 고정」, 마지막 줄에 「도착 고정」. 한 곳뿐이면
                    // 고정할 것이 없다 — 그 하나가 출발이자 도착이라 뜻이 없다.
                    pinKind: stops.count > 1
                        ? (index == 0 ? .start : (index == stops.count - 1 ? .end : nil))
                        : nil,
                    isPinned: index == 0 ? pinStart : pinEnd,
                    onStay: { stayTarget = stop },
                    onDirections: { directionsTarget = stop },
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

    /// 저장하고 닫는다. **실패하면 닫지 않는다** — 조용히 닫으면 저장된 줄 알고
    /// 나갔다가 목록에 없는 것을 보게 된다.
    private func saveAndClose() async {
        if await store.save(course) != nil {
            dismiss()
        }
    }

    // MARK: 담기

    /// 코스 **전체**에 이미 담긴 촬영지의 id.
    ///
    /// 지금 일차가 아니라 **모든 일차**를 본다 — 3일차에 담아 둔 곳을 1일차에 또
    /// 담으면 같은 여행에서 한 곳을 두 번 가게 된다.
    ///
    /// 직접 찍은 핀(id 가 음수)은 세지 않는다. 같은 자리를 두 번 찍었더라도 사용자가
    /// 뜻이 있어 찍은 것이고, 우리가 「같은 곳」이라고 판단할 근거도 없다.
    var takenPlaceIds: Set<Int64> {
        Set(course.days.flatMap(\.stops).map(\.place.id).filter { $0 > 0 })
    }

    /// 이미 담긴 곳의 열쇠. 갈래는 `RouteDedupe` 가 정한다.
    private var takenSpotKeys: Set<String> {
        Set(course.days.flatMap(\.stops).map { RouteDedupe.key($0.place) })
    }

    /// **이미 담긴 곳은 걸러 낸다.** 앞서 거르지 않아 같은 촬영지가 코스에 여러 번
    /// 들어갔다(2026-08-25 사용자 지적). 시트 쪽에서도 체크로 보여 주지만, 거르는
    /// 것은 여기서 한다 — 시트가 늘어나도 규칙이 한 곳에 남는다.
    private func add(_ places: [PlaceSummary], pinned: Bool = false) {
        let fresh = RouteDedupe.fresh(
            places, takenIds: takenPlaceIds, takenKeys: takenSpotKeys
        )
        guard !fresh.isEmpty else { return }
        course.days[dayIndex].stops += fresh.map { RouteStop(place: $0, isPinned: pinned) }
        fitToken += 1
        // 둘부터 순서라는 것이 생긴다 — 그때부터 최적화를 권한다.
        if course.days[dayIndex].stops.count >= 2 {
            optimizeNudge = true
        }
    }

    /// 이 장소가 나온 작품. **서버가 코스 아이템에 안 실어 주므로** 촬영지 목록
    /// (`RouteStore.places`)에서 `placeId` 로 되짚는다.
    ///
    /// 둘까지만 적는다 — 셋을 넘기면 줄이 넘쳐 주소가 밀린다. 「외 2편」처럼 세는
    /// 것도 생각했지만 남은 것이 무엇인지 모르면 세어 봐야 쓸모가 없다.
    private func workTitles(for stop: RouteStop) -> String {
        // 직접 찍은 핀은 촬영지가 아니라 되짚을 것이 없다(id 가 음수다).
        guard stop.place.id > 0,
              let found = store.places.first(where: { $0.id == stop.place.id })
        else { return "" }
        let titles = (found.contents ?? []).map(\.title)
        return titles.prefix(2).joined(separator: " · ")
    }

    /// 가이드에게 줄 화면 상태 — **지금 일차의 번호 핀 그대로.**
    ///
    /// 순서를 바꾸거나 동선 최적화를 누르면 번호가 달라지는데, 그때마다 다시
    /// 만들어지므로 모델이 보는 번호와 지도의 번호가 어긋나지 않는다
    /// (2026-08-27 사용자 지적 — 앞서 아예 안 보내서 「2번이 어디냐」를 몰랐다).
    var guideContext: RouteGuide.Context {
        RouteGuide.Context(
            stops: stops.enumerated().map { index, stop in
                .init(
                    number: index + 1,
                    name: stop.place.name,
                    kind: stop.place.type,
                    latitude: stop.place.latitude,
                    longitude: stop.place.longitude
                )
            },
            picked: focusedStop.map {
                .init(
                    number: 0, name: $0.place.name, kind: $0.place.type,
                    latitude: $0.place.latitude, longitude: $0.place.longitude
                )
            }
        )
    }

    /// 가이드에게 줄 「지금 자리」.
    ///
    /// 위치를 못 받았으면 **지금 보고 있는 장소**로 대신한다 — 코스를 짜는 중에는
    /// 「내가 선 자리」보다 「지금 보는 곳 주변」이 궁금한 경우가 많고, 실내에서
    /// 위치를 못 잡아도 물어볼 수 있어야 한다.
    var guideHere: CLLocationCoordinate2D? {
        if case let .found(latitude, longitude) = guideLocator.state {
            return .init(latitude: latitude, longitude: longitude)
        }
        let anchor = focusedStop ?? stops.first
        return anchor.map {
            .init(latitude: $0.place.latitude, longitude: $0.place.longitude)
        }
    }

    private func setStay(_ stop: RouteStop, minutes: Int) {
        guard let index = course.days[dayIndex].stops.firstIndex(where: { $0.id == stop.id })
        else { return }
        course.days[dayIndex].stops[index].stayMinutes = minutes
    }
}
