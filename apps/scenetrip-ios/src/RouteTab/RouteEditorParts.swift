import SceneApiClient
import SwiftUI

// 편집 화면의 부품 — 목록 행과 시트 넷.
//
// `RouteEditorView.swift` 에서 떼어 냈다. 검색 탭이 `SearchTabOverlays.swift` 로
// 나눈 것과 같은 이유다: 한 타입의 본문이 길어지면 린트가 막고(swiftlint
// `type_body_length`), 그 한도는 "한 화면에 담기는 만큼만" 이라는 뜻이다. 화면 뼈대와
// 상태는 그쪽에, 눌렀을 때 올라오는 것들은 이쪽에 둔다.

// MARK: - 목록 행

/// 코스에 담긴 장소 한 줄.
///
/// 번호 배지는 검색 탭의 `PlaceRow` 와 **같은 색·같은 규칙**이다 — 목록의 N번이 지도의
/// N번이고, 그 짝을 색으로도 잇는다.
struct RouteStopRow: View {
    let stop: RouteStop
    let number: Int

    /// 다음 장소까지의 **직선거리.** 소요 시간은 없다 — 8/11 회의 2부 확정.
    let nextKilometers: Double?

    /// 여행 중인가. 「길찾기」는 그때만 눌린다(잠긴 채로는 늘 보인다).
    let running: Bool

    /// 지금 지도가 보고 있는 장소인가. 골라 둔 것을 목록에서도 알 수 있어야 한다.
    var isFocused = false

    /// 이 장소가 나온 작품들. **바깥에서 넣어 준다.**
    ///
    /// 계약의 `CourseItem` 에는 작품이 없다 — `placeId`·`name`·`address` 만 온다
    /// (2026-08-25 실측). 그래서 `stop.place.contents` 를 보면 늘 비어 있었고 이
    /// 줄이 아예 안 그려졌다. 화면 쪽에서 `RouteStore.places` 로 되짚어 넣는다.
    var works: String = ""

    /// 고정 단추를 이 줄에 다는가. **첫 줄과 마지막 줄에만** 단다.
    ///
    /// 앞서 「출발 고정」·「도착 고정」이 최적화 옆 도구 줄에 있었는데, 그 자리에서는
    /// **무엇이 고정되는지가 안 보인다**(2026-08-25 사용자 지적). 고정은 「어느 줄을
    /// 붙들어 두는가」의 이야기라 그 줄 옆에 있어야 뜻이 통한다.
    var pinKind: PinKind?

    /// 그 고정이 지금 켜져 있는가.
    var isPinned = false

    enum PinKind {
        case start
        case end

        var label: String {
            self == .start ? "출발" : "도착"
        }

        var symbol: String {
            self == .start ? "flag" : "flag.checkered"
        }
    }

    let onStay: () -> Void
    let onDirections: () -> Void

    /// 행을 눌렀다. 지도를 이 장소로 옮긴다.
    var onFocus: () -> Void = {}

    /// 고정 단추를 눌렀다.
    var onTogglePin: () -> Void = {}

    /// 잠긴 「길찾기」의 바탕. 챗봇과 같은 하늘→보라를 **아주 옅게** 깐다 —
    /// 진하게 깔면 눌러도 되는 것처럼 보이고, 회색으로 두면 그냥 꺼진 버튼이 된다.
    private var directionsFill: AnyShapeStyle {
        running
            ? AnyShapeStyle(Color.accentColor.opacity(0.12))
            : AnyShapeStyle(LinearGradient(
                colors: [Color(PinImage.light).opacity(0.18), Color(PinImage.deep).opacity(0.18)],
                startPoint: .leading,
                endPoint: .trailing
            ))
    }

