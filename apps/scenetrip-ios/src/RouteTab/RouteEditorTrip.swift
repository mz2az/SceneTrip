import SceneApiClient
import SwiftUI

/// 편집 화면의 **여행 안내** — 안내 띠, 아래 단추 줄, 스탬프, 번호 핀 카드
/// (계획 trip-mode.md §8, 2026-09-03 · main 이식 MZ2AZ-307). 경로는 `TripSession` 이 백엔드
/// 계약으로 받는다.
///
/// 1단계는 「코스 시작」이 별도 길찾기 창(`RouteNavView`)을 띄웠다. 이제 **이 화면의
/// 지도**가 곧 여행 지도다 — 일차 탭·번호 핀·직선 계획선 위에 실제 경로가 얹히고,
/// 도착하면 그 핀이 발바닥으로 바뀐다. 상태는 `TripSession` 이 든다.
extension RouteEditorView {
    /// 이 성지로 안내를 켠다. 번호는 **그 일차 안의 순서**다(지도·목록의 번호와 같다).
    func startTrip(to stop: RouteStop) {
        let number = (stops.firstIndex { $0.id == stop.id } ?? 0) + 1
        focusedStop = nil
        pickedStop = nil
        guide.picked = nil
        // 계약이 목적지를 코스 항목으로 받으므로 코스의 서버 id 도 함께 준다(저장 전이면 nil).
        trip.start(to: stop, number: number, courseId: course.serverId)
    }

    /// 지금 일차에서 **아직 안 간 첫 곳.** 도착 뒤 「다음 · N번으로」와 「코스 시작」이 이것을 본다.
    var nextUnvisited: (number: Int, stop: RouteStop)? {
        stops.firstIndex { !$0.visited }.map { ($0 + 1, stops[$0]) }
    }

    /// 지도에 「내 자리 → 여기」 직선을 미리 그을 곳(2026-09-03 사용자 결정).
    /// 가는 중인데 경로가 아직 안 왔으면 목적지, 도착해 다음을 고르기 전이면 다음 곳.
    /// 실제 경로가 오면 nil — 직선은 사라지고 세세한 길만 남는다.
    var previewTarget: RouteStop? {
        switch trip.phase {
        case .guiding: trip.result == nil ? trip.target : nil
        case .arrived: nextUnvisited?.stop
        case .idle: nil
        }
    }

    /// 스탬프가 찍혔다 — 코스 상태와 서버 둘 다에 「다녀옴」. 시각은 서버가 찍는다.
    /// 저장 전 코스(서버 id 없음)는 화면에만 남는다.
    func markVisited(_ stop: RouteStop) {
        markVisitedLocally(stop)
        guard let courseId = course.serverId, let itemId = stop.serverItemId else { return }
        Task {
            try? await CoursesAPI.updateCourseItemVisit(
                xDeviceId: InstallIdentity.current,
                courseId: courseId, itemId: itemId,
                visitUpdate: VisitUpdate(visited: true)
            )
        }
    }

    // MARK: 안내 띠

    /// 일정 시트 맨 위 — **가는 중에만.** 어디로 가는 중인지, 얼마나 걸리는지, 어떻게 가는지.
    /// 별도 창의 머리줄·요약·구간 목록을 **한 띠**로 줄였다. 지도가 주인공이다.
    /// 도착하면 이 띠는 내려가고 `arrivalNotice` 가 지도 위에 뜬다(2026-09-03 사용자 결정 —
    /// 경로는 아래에, 알림만 위에. 지도를 카드로 다 가리면 안 된다).
    @ViewBuilder
    var tripBanner: some View {
        if let target = trip.target, trip.phase == .guiding {
            VStack(alignment: .leading, spacing: 6) {
                tripHeadline(target, symbol: "location.fill")
                tripDetail
            }
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 6)
            .background(Color(PinImage.light).opacity(0.10))
        }
    }

    /// 지도 맨 위의 **도착 알림 카드** — 도착한 뒤 「다음은 사람이 고른다」를 말한다. 시트 안에
    /// 두니 성지 목록이 좁아져 넘기기 힘들었다(2026-09-03 사용자 요청) — 지도 위에 떠서
    /// 목록 자리를 먹지 않는다.
    @ViewBuilder
    var arrivalNotice: some View {
        if let target = trip.target, trip.phase == .arrived {
            VStack(alignment: .leading, spacing: 6) {
                tripHeadline(target, symbol: "checkmark.seal.fill")
                tripDetail
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color(.systemBackground))
                    .shadow(color: .black.opacity(0.14), radius: 8, y: 3)
            )
            .padding(.horizontal, 12).padding(.top, 10)
            .background(GeometryReader { geo in
                Color.clear.preference(key: TripBannerHeightKey.self, value: geo.size.height)
            })
            .onPreferenceChange(TripBannerHeightKey.self) { tripBannerHeight = $0 }
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }

    private func tripHeadline(_ target: RouteStop, symbol: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Color(PinImage.deep))
            Text(tripTitle(target)).font(.subheadline.weight(.bold)).lineLimit(1)
            Spacer()
            Button {
                trip.end()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("안내 끝")
        }
    }
}

