import CoreLocation
import SceneApiClient
import SwiftUI

/// 여행 가이드 대화창 (MZ2AZ-223).
///
/// 프로토타입 v6 의 「떠 있는 챗봇」을 옮긴 것이다. 지도 오른쪽 아래 동그란 단추를
/// 누르면 열리고, 그 안에서 말로 주변 장소를 묻는다.
///
/// ## 오른쪽에서 나오는 고정 창
///
/// 바텀시트였다가 **오른쪽에서 미끄러져 나오는 고정 크기 창**으로 바꿨다
/// (2026-08-28 사용자 요청 — 프로토타입의 떠 있는 챗봇과 같은 몸짓). 창이
/// 화면을 다 덮지 않아 **지도가 계속 보인다** — 「이 근처 한식집」의 답이
/// 어디쯤인지는 지도가 말해 준다. 띄우는 쪽이 `guidePanel`(이 파일 아래)로
/// 감싸 오른쪽에 붙인다.
///
/// ## 부른 도구를 보여 준다
///
/// 프로토타입이 정한 규칙이고 그대로 가져왔다 — *"답만 보면 그럴듯한 헛소리를 걸러
/// 낼 수 없다."* 모델이 `poi_nearby` 를 안 부르고 답했다면 그 답은 지어낸 것이다.
struct RouteGuideSheet: View {
    /// **대화는 시트 밖에서 든다.** 닫아도 남아야 하기 때문이다 — 자세한 것은
    /// `RouteGuideSession` 머리말.
    @ObservedObject var session: RouteGuideSession

    /// 어디를 기준으로 「주변」인가.
    let here: CLLocationCoordinate2D?

    /// 코스에 담긴 지점. 모델이 「2번 주변」을 알아듣는 재료다.
    var context: RouteGuide.Context?

    /// 장소를 코스에 담는다. 무엇으로 담을지는 띄운 쪽이 정한다 — 촬영지와 같은 곳인 편의시설은
    /// 촬영지로, 그 밖은 **직접 찍은 핀**으로(`RouteGuide.Place.courseEntry`, MZ2AZ-378).
    var onAdd: (RouteGuide.Place) -> Void = { _ in }

    /// 이미 코스에 있는가. 있으면 ⊕ 대신 체크가 뜬다 — 두 번 담기지 않는다.
    var isAdded: (RouteGuide.Place) -> Bool = { _ in false }

    /// 체크를 한 번 더 눌렀다 — 경로에서 뺀다.
    var onRemove: (RouteGuide.Place) -> Void = { _ in }

    /// 「여기로 길찾기」. 여행 중 화면만 준다 — 주면 카드에 버튼이 뜬다.
    var onReroute: ((RouteGuide.Place) -> Void)?

    /// 한도 안내의 「AI 로 짜기 열기」 — 일정짜기 마법사로 가는 길. 띄운 쪽이 주지 않으면 글만 보인다.
    var onOpenPlanner: (() -> Void)?

    /// X 를 눌렀다. 시트가 아니라 오버레이 창이라 `dismiss` 로는 안 닫힌다 —
    /// 여닫는 상태는 띄운 쪽이 든다.
    var onClose: () -> Void = {}

    @State private var draft = ""

    /// 이 창의 이름표 — 대화 객체가 「떠 있는 창」 을 세는 데 쓴다(`RouteGuideSession.setAttended`).
    @State private var panelId = UUID()

    /// **되는 것만 보여 준다.** 앞서 한식집·카페·편의점 세 줄을 두었는데 셋 다
    /// 제대로 동작하지 않았다(2026-08-27 사용자 확인) — 안 되는 예시는 첫인상에서
    /// 신뢰를 깎는다. poi_nearby 가 확실히 답하는 질문 하나만 남긴다.
    private static let examples = [
        "주변 음식점 알려줘",
    ]

    var body: some View {
        VStack(spacing: 0) {
            header
            if here == nil {
                // 「주변」이 어디인지 모르면 물어볼 수가 없다.
                ContentUnavailableView(
                    "현재 위치를 알 수 없습니다",
                    systemImage: "location.slash",
                    description: Text("위치 권한을 켜면 주변 장소를 찾아 드립니다")
                )
            } else {
                conversation
                composer
            }
        }
        // 창이 떠 있는 동안만 다시 보내기·되묻기를 기다린다 — 닫거나 화면을 떠나면 끊는다(MZ2AZ-366).
        // 편집 화면과 길찾기 화면이 같은 창을 쓰므로 창 자신이 알린다.
        .onAppear { session.setAttended(true, by: panelId) }
        .onDisappear { session.setAttended(false, by: panelId) }
    }

