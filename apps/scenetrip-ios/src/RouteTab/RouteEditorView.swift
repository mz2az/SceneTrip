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
    @EnvironmentObject var store: RouteStore
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
    /// **켜짐이 기본이다**(2026-08-28) — 코스를 보는 사람은 대개 자기 위치와
    /// 견주고 싶어서 본다. 끄는 것은 선택으로 남는다.
    @State var showingMe = true

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

    /// 가이드와의 대화. **앱 공용이다** — 길찾기 화면과 같은 대화를 본다.
    @ObservedObject var guide = RouteGuideSession.shared

    /// 지도에 보여 줄 편의시설 갈래. 기본은 전부 — 끄는 것은 사용자의 선택이다.
    @State var poiGroupsOn: Set<RoutePoiGroup> = Set(RoutePoiGroup.allCases)

    /// 동선 최적화 단추가 **반짝여야 하는가.** 장소가 새로 담기면 켜진다 — 방금
    /// 담긴 곳은 줄 맨 끝이라 순서가 대개 엉망이 된다. 한 번 최적화하면 꺼진다.
    @State var optimizeNudge = false

    /// 화면 범위 안의 주변 편의시설. 카메라가 멈출 때마다 다시 받는다.
    @State var ambientPois: [RouteGuide.Place] = []
    @State var ambientTask: Task<Void, Never>?
    @State var stayTarget: RouteStop?

    /// 여행 안내 — **이 화면 안에서 돈다**(2026-09-03, 계획 trip-mode.md §8). 별도
    /// 길찾기 창은 코스 여행에서 더 안 쓴다. 목적지·경로·머무름·스탬프는 이것이 든다.
    @StateObject var trip = TripSession()

    /// 홈 「이어서 길찾기」가 이 코스를 열며 안내를 켜 달라고 남긴 표시를 읽는다.
    /// 환경 객체가 아니라 공용 인스턴스다 — 이 화면은 덮개(fullScreenCover) 안에 떠서
    /// 환경이 안 닿는다(2026-09-03 실측: `@EnvironmentObject` 로 두니 첫 진입에 죽었다).
    @ObservedObject var router = TabRouter.shared

    /// 지도의 번호 핀을 눌렀다 — 성지 카드(장면 설명·여기로 길찾기).
    @State var pickedStop: RouteStop?

    /// 지도 위 도착 알림 카드의 높이. 오른쪽 위 단추들이 그만큼 내려온다.
    @State var tripBannerHeight: CGFloat = 0

    /// 캡쳐 뒷문이 이 프로세스에서 이미 발동했는가(MZ2AZ-292).
    private static var captureBackdoorUsed = false
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
    ///
    /// **코스에 이미 담긴 곳은 뺀다** — 담는 순간 그 자리는 번호 핀의 것이다.
    /// 안 빼면 같은 좌표에 챗봇 마커가 겹쳐 핀이 두 장으로 보인다(2026-08-28
    /// 사용자 발견).
    var visibleGuidePlaces: [RouteGuide.Place] {
        let taken = takenSpotKeys
        return guide.places.filter {
            poiGroupsOn.contains($0.poiGroup)
                && !taken.contains(RouteDedupe.key($0.asPlaceSummary))
        }
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
                    // **도착 알림만** 지도 맨 위 카드로(2026-09-03 사용자 결정). 경로 정보는
                    // 시트 안(`tripBanner`)에 — 지도를 카드로 다 가리지 않는다.
                    .overlay(alignment: .top) { arrivalNotice }
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
                        tripBanner
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
            } else {
                stopCardOverlay
            }
        }
        // 도착 스탬프 — 화면 가운데 발바닥이 쾅.
        .overlay { stampOverlay }
        .task {
            // 스탬프가 찍히면 코스 상태·서버에 「다녀옴」 — 목록이 흐려지고 핀이 발바닥이 된다.
            trip.onArrived = { markVisited($0) }
            await cart.refresh()
            // 가이드가 「주변」을 찾으려면 자리가 있어야 한다. 미리 물어 둔다 —
            // 단추를 누른 뒤에 물으면 그만큼 기다린다.
            guideLocator.start()

            await runCaptureBackdoor()
            await runPendingTripStart()
        }
        // 화면을 닫으면 안내도 끝난다 — 위치 받기가 뒤에서 계속 돌면 안 된다.
        .onDisappear { trip.end() }
        // **안내 중에는 편의시설 점을 다 끈다**(2026-09-04 사용자 요청) — 경로선이 주인공인데
        // 음식점·명소 점이 그 위를 덮었다. 안내가 끝나면 다시 전부 켠다. 안내 중에 칩으로
        // 켜는 것은 그대로 된다.
        .onChange(of: trip.isActive) { _, active in
            poiGroupsOn = active ? [] : Set(RoutePoiGroup.allCases)
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
        // 가이드는 시트가 아니라 **오른쪽 서랍**이다 — 오른쪽에서 미끄러져
        // 나오는 고정 크기 창(2026-08-28 사용자 요청). 지도가 계속 보인다.
        .guidePanel(isOpen: showGuide) {
            RouteGuideSheet(
                session: guide,
                here: guideHere,
                context: guideContext,
                onAdd: { add([$0], pinned: true) },
                isAdded: isAdded,
                onRemove: removeGuidePlace,
                onClose: { showGuide = false }
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
                    // 도착하면 「안내 중」은 내린다 — 그 자리는 「다녀옴」의 것이다(2026-09-03
                    // 사용자 지적: 도착했는데 안내 중이 남아 있었다).
                    isTarget: trip.phase == .guiding && trip.target?.id == stop.id,
                    // 여행 중, 아직 안 간 곳에만 「길찾기」 — 이 지도에 경로가 그려진다.
                    onNavigate: course.isRunning && !stop.visited ? { startTrip(to: stop) } : nil,
                    onStay: { stayTarget = stop },
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

    /// 저장하고 닫는다. **실패하면 닫지 않는다** — 조용히 닫으면 저장된 줄 알고
    /// 나갔다가 목록에 없는 것을 보게 된다.
    func saveAndClose() async {
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
    var takenSpotKeys: Set<String> {
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

/// 타입 본문 길이(swiftlint 350줄) 때문에 여기 둔다.
extension RouteEditorView {
    /// 스탬프가 찍혔다 — 코스 상태의 그 정지점도 「다녀옴」으로. 목록이 흐려지고 지도의
    /// 핀이 발바닥이 되며, 「다음 · N번으로」가 그다음 곳을 가리킨다.
    func markVisitedLocally(_ visited: RouteStop) {
        for day in course.days.indices {
            if let index = course.days[day].stops.firstIndex(where: { $0.id == visited.id }) {
                course.days[day].stops[index].visited = true
            }
        }
    }

    /// 확인용 뒷문(MZ2AZ-292) — 합성 클릭이 안 닿는 시뮬레이터에서 화면을 기계로 열어
    /// 캡쳐한다. `-openGuide 1` 은 가이드 서랍, `-navStop 2` 는 그 번호 성지로 **이 화면
    /// 안의 안내**를 켠다. 찜 뒷문과 같은 프로세스당 한 번 규칙.
    func runCaptureBackdoor() async {
        guard !Self.captureBackdoorUsed else { return }
        Self.captureBackdoorUsed = true
        if UserDefaults.standard.bool(forKey: "openGuide") {
            showGuide = true
        }
        let wanted = UserDefaults.standard.integer(forKey: "navStop")
        guard wanted > 0, await waitForStops(count: wanted) else { return }
        startTrip(to: stops[wanted - 1])
    }

    /// 홈 「이어서 길찾기」 — 코스가 열리면 첫 미방문 성지로 안내를 켠다. 표시는 한 번 읽고 끈다.
    func runPendingTripStart() async {
        guard router.pendingTripStart else { return }
        router.pendingTripStart = false
        guard await waitForStops(count: 1), let next = nextUnvisited?.stop ?? stops.first else { return }
        startTrip(to: next)
    }

    /// 코스 상세(정지점)가 아직 안 왔을 수 있다 — 홈·뒷문에서 열면 목록의 요약으로
    /// 먼저 열리고 정지점은 뒤따라온다. 최대 3초 기다린다(2026-09-02 실측: 기다리지
    /// 않으면 뒷문이 빈 목록을 보고 그냥 끝났다).
    private func waitForStops(count: Int) async -> Bool {
        for _ in 0 ..< 30 where stops.count < count {
            try? await Task.sleep(for: .milliseconds(100))
        }
        return stops.count >= count
    }
}
