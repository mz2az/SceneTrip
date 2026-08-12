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

    /// 여행 중인가. 「여기서 길 찾기」는 그때만 나온다.
    let running: Bool

    let onStay: () -> Void
    let onDirections: () -> Void

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

            if running {
                Button(action: onDirections) {
                    Label("현재 위치 기준 여기서 길 찾기", systemImage: "figure.walk")
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }

            if let nextKilometers {
                Label(RouteFormat.kilometers(nextKilometers), systemImage: "arrow.down")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
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
    let onPick: ([PlaceSummary]) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<Int64> = []

    private var content: (places: [PlaceSummary], isSample: Bool) {
        RouteStore.cartPlaces(cart.items)
    }

    var body: some View {
        NavigationStack {
            List {
                if content.isSample {
                    Text("장바구니가 비어 예시 장소를 보여 줍니다")
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
            Image(systemName: picked.contains(place.id) ? "checkmark.circle.fill" : "plus.circle")
                .foregroundStyle(picked.contains(place.id) ? Color.accentColor : .secondary)
        }
        .contentShape(.rect)
        .onTapGesture {
            if picked.contains(place.id) {
                picked.remove(place.id)
            } else {
                picked.insert(place.id)
            }
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

// MARK: - 길찾기 (데모)

/// 「현재 위치 기준 여기서 길 찾기」.
///
/// **미확정·데모용이다.** 여행 중 길찾기를 「현재 위치 → 다음 목적지」 단위로 준다는
/// 것까지는 8/11 회의 2부에서 정해졌지만, 여기 보이는 노선·정류장·시간은 전부
/// 지어낸 값이다. 어떤 API 를 어떻게 부를지는 계약이 나온 뒤에 붙는다.
///
/// **길찾기 API 를 부르지 않는다.** 실측 호출은 유료 구간이고(T맵 종량제 회당 11.88원),
/// 화면을 보려고 돈을 쓸 이유가 없다.
struct RouteDirectionsSheet: View {
    let stop: RouteStop

    @Environment(\.dismiss) private var dismiss

    private let steps = [
        ("도보 4분", "안국역 2번 출구까지"),
        ("3호선 · 2정거장", "안국 → 종로3가"),
        ("1호선 환승 · 1정거장", "종로3가 → 종각"),
        ("도보 6분", "목적지까지"),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(steps, id: \.0) { step in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(step.0).font(.subheadline.weight(.semibold))
                            Text(step.1).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("현재 위치 → \(stop.place.name)")
                } footer: {
                    Text("데모 값입니다. 실제 길찾기 API 를 부르지 않습니다.")
                }
            }
            .navigationTitle("여기서 길 찾기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