    /// 잠긴 글자색. 핀 보라를 쓰되 흐리게 — 읽히되 「지금은 못 누른다」로 보여야 한다.
    private var directionsTint: some ShapeStyle {
        Color(PinImage.deep).opacity(0.55)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text("\(number)")
                    .font(.caption2.weight(.heavy))
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(
                        Circle().fill(
                            LinearGradient(
                                colors: [Color(PinImage.light), Color(PinImage.deep)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                    )

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(stop.place.name).font(.subheadline.weight(.semibold))
                        // 직접 찍은 핀은 우리 데이터에 없는 곳이라 표시를 남긴다.
                        if stop.isPinned {
                            Text("내가 찍은 곳")
                                .font(.caption2)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(Capsule().fill(Color(.systemGray5)))
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text([stop.place.type, stop.place.address]
                        .compactMap { $0 }.joined(separator: " · "))
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)

                    // **어느 작품에 나온 곳인가.** 이 앱에 오는 이유가 그것이라
                    // 유형·주소보다 중요한 줄이다 — 「북촌한옥마을」만 봐서는 왜
                    // 이 코스에 들어왔는지 알 수 없다(2026-08-25 사용자 요청).
                    if !works.isEmpty {
                        HStack(spacing: 4) {
                            Image(systemName: "film")
                                .font(.system(size: 9))
                            Text(works)
                                .font(.caption2.weight(.medium))
                                .lineLimit(1)
                        }
                        .foregroundStyle(Color(PinImage.deep))
                    }
                }
                Spacer()

                Button(action: onStay) {
                    Text(stop.stayLabel)
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 9).padding(.vertical, 5)
                        .background(Capsule().fill(Color.accentColor.opacity(0.12)))
                }
                .buttonStyle(.plain)

                // 끌 수 있다는 것을 알리는 그림. 편집 모드를 켜지 않으므로 iOS 가
                // 손잡이를 그려 주지 않는다 — 없으면 길게 눌러 끌 수 있다는 것을
                // 아무도 모른다.
                Image(systemName: "line.3.horizontal")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            // **이름 줄을 누르면 지도가 그리로 간다.** 아래 버튼(체류 시간·길찾기)은
            // 각자 제 일을 하므로 이 제스처를 윗줄에만 붙인다 — 행 전체에 붙이면
            // 버튼을 누를 때도 지도가 함께 움직인다.
            .contentShape(.rect)
            .onTapGesture(perform: onFocus)

            // 「길찾기」가 이 행의 주된 동작이다. 전에는 캡션 크기의 텍스트 줄이었는데,
            // **누를 때만 API 를 부르는** 구조에서 이 버튼이 곧 호출 지점이라 누르는
            // 것으로 보여야 한다. 애플 최소 터치 크기(44)도 지킨다 — 텍스트 줄은
            // 15pt 밖에 안 됐다.
            //
            // **여행 전에는 잠긴 채로 보인다.** 앞서 여행 중에만 그렸는데, 사용자가
            // 「길찾기 버튼이 없다」고 했다(2026-08-24) — 없는 것과 잠긴 것은 다르다.
            // 없으면 기능 자체를 모르고, 잠겨 있으면 **어떻게 열리는지**를 안다.
            // 잠그는 이유는 돈이다(계획서 course-api.md §7, 8/11 회의) — 계획 단계는
            // 바깥 API 를 부르지 않는다.
            HStack(spacing: 10) {
                Button(action: onDirections) {
                    HStack(spacing: 6) {
                        Image(systemName: running ? "location.north.fill" : "lock.fill")
                            .font(.caption2.weight(.semibold))
                        Text("길찾기")
                            .font(.caption.weight(.semibold))
                        // 잠겨 있을 때만 반짝임을 붙인다 — 「코스를 시작하면 열리는
                        // 특별한 것」이라는 뜻이고, 챗봇 버튼과 같은 기호를 써서
                        // 같은 결의 기능임을 알린다.
                        if !running {
                            Image(systemName: "sparkles")
                                .font(.caption2)
                        }
                    }
                    // 고정 단추와 **같은 높이**여야 한 줄로 보인다. 앞서 44 였는데
                    // 옆의 30 짜리와 나란히 서니 둘이 어긋나 보였다(2026-08-25).
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(Capsule().fill(directionsFill))
                    .foregroundStyle(running ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(directionsTint))
                }
                .buttonStyle(.plain)
                .disabled(!running)

                // 고정 단추는 **첫 줄과 마지막 줄에만** 붙는다. 「이 줄을 붙들어
                // 둔다」는 뜻이라 그 줄에 있어야 한다.
                if let pinKind {
                    Button(action: onTogglePin) {
                        HStack(spacing: 4) {
                            Image(systemName: isPinned ? "\(pinKind.symbol).fill" : pinKind.symbol)
                                .font(.caption2)
                            Text("\(pinKind.label) 고정").font(.caption2.weight(.medium))
                        }
                        .padding(.horizontal, 9)
                        .frame(height: 30)
                        .background(Capsule().fill(
                            isPinned ? Color.accentColor : Color(.systemGray6)
                        ))
                        .foregroundStyle(isPinned ? Color.white : Color.secondary)
                    }
                    .buttonStyle(.plain)
                }

                if let nextKilometers {
                    Label(RouteFormat.kilometers(nextKilometers), systemImage: "arrow.down")
                        .font(.caption)
                        .foregroundStyle(running ? .secondary : .tertiary)
                }
            }
            .padding(.leading, 34)
            .padding(.top, 2)
        }
        .padding(.vertical, 4)
        // 지도가 보고 있는 행에 옅은 바탕을 깐다. 지도의 핀과 목록의 줄이 같은
        // 것을 가리킨다는 것을 눈으로 잇는다.
        .listRowBackground(isFocused ? Color.accentColor.opacity(0.07) : Color(.systemBackground))
    }
}

// MARK: - 장바구니에서 담기