/// 도착 알림 카드의 높이를 위로 알린다 — 지도 오른쪽 위 단추가 카드 밑으로 내려가게.
struct TripBannerHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

extension RouteEditorView {
    private func tripTitle(_ target: RouteStop) -> String {
        let number = stops.firstIndex { $0.id == target.id }.map { "\($0 + 1)번 " } ?? ""
        return trip.phase == .arrived
            ? "\(number)\(target.place.name) 도착"
            : "\(number)\(target.place.name)로 가는 중"
    }

    @ViewBuilder
    private var tripDetail: some View {
        if trip.phase == .arrived {
            // **다음으로 넘기지 않는다.** 둘러볼 시간은 사람의 것이다.
            Text(nextUnvisited.map { "둘러본 뒤 아래 「다음 · \($0.number)번으로」를 누르세요" }
                ?? "오늘 일정을 모두 돌았어요")
                .font(.caption).foregroundStyle(.secondary)
        } else if let result = trip.result {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(RouteFormat.minutes(result.totalMinutes)).font(.title3.weight(.bold))
                Text(result.summaryLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            // 구간 — 도보·대중교통 조각을 한 줄로. 화면을 밀면 다 보인다.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(result.legs) { leg in
                        HStack(spacing: 4) {
                            Image(systemName: leg.mode.symbol).font(.system(size: 10, weight: .bold))
                            // 탈것은 노선까지 — 「간선 150 · 서울신문사 → 혜화역2번출구 · 10정거장 · 19분」.
                            Text(leg.mode.isVehicle
                                ? [leg.title, leg.detail].filter { !$0.isEmpty }.joined(separator: " · ")
                                : (leg.detail.isEmpty ? leg.title : leg.detail))
                                .font(.caption2).lineLimit(1)
                            if leg.hasStairs {
                                Image(systemName: "stairs").font(.system(size: 9)).foregroundStyle(Color(.systemRed))
                            }
                        }
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(
                            leg.mode == .walk ? Color(.systemGray5)
                                : leg.mode == .subway ? Color(.systemBlue).opacity(0.16)
                                : Color(.systemGreen).opacity(0.18)
                        ))
                    }
                }
            }
            Text(trip.dwellHint).font(.caption2).foregroundStyle(.tertiary)
        } else if let failure = trip.failure {
            // 계약 응답별 문구(`RouteNavFailure`). 다시 불러 달라질 수 있는 것에만 단추.
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Label(failure.message, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.secondary)
                if failure.canRetry {
                    Button("다시 시도") { trip.retry() }
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.bordered).controlSize(.mini)
                }
            }
        } else if trip.asking {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text("길을 찾는 중입니다").font(.caption).foregroundStyle(.secondary)
            }
        } else if trip.here == nil {
            Label("현재 위치를 찾는 중입니다", systemImage: "location.slash")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    // MARK: 아래 단추 줄

    var bottomBar: some View {
        Group {
            if trip.isActive {
                // 안내 중에는 이 줄이 **안내의 것**이다 — 도착함 / 다음으로 / 완료.
                tripControls
            } else {
                planControls
            }
        }
        // 줄을 낮게 잡는다 — 이 줄이 먹는 만큼 일정이 좁아진다(2026-08-28
        // 사용자 요청: 단추 위아래와 글자를 줄여 아래쪽을 넓힌다).
        .font(.subheadline)
        .controlSize(.regular)
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Color(.systemBackground))
    }

    var planControls: some View {
        HStack(spacing: 10) {
            // 「코스 시작」은 저장된 코스에만 있다 — 아직 만들지도 않은 일정을 여행
            // 중으로 만들 수는 없다.
            if !isNew {
                // 여행 중이면 **다음 성지로 길찾기가 주된 동작**이다 — 경로는 이 화면의
                // 지도에 그려진다(2026-09-03, 계획 trip-mode.md §8). 다 돌았으면 없다.
                if course.isRunning, let next = nextUnvisited {
                    Button {
                        startTrip(to: next.stop)
                    } label: {
                        Text("\(next.number)번으로 길찾기").lineLimit(1)
                    }
                    .buttonStyle(.borderedProminent)
                }
                Button(course.isRunning ? "여행 종료" : "코스 시작") {
                    course.isRunning.toggle()
                    // 상태만 바꾼다 — 코스 내용을 함께 덮어쓰면 편집 중이던 것이
                    // 저장돼 버려 「시작」이 「저장」을 겸하게 된다.
                    // 지금 보고 있는 일차에서 시작한다 — 서버가 `currentDayNo` 를
                    // 요구하고, 1일차를 지나 보고 있다면 그 일차가 맞다.
                    Task { await store.setRunning(course, course.isRunning, dayNo: dayIndex + 1) }
                    // **시작하면 바로 첫 성지로 길찾기.** 여기서 앱이 흐름을 이어받는다
                    // (계획 trip-mode.md §2·§8) — 경로는 이 지도에, 도착은 머무름으로.
                    if course.isRunning, let first = nextUnvisited?.stop ?? stops.first {
                        startTrip(to: first)
                    } else {
                        trip.end()
                    }
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
    }

    // MARK: 아래 단추 줄 (안내 중)

    /// 안내 중의 아래 줄 — 가는 중이면 「여기 도착함」(탈출구), 도착했으면 「다음 · N번으로」.
    /// 「저장하고 닫기」는 이때 없다 — 저장은 위 「저장」이 한다.
    var tripControls: some View {
        HStack(spacing: 10) {
            Button("안내 끝") { trip.end() }
                .buttonStyle(.bordered)
            if trip.phase == .arrived {
                if let next = nextUnvisited {
                    Button {
                        startTrip(to: next.stop)
                    } label: {
                        Text("다음 · \(next.number)번 \(next.stop.place.name)로 길찾기")
                            .lineLimit(1).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Label("오늘 일정을 모두 돌았어요", systemImage: "checkmark.seal.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color(PinImage.deep))
                        .frame(maxWidth: .infinity)
                }
            } else {
                // 머무를 시간이 없거나 GPS 가 튈 때의 탈출구. 저절로 찍힐 때와 같은 길이다.
                Button {
                    trip.arriveNow()
                } label: {
                    Text("여기 도착함").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(trip.stamped)
            }
        }
    }

    // MARK: 스탬프와 성지 카드

    /// 도착 스탬프 — 화면 가운데 발바닥이 쾅. 연출이 끝나도 **다음으로 넘어가지 않는다.**
    @ViewBuilder
    var stampOverlay: some View {
        if trip.stamped, let target = trip.target {
            ZStack {
                Color.black.opacity(0.25).ignoresSafeArea()
                PawStampOverlay(
                    title: stops.firstIndex { $0.id == target.id }.map { "\($0 + 1)번 성지 도착!" } ?? "도착!",
                    subtitle: target.place.name,
                    onDone: { withAnimation { trip.stampDone() } }
                )
            }
            .transition(.opacity)
        }
    }

    /// 번호 핀을 눌렀을 때 — 장면 설명 + (여행 중이면) 여기로 길찾기.
    @ViewBuilder
    var stopCardOverlay: some View {
        if let tapped = pickedStop, !showGuide, guide.picked == nil {
            RouteStopCard(
                stop: tapped,
                onReroute: course.isRunning && trip.target?.id != tapped.id
                    ? { startTrip(to: tapped) } : nil,
                onClose: { pickedStop = nil }
            )
            .padding(.horizontal, 12)
            .padding(.bottom, 90)
        }
    }
}
