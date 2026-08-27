import CoreLocation
import NMapsMap
import SwiftUI

/// 「길찾기」를 누른 뒤 (MZ2AZ-225).
///
/// 화면이 셋으로 나뉜다 — **지도**(실제 경로 + 반경 안 편의시설), **구간**(도보·환승),
/// **이 근처**(반경 안에서 찾은 것의 이름).
///
/// ## 편의시설을 지도에 이름까지 얹지 않는다
///
/// 처음에는 지도 위에 이름표를 띄웠다. 넷일 때는 읽히는데 **열 개가 넘으면 지도가
/// 이름표로 덮인다** — 정작 봐야 할 경로선이 가린다. 지도에는 점만 찍고 이름은
/// 아래 목록에 둔다. 점 색과 목록 점 색이 같아서 눈으로 이어진다.
///
/// ## 값이 목이다
///
/// 서버의 `POST /navigation/next-leg` 는 아직 `501` 이다(MZ2AZ-233). 여기 보이는
/// 노선·시간·요금과 편의시설은 **지어낸 값**이고, 화면이 무엇을 필요로 하는지를
/// 먼저 보여 주려고 만들었다. 서버가 서면 `RouteNavResult` 를 채우는 쪽만 바뀐다.
struct RouteNavView: View {
    let stop: RouteStop

    /// 그 일차의 코스 전체. 지도에 번호 핀으로 함께 그리고, 가이드가 「2번 주변」을
    /// 알아듣는 재료도 된다. 안 주면 목적지 하나만 안다.
    var dayStops: [RouteStop] = []

    @Environment(\.dismiss) private var dismiss
    @StateObject private var locator = RouteLocator()
    @State private var arrived = false

    /// 가이드 대화창이 열려 있는가.
    @State private var showGuide = false

    /// 가이드와의 대화. **앱 공용이다** — 계획 화면에서 하던 대화가 여기로
    /// 이어지고, 닫아도 남는다.
    @ObservedObject private var guide = RouteGuideSession.shared

    /// 화면 범위 안의 주변 편의시설. **기본은 전부 꺼짐** — 길찾기 화면의
    /// 주인공은 경로라, 배경 점은 사용자가 켤 때만 나온다(2026-08-28 사용자 결정).
    /// 목록은 늘 받아 둔다(개수 칩을 보여 줘야 켤 마음이 생긴다) — 우리 자료
    /// 조건 질의라 부르는 값이 없다.
    @State private var poiGroupsOn: Set<RoutePoiGroup> = []
    @State private var ambientPois: [RouteGuide.Place] = []
    @State private var ambientTask: Task<Void, Never>?

    /// 성지(코스 번호 핀)를 지도에 그릴 것인가. **켜짐이 기본** — 여정의 뼈대다.
    @State private var showSanctums = true

    /// 지도에서 누른 성지. 장면 설명 카드가 뜬다.
    @State private var pickedStop: RouteStop?

    /// **즉석에서 갈아탄 목적지.** 가이드가 찾아 준 가게로 「여기로 길찾기」를 누르면
    /// 원래 촬영지 대신 여기로 안내한다 — 걷다가 배가 고프면 목적지가 바뀌는 것이
    /// 내비게이션이다. `nil` 이면 원래 목적지(`stop`)다.
    @State private var detour: RouteGuide.Place?

    /// 카카오가 준 안내. 아직 안 왔으면 nil 이다.
    @State private var result: RouteNavResult?
    @State private var routeError: String?
    @State private var asking = false

    /// 지금 위치. 아직 못 받았으면 nil 이고, 그때 지도는 파란 점을 그리지 않는다 —
    /// **없는 위치를 지어내 찍지 않는다.** 엉뚱한 자리에 내가 있다고 하는 것이
    /// 아무 표시도 없는 것보다 나쁘다.
    private var here: (latitude: Double, longitude: Double)? {
        if case let .found(latitude, longitude) = locator.state {
            return (latitude, longitude)
        }
        return nil
    }

