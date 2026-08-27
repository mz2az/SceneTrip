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
        NavigationStack {
            VStack(spacing: 0) {
                map
                summary
                Divider()
                legList
                bottomBar
            }
            .navigationTitle(destination.name)
            .navigationBarTitleDisplayMode(.inline)
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
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
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
                goal: detour.map { ($0.latitude, $0.longitude) },
                guidePlaces: guide.places,
                picked: guide.picked,
                onTapPlace: { guide.picked = $0 },
                legs: result?.legs ?? [],
                here: here
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
