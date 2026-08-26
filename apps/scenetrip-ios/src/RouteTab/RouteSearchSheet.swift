import SceneApiClient
import SwiftUI

/// 코스를 짜다가 **여기서 바로** 장소를 찾아 담는 시트 (MZ2AZ-247).
///
/// ## 왜 장바구니만으로는 모자라나
///
/// 앞서 코스에 장소를 넣는 길은 둘뿐이었다 — 장바구니에서 고르기, 지도에 핀 찍기.
/// 장바구니는 **검색 탭에서 미리 담아 둔 것**이라, 코스를 짜다가 한 곳이 떠오르면
/// 편집을 두고 검색 탭으로 건너가 담고 돌아와야 했다. 짜던 것을 놓고 나갔다 오는
/// 일이라 흐름이 끊긴다(2026-08-24 사용자 지적).
///
/// 그래서 같은 검색을 편집 화면 안으로 들인다. **닫으면 짜던 자리로 그대로 돌아온다** —
/// 시트라서 뒤에 편집 화면이 살아 있고, 담은 것은 지금 보고 있는 일차에 바로 붙는다.
///
/// ## 무엇으로 찾나
///
/// 장소 이름과 **작품 이름** 둘 다다. 「도깨비」로 찾으면 그 드라마가 찍힌 곳이 다
/// 나온다 — 여행자가 기억하는 것은 장소 이름이 아니라 작품인 경우가 많다.
///
/// ## 서버를 다시 부르지 않는다
///
/// `RouteStore` 가 이미 촬영지 전체(155건)를 들고 있다. 글자를 칠 때마다 서버에
/// 물으면 호출이 타이핑 수만큼 늘어나는데, 155건은 메모리에서 거르는 편이 빠르고
/// 정확하다. 촬영지가 수천 건이 되면 그때 서버 검색으로 바꾼다.
struct RouteSearchSheet: View {
    /// **이미 코스에 담긴 촬영지.** 체크로 보여 주고 다시 못 고르게 한다 — 같은 곳을
    /// 두 번 담으면 한 여행에서 한 곳을 두 번 가게 된다(2026-08-25 사용자 지적).
    var taken: Set<Int64> = []

    /// 고르는 대로 바깥에 알린다. **담기 전에** 지도에 빨간 고양이로 뜨게 하는 것이다.
    var onPreview: ([PlaceSummary]) -> Void = { _ in }

    /// 고른 장소를 지금 보고 있는 일차에 넣는다.
    let onAdd: ([PlaceSummary]) -> Void

    @EnvironmentObject private var store: RouteStore
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var picked: [Int64] = []

    /// 이름·주소·작품으로 거른다. 빈 검색어면 인기순 그대로 보여 준다 — 빈 화면보다
    /// 무엇이든 있는 편이 다음 행동을 부른다.
    private var results: [PlaceSummary] {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return Array(store.places.prefix(40)) }
        return store.places.filter { place in
            place.name.localizedCaseInsensitiveContains(text)
                || (place.address ?? "").localizedCaseInsensitiveContains(text)
                || (place.contents ?? []).contains {
                    $0.title.localizedCaseInsensitiveContains(text)
                }
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if results.isEmpty {
                    ContentUnavailableView.search(text: query)
                } else {
                    List(results, id: \.id) { place in
                        row(place)
                    }
                    .listStyle(.plain)
                }
            }
            .searchable(
                text: $query,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "장소나 작품 이름"
            )
            .navigationTitle("장소 검색")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("추가 \(picked.count)") { addAndClose() }
                        .font(.body.weight(.semibold))
                        .disabled(picked.isEmpty)
                }
            }
        }
        // 장바구니 시트와 **같은 높이 규칙**이다 — 반쯤 올라오고, 필요하면 끌어
        // 올린다. 처음부터 화면을 다 덮으면 뒤에서 짜던 코스가 사라져 어디에 담는
        // 것인지 알 수 없다(2026-08-25 사용자 요청).
        .presentationDetents([.medium, .large])
        .presentationBackground(.regularMaterial)
    }

    private func row(_ place: PlaceSummary) -> some View {
        Button {
            toggle(place.id)
        } label: {
            HStack(spacing: 12) {
                RemoteImage(url: place.imageUrl, symbol: "mappin.and.ellipse")
                    .frame(width: 44, height: 44)
                    .clipShape(.rect(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 3) {
                    Text(place.name).font(.subheadline.weight(.medium))
                    // 어느 작품에 나왔는지가 이름보다 중요한 단서일 때가 많다.
                    if let work = (place.contents ?? []).first?.title {
                        Text(work).font(.caption).foregroundStyle(Color.accentColor)
                    }
                    if let address = place.address {
                        Text(address)
                            .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                }
                Spacer()

                // 고른 순서를 번호로 보여 준다. 여러 곳을 고를 때 **어느 순서로
                // 들어가는지**를 미리 알 수 있어야 담고 나서 다시 끌어 옮기지 않는다.
                if taken.contains(place.id) {
                    // 이미 코스에 있다. 「담긴 것」과 「지금 고른 것」을 갈라 보여야
                    // 왜 못 고르는지 안다.
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(Color.accentColor.opacity(0.45))
                } else if let order = picked.firstIndex(of: place.id) {
                    Text("\(order + 1)")
                        .font(.caption2.weight(.heavy))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(Color.accentColor))
                } else {
                    Image(systemName: "plus.circle")
                        .font(.title3)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, 2)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(taken.contains(place.id))
        .opacity(taken.contains(place.id) ? 0.5 : 1)
    }

    private func toggle(_ id: Int64) {
        if let index = picked.firstIndex(of: id) {
            picked.remove(at: index)
        } else {
            picked.append(id)
        }
        onPreview(resolve(picked))
    }

    /// id 를 장소로 되짚는다. 고른 순서를 지킨다.
    private func resolve(_ ids: [Int64]) -> [PlaceSummary] {
        let byId = Dictionary(uniqueKeysWithValues: store.places.map { ($0.id, $0) })
        return ids.compactMap { byId[$0] }
    }

    /// **고른 순서대로** 넣는다. `results` 순서로 넣으면 사용자가 3·1·2 로 골라도
    /// 목록 순서대로 들어가 의도가 사라진다.
    private func addAndClose() {
        onAdd(resolve(picked))
        dismiss()
    }
}