    /// 위치를 못 받았을 때 한 줄로 알린다. 받았으면 nil.
    private var locationNotice: String? {
        switch locator.state {
        case .found: nil
        case .asking: "현재 위치를 찾는 중입니다"
        case .denied: "위치 권한이 없어 현재 위치를 표시할 수 없습니다"
        case .failed: "현재 위치를 찾지 못했습니다"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            map
            poiChips
            summary
            Divider()
            legList
            bottomBar
        }
        .task { locator.start() }
        // 정보 카드는 화면 바닥에. 지도(300pt) 위에 얹으면 카드가 더 커서
        // 뚫고 나간다 — 편집 화면과 같은 규칙이다.
        .overlay(alignment: .bottom) {
            if let picked = guide.picked, !showGuide {
                RoutePlaceCard(
                    place: picked,
                    onReroute: { reroute(to: picked) },
                    onClose: { guide.picked = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 80)
            } else if let tapped = pickedStop, !showGuide {
                // 성지 카드 — 장면 설명 + 여기로 길찾기. 이미 코스에 있는 곳이라
                // 담기는 없다. 지금 가는 곳 자신이면 길찾기 단추는 뺀다.
                RouteStopCard(
                    stop: tapped,
                    onReroute: tapped.id == stop.id && detour == nil ? nil : {
                        reroute(to: RouteGuide.Place(
                            id: "stop-\(tapped.id)",
                            name: tapped.place.name,
                            category: tapped.place.type,
                            latitude: tapped.place.latitude,
                            longitude: tapped.place.longitude
                        ))
                        pickedStop = nil
                    },
                    onClose: { pickedStop = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 80)
            }
        }
        .sheet(isPresented: $showGuide) {
            RouteGuideSheet(
                session: guide,
                here: here.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                } ?? CLLocationCoordinate2D(
                    // 위치를 못 받았으면 **목적지 주변**으로 묻는다. 여행 중에
                    // 「거기 가면 뭐가 있나」도 물을 만한 질문이다.
                    latitude: stop.place.latitude, longitude: stop.place.longitude
                ),
                context: RouteGuide.Context(
                    // 일차 전체를 번호째 준다 — 여행 중에도 「2번 주변 음식점」
                    // 이 통해야 한다(2026-08-28 사용자 요청).
                    stops: dayStops.enumerated().map { index, dayStop in
                        .init(
                            number: index + 1, name: dayStop.place.name,
                            kind: dayStop.place.type,
                            latitude: dayStop.place.latitude,
                            longitude: dayStop.place.longitude
                        )
                    },
                    picked: .init(
                        number: (dayStops.firstIndex { $0.id == stop.id })
                            .map { $0 + 1 } ?? 0,
                        name: stop.place.name, kind: stop.place.type,
                        latitude: stop.place.latitude, longitude: stop.place.longitude
                    )
                ),
                onReroute: { reroute(to: $0) }
            )
        }
        // 위치가 오면 그때 부른다. **누를 때만 부르는 것이 비용 통제 장치**이고,
        // 이 화면 자체가 「길찾기」를 누른 결과라 여기서 한 번만 부르면 된다.
        .onChange(of: locator.state) { _, state in
            guard case let .found(latitude, longitude) = state else { return }
            Task { await load(from: latitude, longitude: longitude) }
        }
    }

    /// 손수 그린 머리줄 — 가이드 시트와 같은 이유다. 내비게이션 바에 「닫기」를
    /// 넣으면 시스템이 유리 캡슐을 깔아 왼쪽에서 크게 그린다(2026-08-28 사용자
    /// 지적, 세 번째라 이제 규칙이다: **닫기는 오른쪽 위 작은 X**).
    private var header: some View {
        ZStack {
            Text(destination.name)
                .font(.headline).lineLimit(1)
                .padding(.horizontal, 44)
            HStack {
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 6)
    }

    /// 갈래 칩 — 편집 화면과 같은 부품에 **성지 칩**이 하나 더 붙는다.
    /// 성지는 코스의 번호 핀이라 「전체」(편의시설 마스터 스위치) 소관 밖이다.
    private var poiChips: some View {
        RoutePoiChips(
            places: ambientPois, groupsOn: $poiGroupsOn,
            onGroupOff: { group in
                // 챗봇이 찾아 준 핀은 이 칩의 소관이 아니다 — 주변 점에서 고른
                // 것만 놓는다.
                if let picked = guide.picked, picked.poiGroup == group,
                   ambientPois.contains(where: { $0.id == picked.id })
                {
                    guide.picked = nil
                }
            },
            extras: [
                .init(
                    id: "sanctum",
                    label: "성지 \(dayStops.count)",
                    tone: Color(PinImage.deep),
                    isOn: showSanctums
                ) {
                    showSanctums.toggle()
                    if !showSanctums {
                        pickedStop = nil // 지도에서 사라진 핀의 카드는 닫는다
                    }
                },
            ]
        )
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
    }

    /// 갈래 필터를 통과한 주변 점. 챗봇 결과와 겹치면 뺀다.
    private var visibleAmbientPois: [RouteGuide.Place] {
        let shown = Set(guide.places.map { RouteDedupe.key($0.asPlaceSummary) })
        return ambientPois.filter { place in
            poiGroupsOn.contains(place.poiGroup)
                && !shown.contains(RouteDedupe.key(place.asPlaceSummary))
        }
    }

    /// 카메라가 멈췄다 — 편집 화면과 같은 규칙(0.35초 조용하면, 줌 13 미만은 안 부름).
    private func viewportChanged(
        south: Double, west: Double, north: Double, east: Double,
        centerLat: Double, centerLng: Double, zoom: Double
    ) {
        ambientTask?.cancel()
        // 줌이 아니라 **화면의 실제 남북 폭**으로 거른다. 이 지도는 높이가
        // 300pt 라 같은 동네를 봐도 줌 숫자가 낮게 나온다 — 줌 13 가드에 늘
        // 걸려 주변 목록이 영영 비었다(2026-08-28 사용자 발견: 칩이 안 뜸).
        _ = zoom
        guard north - south <= 0.1 else { // 약 11 km — 이보다 넓으면 점이 먼지다
            ambientPois = []
            return
        }
        ambientTask = Task {
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            let found = await RouteGuide.pois(
                south: south, west: west, north: north, east: east,
                centerLat: centerLat, centerLng: centerLng, limit: 30
            )
            guard !Task.isCancelled else { return }
            ambientPois = found
        }
    }

    /// 지금 안내하는 목적지 — 갈아탔으면 그 가게, 아니면 원래 촬영지.
    private var destination: (name: String, latitude: Double, longitude: Double) {
        if let detour {
            return (detour.name, detour.latitude, detour.longitude)
        }
        return (stop.place.name, stop.place.latitude, stop.place.longitude)
    }

    /// 목적지를 이 가게로 갈아타고 경로를 다시 받는다.
    private func reroute(to place: RouteGuide.Place) {
        detour = place
        guide.picked = nil
        result = nil // `load` 의 「이미 받았으면 안 받는다」 문을 다시 연다.
        routeError = nil
        if let here {
            Task { await load(from: here.latitude, longitude: here.longitude) }
        }
    }

    private func load(from latitude: Double, longitude: Double) async {
        guard result == nil, !asking else { return }
        asking = true
        defer { asking = false }
        do {
            result = try await KakaoTransit.leg(
                from: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                to: CLLocationCoordinate2D(
                    latitude: destination.latitude,
                    longitude: destination.longitude
                ),
                destinationName: destination.name
            )
            routeError = nil
        } catch KakaoTransit.Failure.noKey {
            routeError = "길찾기 키가 없어 안내를 받을 수 없습니다"
        } catch KakaoTransit.Failure.noRoute {
            routeError = "대중교통으로 갈 수 있는 길을 찾지 못했습니다"
        } catch {
            routeError = "길찾기 안내를 받지 못했습니다"
        }
    }

    // MARK: 지도

    private var map: some View {
        ZStack(alignment: .bottomTrailing) {
            RouteNavMapView(
                stop: stop,
                dayStops: dayStops,
                showDayStops: showSanctums,
                onTapStop: { tapped in
                    pickedStop = tapped
                    guide.picked = nil // 카드는 한 장만
                },
                goal: detour.map { ($0.latitude, $0.longitude) },
                guidePlaces: guide.places,
                picked: guide.picked,
                onTapPlace: { tapped in
                    guide.picked = tapped
                    pickedStop = nil // 카드는 한 장만
                },
                legs: result?.legs ?? [],
                here: here,
                ambientPlaces: visibleAmbientPois,
                onViewport: viewportChanged
            )
            .frame(height: 300)

            // 챗봇은 여행 중에도 늘 손에 닿는 자리에 있다 — 길을 잃었을 때 물을
            // 상대가 화면을 나가야 나온다면 아무도 못 쓴다. **접힌 동그라미**라
            // 지도를 거의 가리지 않고, 계획 화면과 같은 모양이다.
            RouteGuideChip { showGuide = true }
                .padding(12)
        }
    }

    // MARK: 요약

    private var summary: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let result {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(RouteFormat.minutes(result.totalMinutes))
                        .font(.title2.weight(.bold))
                    Text(result.summaryLine)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            } else if let routeError {
                Label(routeError, systemImage: "exclamationmark.triangle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else if asking {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("길을 찾는 중입니다").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if let locationNotice {
                Label(locationNotice, systemImage: "location.slash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    // MARK: 구간

    private var legList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(result?.legs ?? []) { leg in
                    HStack(alignment: .top, spacing: 12) {
                        VStack(spacing: 0) {
                            Image(systemName: leg.mode.symbol)
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .frame(width: 26, height: 26)
                                .background(Circle().fill(tone(for: leg.mode)))
                            // 마지막 구간 뒤에는 이어질 것이 없다.
                            if leg.id != result?.legs.last?.id {
                                Rectangle()
                                    .fill(Color(.separator))
                                    .frame(width: 2)
                                    .frame(maxHeight: .infinity)
                            }
                        }
                        .frame(width: 26)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(leg.title).font(.subheadline.weight(.semibold))
                            Text(leg.detail).font(.caption).foregroundStyle(.secondary)
                            if leg.hasStairs {
                                Label("계단 있음", systemImage: "stairs")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(Color(.systemRed))
                                    .padding(.horizontal, 8).padding(.vertical, 3)
                                    .background(Capsule().fill(Color(.systemRed).opacity(0.1)))
                                    .padding(.top, 3)
                            }
                        }
                        .padding(.bottom, 14)
                        Spacer(minLength: 0)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
        }
    }

    private func tone(for mode: RouteLegMode) -> Color {
        switch mode {
        case .walk: Color(.tertiaryLabel)
        case .transit: Color(.systemGreen)
        }
    }

    private var bottomBar: some View {
        Button {
            arrived = true
            dismiss()
        } label: {
            Text("여기 도착함")
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .padding(16)
    }
}
