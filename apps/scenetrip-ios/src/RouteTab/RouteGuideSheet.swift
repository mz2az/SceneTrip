import CoreLocation
import SceneApiClient
import SwiftUI

/// 여행 가이드 대화창 (MZ2AZ-223).
///
/// 프로토타입 v6 의 「떠 있는 챗봇」을 옮긴 것이다. 지도 오른쪽 아래 동그란 단추를
/// 누르면 열리고, 그 안에서 말로 주변 장소를 묻는다.
///
/// ## 반쯤 올라온다
///
/// `.medium` 이라 **뒤로 지도가 보인다.** 「이 근처 한식집」을 물었을 때 답이 목록으로만
/// 오면 그게 어디쯤인지 알 수 없다 — 찾은 곳이 지도에 함께 찍혀야 뜻이 있다.
/// 검색·장바구니 시트와 같은 규칙이다.
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

    /// 장소를 코스에 담는다. 촬영지가 아니라 편의시설이므로 **직접 찍은 핀**으로 넣는다.
    var onAdd: (PlaceSummary) -> Void = { _ in }

    /// 「여기로 길찾기」. 여행 중 화면만 준다 — 주면 카드에 버튼이 뜬다.
    var onReroute: ((RouteGuide.Place) -> Void)?

    @Environment(\.dismiss) private var dismiss

    @State private var draft = ""

    private static let examples = [
        "이 근처 한식집 알려줘",
        "300미터 안에 카페 있어?",
        "주변에 편의점 어디 있어",
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
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
            .navigationTitle("여행 가이드")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.regularMaterial)
        // **시트가 떠 있어도 지도를 만질 수 있다.** 이것이 없으면 지도를 누르는
        // 순간 시트가 내려가서, 고양이 하나 눌러 보고는 챗봇을 다시 열어야 했다
        // (2026-08-27 사용자 지적). 뒷화면을 흐리게 덮는 것도 이 설정이 없앤다 —
        // 반쯤 올라온 시트의 요점이 「지도와 같이 보는 것」인데 흐리면 뜻이 없다.
        .presentationBackgroundInteraction(.enabled(upThrough: .medium))
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
                    if let failure = session.failure {
                        Label(failure, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(16)
            }
            .onChange(of: session.turns.count) { _, _ in
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
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
                    draft = example
                    send()
                } label: {
                    Text(example)
                        .font(.caption)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Capsule().fill(Color(.systemGray6)))
                }
                .buttonStyle(.plain)
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
                        RoundedRectangle(cornerRadius: 14).fill(
                            turn.role == .user
                                ? AnyShapeStyle(Color.accentColor.opacity(0.14))
                                : AnyShapeStyle(Color(.systemBackground))
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
                    Circle()
                        .fill(isPicked ? Color.red : Color.accentColor)
                        .frame(width: 7, height: 7)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(place.name)
                            .font(.caption.weight(isPicked ? .bold : .medium))
                        Text([place.category, place.address]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    Spacer()
                    if let meters = place.distanceMeters {
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

            Button {
                onAdd(place.asPlaceSummary)
            } label: {
                Image(systemName: "plus.circle")
                    .font(.footnote).foregroundStyle(Color.accentColor)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 2)
    }

    private var thinking: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            // 몇 초 걸리는지 미리 말한다. 안 그러면 멈춘 줄 안다(실측 9~57초).
            Text("찾는 중입니다… 10초쯤 걸립니다")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    // MARK: 입력

    private var composer: some View {
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
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespaces).isEmpty && !session.asking
    }

    private func send() {
        let text = draft
        guard let here else { return }
        draft = ""
        Task { await session.ask(text, here: here, context: context) }
    }
}

extension RouteGuide.Place {
    /// 코스에 담을 수 있는 모양으로 바꾼다.
    ///
    /// **촬영지가 아니라 편의시설이다.** 우리 `place` 표에 없으므로 서버에 `placeId`
    /// 로 보낼 수 없다 — 지도에 직접 찍은 핀과 같은 길로 들어간다(음수 id).
    /// `/pois` 계약이 서면(MZ2AZ-284) `poiId` 로 제대로 보낼 수 있다.
    var asPlaceSummary: PlaceSummary {
        RouteMock.pinnedPlace(
            name: name,
            category: category ?? "음식점·카페",
            lat: latitude,
            lng: longitude
        )
    }
}