    /// 손수 그린 머리줄. 내비게이션 바를 쓰지 않는 이유는 닫기 단추다 — 바에
    /// 넣으면 시스템이 유리 동그라미를 깔아 크게 그린다(2026-08-27 사용자 지적).
    ///
    /// 아이콘은 X 가 아니라 **창 줄이기**(안쪽으로 모이는 화살)다 — 대화가
    /// 사라지는 게 아니라 오른쪽 동그라미로 **접히는 것**이라서다(2026-08-28
    /// 사용자 요청). 닫는 몸짓도 `guidePanel` 이 오른쪽 아래로 오므라들게 한다.
    private var header: some View {
        ZStack {
            Text("여행 가이드").font(.headline)
            HStack {
                Spacer()
                Button {
                    onClose()
                } label: {
                    Image(systemName: "arrow.down.right.and.arrow.up.left")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 6)
    }

    // MARK: 대화

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if session.isEmpty {
                        starters
                    }
                    ForEach(session.turns) { turn in
                        bubble(turn)
                    }
                    if session.asking {
                        thinking
                    }
                    if let failure = session.failure, !failure.isLimit {
                        failed(failure)
                    }
                    // 한도 안내는 실패 줄과 따로 둔다 — 대화를 지우거나 코스를 바꿔도 잠겨 있는 이유가 보여야 한다.
                    limitNotice
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(16)
            }
            .onChange(of: session.turns.count) { _, _ in
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            // 실패 줄과 「다시 시도」 도 맨 아래에 붙는다 — 대화가 길면 안 보인다.
            .onChange(of: session.failure) { _, failure in
                guard failure != nil else { return }
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: session.limit) { _, limit in
                guard limit != .clear else { return }
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            // 창을 접었다 다시 열면 스크롤이 맨 위에서 시작한다 — 대화가 길면 한도 안내가 화면 밖이고 「보내기」 만
            // 회색이라 왜 잠겼는지 안 보인다. 한도에 걸려 있으면 안내가 있는 맨 아래로 내린다.
            .onAppear {
                guard session.limit != .clear else { return }
                DispatchQueue.main.async { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            // 펼친 줄이 보이게 그 자리로 내려간다 — 맨 아래로 보내면 목록 끝까지
            // 지나쳐 버린다(2026-08-27 사용자 지적: 정동커피를 눌렀는데 목록 끝
            // 아래에 카드가 떠서 딴 가게들을 다 지나야 했다).
            .onChange(of: session.picked?.id) { _, id in
                guard let id else { return }
                withAnimation { proxy.scrollTo("place-\(id)", anchor: .top) }
            }
        }
    }

    private var starters: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("무엇을 도와드릴까요?")
                .font(.subheadline.weight(.semibold))
            Text("지금 있는 자리를 기준으로 주변을 찾아 드립니다.")
                .font(.caption).foregroundStyle(.secondary)

            ForEach(Self.examples, id: \.self) { example in
                Button {
                    draft = tr(example)
                    send()
                } label: {
                    Text(tr(example))
                        .font(.caption)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Capsule().fill(Color(.systemGray6)))
                }
                .buttonStyle(.plain)
                // 한도가 풀리기를 기다리는 동안에는 보낼 수 없다 — 눌러도 입력창을 채우지 않고, 흐리게 보인다.
                .disabled(session.isLimited)
                .opacity(session.isLimited ? 0.4 : 1)
            }
        }
    }

    private func bubble(_ turn: RouteGuide.Turn) -> some View {
        HStack {
            if turn.role == .user {
                Spacer(minLength: 40)
            }
            VStack(alignment: turn.role == .user ? .trailing : .leading, spacing: 6) {
                Text(turn.text)
                    .font(.subheadline)
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(
                        // 사용자는 하늘색, 가이드는 **연보라** — AI 가이드 단추와
                        // 경로선이 쓰는 피노 보라(`PinImage.light`)의 옅은 판이다.
                        // 흰 말풍선은 시트 배경과 구별이 안 됐다(2026-08-27).
                        RoundedRectangle(cornerRadius: 14).fill(
                            turn.role == .user
                                ? AnyShapeStyle(Color.accentColor.opacity(0.14))
                                : AnyShapeStyle(Color(PinImage.light).opacity(0.16))
                        )
                    )

                // 마지막 답에만 붙인다 — 무엇을 근거로 말했는지.
                if turn.role == .assistant, turn.id == session.turns.last?.id {
                    evidence
                }
            }
            if turn.role == .assistant {
                Spacer(minLength: 40)
            }
        }
    }