/// 장바구니 시트. **담을 장소는 장바구니에서 고른다** — 검색 탭에서 담은 것이 여기로
/// 이어진다.
///
/// 서버가 꺼져 있거나 담은 것이 없으면 예시 값을 대신 보여 주고 그 사실을 적는다.
/// 빈 목록만 뜨면 데모를 보는 사람이 "이 화면이 고장 났나" 부터 의심하게 된다.
struct RouteCartSheet: View {
    @ObservedObject var cart: CartStore

    /// **이미 코스에 담긴 촬영지.** 검색 시트와 같은 규칙이다(`RouteSearchSheet`).
    var taken: Set<Int64> = []

    /// 고르는 대로 바깥에 알린다 — 담기 전에 지도에 빨간 고양이로 뜬다.
    var onPreview: ([PlaceSummary]) -> Void = { _ in }

    let onPick: ([PlaceSummary]) -> Void

    @EnvironmentObject private var store: RouteStore

    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<Int64> = []

    private var content: (places: [PlaceSummary], isSample: Bool) {
        let real = RouteStore.cartPlaces(cart.items)
        // 장바구니가 비면 서버의 인기 장소를 대신 보여 준다. **목 장소를 쓰면 안 된다** —
        // 담는 순간 서버가 외래키 위반으로 코스 저장을 통째로 거부한다.
        return real.isSample ? (store.cartSample(), true) : real
    }

    var body: some View {
        NavigationStack {
            List {
                if content.isSample {
                    Text("장바구니가 비어 인기 장소를 보여 줍니다")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(content.places, id: \.id) { place in
                    row(place)
                }
            }
            .listStyle(.plain)
            .navigationTitle("장바구니에서 담기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("담기 \(picked.count)") {
                        onPick(content.places.filter { picked.contains($0.id) })
                        dismiss()
                    }
                    .disabled(picked.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.regularMaterial)
    }

    private func row(_ place: PlaceSummary) -> some View {
        HStack(spacing: 12) {
            RemoteImage(url: place.imageUrl, symbol: "mappin.and.ellipse")
                .frame(width: 44, height: 44)
                .clipShape(.rect(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 2) {
                Text(place.name).font(.subheadline.weight(.semibold))
                Text(place.address ?? place.type ?? "")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            // 장소는 플러스·체크다 (8/11 회의: 작품에는 하트, 장소에는 플러스).
            // **이미 코스에 있는 것은 흐린 체크**다 — 지금 고른 것(진한 체크)과
            // 갈라 보여야 왜 안 눌리는지 안다.
            if taken.contains(place.id) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.accentColor.opacity(0.45))
            } else {
                Image(systemName: picked.contains(place.id) ? "checkmark.circle.fill" : "plus.circle")
                    .foregroundStyle(picked.contains(place.id) ? Color.accentColor : .secondary)
            }
        }
        .opacity(taken.contains(place.id) ? 0.5 : 1)
        .contentShape(.rect)
        .onTapGesture {
            guard !taken.contains(place.id) else { return }
            if picked.contains(place.id) {
                picked.remove(place.id)
            } else {
                picked.insert(place.id)
            }
            onPreview(content.places.filter { picked.contains($0.id) })
        }
    }
}

// MARK: - 핀 찍기

/// 지도를 눌러 찍은 자리에 이름과 갈래만 붙인다.
///
/// 회의에서 이 기능이 나온 맥락은 숙소다 — *"핀 찍는 건 있어야 될 것 같은데.
/// 저기 (숙소) 넣고 싶은 것도 넣을 수 있게"*. 그래서 갈래 목록 맨 앞이 숙소다.
struct RoutePinSheet: View {
    let pin: RoutePin
    let onDone: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var category = RoutePinSheet.categories[0]

    /// 검색 탭의 카테고리 묶음을 그대로 쓰고 숙소만 앞에 더한다 — 같은 앱에서 갈래
    /// 이름이 화면마다 다르면 나중에 서버로 보낼 때 맞출 수 없다.
    static let categories = ["숙소"] + CategoryChip.groups.map(\.name)

    var body: some View {
        NavigationStack {
            Form {
                Section("이름") {
                    TextField("예: 오늘 묵을 숙소", text: $name)
                }
                Section("갈래") {
                    Picker("갈래", selection: $category) {
                        ForEach(Self.categories, id: \.self) { each in
                            Text(each).tag(each)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section {
                    Text(String(format: "위도 %.5f · 경도 %.5f", pin.latitude, pin.longitude))
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("이 자리에 추가")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("추가") {
                        onDone(name.isEmpty ? "이름 없는 장소" : name, category)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - 체류 시간

/// 체류 시간 고르기. 기본 30분은 8/11 회의에서 확정된 값이다.
struct RouteStaySheet: View {
    let stop: RouteStop
    let onPick: (Int) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(RouteStop.stayOptions, id: \.self) { minutes in
                Button {
                    onPick(minutes)
                    dismiss()
                } label: {
                    HStack {
                        Text(RouteFormat.minutes(minutes))
                        Spacer()
                        if minutes == stop.stayMinutes {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
            .navigationTitle("얼마나 머무를까요?")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
    }
}
