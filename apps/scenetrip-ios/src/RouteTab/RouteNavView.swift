import CoreLocation
import NMapsMap
import SceneApiClient
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
/// ## 값은 서버가 준다
///
/// `POST /navigation/next-leg` 가 섰다(MZ2AZ-296). 노선·시간·요금·경로선은 전부
/// 계약 응답(`NextLeg`)에서 오고, 이 화면은 그것을 **조립해서 보여 주기만** 한다 —
/// 표시 문구는 앱이, 재료는 서버가(ADR 0010). 안 될 때는 계약 응답별로 다르게
/// 말한다(`RouteNavFailure`). `legs` 가 비면 오류가 아니라 **이미 도착**이다.
struct RouteNavView: View {
    let stop: RouteStop

    /// 그 일차의 코스 전체. 지도에 번호 핀으로 함께 그리고, 가이드가 「2번 주변」을
    /// 알아듣는 재료도 된다. 안 주면 목적지 하나만 안다.
    var dayStops: [RouteStop] = []

    /// 이 코스의 서버 id. **방문 스탬프를 남기는 데 쓴다** — 없으면(저장 전 코스)
    /// 스탬프 없이 화면만 닫힌다.
    var courseId: Int64?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var locator = RouteLocator()
    @State var arrived = false

    /// 방문 스탬프가 찍혔다는 알림. 잠깐 보였다 사라진다.
    @State var stamped = false

    /// 가이드 대화창이 열려 있는가.
    @State private var showGuide = false

    /// 가이드와의 대화. **앱 공용이다** — 계획 화면에서 하던 대화가 여기로
    /// 이어지고, 닫아도 남는다.
    @ObservedObject var guide = RouteGuideSession.shared

    // 갈래 칩 줄(주변 편의시설·성지 토글)은 있었다가 뺐다(2026-08-28 사용자
    // 결정: 「이거 전체를 빼버리자」) — 길찾기의 주인공은 경로다. 성지 번호
    // 핀은 토글 없이 늘 그린다. 주변 편의시설은 챗봇이 찾아 줄 때만 나온다.

    /// 지도에서 누른 성지. 장면 설명 카드가 뜬다.
    @State var pickedStop: RouteStop?

    /// **즉석에서 갈아탄 목적지.** 가이드가 찾아 준 가게로 「여기로 길찾기」를 누르면
    /// 원래 촬영지 대신 여기로 안내한다 — 걷다가 배가 고프면 목적지가 바뀌는 것이
    /// 내비게이션이다. `nil` 이면 원래 목적지(`stop`)다.
    @State var detour: RouteGuide.Place?

    /// 「현재위치로」 단추가 눌린 횟수. 지도에 넘겨 카메라를 내 자리로 되돌린다.
    @State private var recenterTick = 0

    /// 서버가 준 안내. 아직 안 왔으면 nil 이다.
    @State var result: RouteNavResult?
    /// 안 된 이유. 결과가 오면 nil 로 돌아간다.
    @State var failure: RouteNavFailure?
    @State var asking = false

    /// 지금 위치. 아직 못 받았으면 nil 이고, 그때 지도는 파란 점을 그리지 않는다 —
    /// **없는 위치를 지어내 찍지 않는다.** 엉뚱한 자리에 내가 있다고 하는 것이
    /// 아무 표시도 없는 것보다 나쁘다.
    var here: (latitude: Double, longitude: Double)? {
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
        // 가이드는 시트가 아니라 **오른쪽 서랍**이다 — 편집 화면과 같은 몸짓
        // (2026-08-28 사용자 요청). 지도·구간 목록이 계속 보인다.
        .guidePanel(isOpen: showGuide) {
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
                onReroute: { reroute(to: $0) },
                onClose: { showGuide = false }
            )
        }
        // 위치가 오면 그때 부른다. **누를 때만 부르는 것이 비용 통제 장치**이고,
        // 이 화면 자체가 「길찾기」를 누른 결과라 여기서 한 번만 부르면 된다.
        .onChange(of: locator.state) { _, state in
            guard case let .found(latitude, longitude) = state else { return }
            Task { await load(from: latitude, longitude: longitude) }
            autoStampIfArrived((latitude, longitude))
        }
        .overlay(alignment: .top) {
            if stamped {
                Label("방문 스탬프를 찍었어요!", systemImage: "checkmark.seal.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(Capsule().fill(Color(PinImage.deep)))
                    .padding(.top, 54)
                    .task {
                        try? await Task.sleep(for: .seconds(2.5))
                        withAnimation { stamped = false }
                    }
            }
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

    // MARK: 지도

    private var map: some View {
        ZStack(alignment: .bottomTrailing) {
            RouteNavMapView(
                stop: stop,
                dayStops: dayStops,
                onTapStop: { tapped in
                    pickedStop = tapped
                    guide.picked = nil // 카드는 한 장만
                },
                goal: detour.map { ($0.latitude, $0.longitude) },
                guidePlaces: navGuidePlaces,
                picked: guide.picked,
                onTapPlace: { tapped in
                    guide.picked = tapped
                    pickedStop = nil // 카드는 한 장만
                },
                legs: result?.legs ?? [],
                here: here,
                recenterTick: recenterTick
            )
            .frame(height: 300)

            VStack(spacing: 8) {
                // 현재위치로 **돌아가는** 단추. 길찾기에서 내 자리 파문은 늘 떠
                // 있으므로 토글이 아니라, 지도를 밀다가 화면을 그리로 되돌려
                // 확대해 들어가는 일만 한다(2026-08-28 사용자 결정). 위치를 아직
                // 못 받았으면 누를 것이 없으니 숨긴다.
                if here != nil {
                    Button {
                        recenterTick += 1
                    } label: {
                        Image(systemName: "location.fill")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(Color(PinImage.deep))
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(.white))
                            .overlay(Circle().strokeBorder(
                                Color(PinImage.light).opacity(0.5), lineWidth: 1.5
                            ))
                            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
                    }
                    .buttonStyle(.plain)
                }

                // 챗봇은 여행 중에도 늘 손에 닿는 자리에 있다 — 길을 잃었을 때 물을
                // 상대가 화면을 나가야 나온다면 아무도 못 쓴다. **접힌 동그라미**라
                // 지도를 거의 가리지 않고, 계획 화면과 같은 모양이다.
                RouteGuideChip { showGuide = true }
            }
            .padding(12)
        }
    }

    // MARK: 요약

    private var summary: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let result, result.legs.isEmpty {
                // 출발지와 목적지가 같다 — 계약이 `legs: []` 로 말하는 「이미 도착」.
                // 오류가 아니므로 경고 아이콘을 쓰지 않는다.
                Label("이미 목적지에 있어요", systemImage: "checkmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else if let result {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(RouteFormat.minutes(result.totalMinutes))
                        .font(.title2.weight(.bold))
                    Text(result.summaryLine)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            } else if let failure {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Label(failure.message, systemImage: "exclamationmark.triangle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    // 다시 불러서 달라질 수 있는 것에만 단추를 둔다(`canRetry`).
                    if failure.canRetry {
                        Spacer(minLength: 0)
                        Button("다시 시도") { retry() }
                            .font(.subheadline.weight(.semibold))
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                    }
                }
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
            markVisited()
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