    @ViewBuilder
    private var evidence: some View {
        if !session.tools.isEmpty || !session.places.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                if !session.tools.isEmpty {
                    // **답만 보면 그럴듯한 헛소리를 못 거른다**(프로토타입 규칙).
                    Label(session.tools.joined(separator: " · "), systemImage: "wrench.and.screwdriver")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                ForEach(session.places) { place in
                    let isPicked = place.id == session.picked?.id
                    // **누른 줄 바로 아래에 펼쳐진다.** 아코디언이다 — 한 줄을
                    // 펼치면 앞서 펼친 것은 접힌다(`picked` 가 하나뿐이라 저절로
                    // 그렇다). 목록 어느 줄이든 펼쳐 볼 수 있다.
                    VStack(alignment: .leading, spacing: 6) {
                        placeRow(place, isPicked: isPicked)
                        if isPicked {
                            RoutePlaceCard(
                                place: place,
                                onAdd: { onAdd(place) },
                                added: isAdded(place),
                                onRemove: { onRemove(place) },
                                onReroute: onReroute.map { fire in { fire(place) } },
                                onClose: { session.picked = nil }
                            )
                        }
                    }
                    .id("place-\(place.id)")
                }
            }
            .padding(.leading, 4)
        }
    }

    private func placeRow(_ place: RouteGuide.Place, isPicked: Bool) -> some View {
        HStack(spacing: 8) {
            // **줄을 누르면 지도에서 그 곳이 빨간 고양이가 된다.**
            // 담는 것과 고르는 것은 다른 일이라 자리를 나눈다 — 앞서
            // 줄 전체가 「담기」라 어디인지 보려다 코스에 들어갔다.
            Button {
                session.picked = isPicked ? nil : place
            } label: {
                HStack(spacing: 8) {
                    // 목록 점도 지도 점과 같은 갈래 색·같은 글리프다 — 그것이 끈이다.
                    // 색만으로는 음식과 숙박이 한 줄 건너 붙어 있을 때 갈리지 않는다.
                    ZStack {
                        Circle().fill(isPicked ? Color.red : RoutePoiTone.of(place.poiGroup))
                        Image(systemName: place.poiSymbol)
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .frame(width: 16, height: 16)
                    VStack(alignment: .leading, spacing: 1) {
                        let label = place.label
                        Text(label.title)
                            .font(.caption.weight(isPicked ? .bold : .medium))
                            .lineLimit(1)
                        // 영어 이름이 없는 가게 — 한글 간판 아래 읽는 법(MZ2AZ-360).
                        if let reading = label.reading {
                            Text(reading)
                                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Text([label.category, label.address]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    Spacer()
                    if let meters = place.shownMeters {
                        Text("\(meters) m")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)

            // 펼칠 수 있다는 표시. 없으면 줄이 눌리는 것을 아무도 모른다.
            Image(systemName: isPicked ? "chevron.up" : "chevron.down")
                .font(.caption2).foregroundStyle(.tertiary)

            if isAdded(place) {
                // 이미 담긴 곳 — 체크가 뜨고, **한 번 더 누르면 뺀다.**
                Button {
                    onRemove(place)
                } label: {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            } else {
                Button {
                    onAdd(place)
                } label: {
                    Image(systemName: "plus.circle")
                        .font(.footnote).foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 2)
    }

    private var thinking: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            if session.pending?.stage == .reconnecting {
                // 첫 요청이 실패해 같은 키로 다시 보내는 중 — 멈춘 것이 아니라는 것을 말한다.
                Text("다시 연결하는 중이에요")
                    .font(.caption).foregroundStyle(.secondary)
            } else if session.pending?.stage == .waitingForLimit {
                // 분당 한도가 풀리기를 기다린다(길면 1 분) — 연결 탓이 아니다.
                Text("요청이 많아 잠시 기다리는 중이에요")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                // 몇 초 걸리는지 미리 말한다. 안 그러면 멈춘 줄 안다(실측 9~57초).
                // 서버가 「처리 중」 이라 다시 묻는 동안에도 이 말이다 — 답은 만들어지고 있다.
                Text("찾는 중입니다… 10초쯤 걸립니다")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    /// 실패 한 줄과, 다시 해 볼 만하면 「다시 시도」.
    ///
    /// **단추는 같은 질문을 같은 멱등 키로 다시 보낸다**(`RouteGuideSession.retry`) — 서버가 이미 답을 만들어
    /// 두었으면 그 답이 오고, 모델을 다시 부르지 않는다. 서버가 키를 모르면 단추가 없다(전과 같다).
    private func failed(_ failure: RouteGuideFailure) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(failure.message, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.orange)
            if session.canRetry {
                retryButton
            }
        }
    }

    // MARK: 입력

    private var composer: some View {
        VStack(spacing: 0) {
            remainingLine
            composerRow
        }
        .background(.thinMaterial)
    }

    private var composerRow: some View {
        HStack(spacing: 8) {
            TextField("주변에 무엇을 찾으세요?", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1 ... 3)
                .padding(.horizontal, 12).padding(.vertical, 9)
                .background(Capsule().fill(Color(.systemGray6)))
                .onSubmit(send)

            Button(action: send) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(canSend ? Color.accentColor : Color.secondary.opacity(0.4))
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .accessibilityLabel("보내기")
            .accessibilityHint(session.isLimited ? tr("한도가 풀리면 다시 보낼 수 있어요") : "")
            .accessibilityIdentifier("guide-send")
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    /// 글은 칠 수 있다 — 한도가 풀리기를 기다리는 동안에도. 보내기만 잠근다.
    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespaces).isEmpty && !session.asking && !session.isLimited
    }

    private func send() {
        let text = draft
        // 잠긴 동안 자판의 「완료」 로 들어오면 친 글을 지우지 않는다.
        guard let here, canSend else { return }
        draft = ""
        Task { await session.ask(text, here: here, context: context) }
    }
}

extension RouteGuide.Place {
    /// **이름 + 좌표만 가진 장소** 모양으로 바꾼다 — 같은 곳인지 견줄 열쇠(`RouteDedupe.key`)에 쓴다.
    ///
    /// 코스에 담을 때는 이것이 아니라 `courseEntry` 를 쓴다 — 촬영지는 `placeId`, 편의시설은 `poiId` 로
    /// 담긴다(MZ2AZ-378 · 380). 이 모양(음수 id)으로 담기는 것은 출처가 없는 장소(코스에서 옮긴 것)뿐이다.
    var asPlaceSummary: PlaceSummary {
        RouteMock.pinnedPlace(
            name: name,
            category: category ?? "음식점·카페",
            lat: latitude,
            lng: longitude
        )
    }
}

/// 가이드 창을 **오른쪽 서랍**으로 붙인다 — 화면 오른쪽 아래에 고정 크기로
/// 떠 있고, 여닫을 때 오른쪽에서 미끄러진다(2026-08-28 사용자 요청). 편집·
/// 길찾기 화면이 같은 몸짓을 쓰도록 한 군데에 둔다.
///
/// 크기는 고정이다 — 시트의 detent 처럼 잡아 늘이는 물건이 아니다. 폭을
/// 화면보다 좁게 둬서 **왼쪽으로 지도가 계속 보인다.**
extension View {
    func guidePanel(isOpen: Bool, @ViewBuilder panel: () -> some View) -> some View {
        overlay(alignment: .bottomTrailing) {
            if isOpen {
                panel()
                    .frame(width: 316, height: 470)
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .shadow(color: .black.opacity(0.22), radius: 14, y: 6)
                    .padding(.trailing, 6)
                    .padding(.bottom, 8)
                    // 접힌 동그라미 **자리 쪽으로** 오므라들며 사라진다 — 창이
                    // 닫히는 게 아니라 동그라미로 접힌다는 몸짓(2026-08-28 사용자
                    // 요청). 동그라미는 이제 화면 오른쪽 아래에 있다(2026-09-16,
                    // `RouteGuideFloatingChip`) — 그쪽으로 오므린다.
                    .transition(.scale(scale: 0.05, anchor: .bottomTrailing)
                        .combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.28), value: isOpen)
    }
}
