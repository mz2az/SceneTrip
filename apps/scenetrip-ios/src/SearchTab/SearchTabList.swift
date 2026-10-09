import SceneApiClient
import SwiftUI

/// 검색 탭 시트의 **목록** — 작품 / 장소 두 탭, 분류 칩, 그리고 이어 받기(MZ2AZ-372).
///
/// `SearchTabView.swift` 에서 떼어 냈다(`SearchTabOverlays.swift` 와 같은 이유 — 한 타입의 본문이 한 화면에
/// 담기는 만큼만). 상태는 그쪽에 있고 여기는 그것을 그리기만 한다.
///
/// ## 개수는 서버의 총수다
///
/// 탭의 숫자는 받아 둔 줄 수가 아니라 서버가 준 `total` 이다. 전에는 받은 줄을 세어 「인기 작품 100」 이라고
/// 적었는데 실제는 128편이었다 — 받는 양의 상한이 100 이었을 뿐이다. 나머지는 목록 끝이 보일 때 이어 받는다.
extension SearchTabView {
    var listContent: some View {
        VStack(spacing: 0) {
            // 검색을 하고 들어온 목록이면 나가는 길을 준다.
            //
            // 화면은 초기목록 → 검색결과 → 작품상세 → 장소상세 로 쌓인다. 상세 둘은
            // `<` 로 한 단계씩 나오는데 **검색결과에만 그것이 없어서**, 작품 상세에서
            // `<` 를 눌러 여기까지 온 사용자가 더 나갈 자리를 못 찾았다. 검색바의
            // ⊗ 로 지울 수는 있지만 방금 누른 것과 다른 자리라 이어지지 않는다.
            //
            // 그래서 **상세와 같은 헤더를 같은 자리에** 쓴다. 브라우저 뒤로가기처럼
            // 한 번에 한 단계씩만 나온다 — 여기서 한 단계는 "검색 전" 이다.
            if !committed.isEmpty {
                DetailHeader(title: committed, subtitle: "") {
                    draft = ""
                    commit("")
                }
            }

            // 첫 화면의 숫자는 **전체가 아니라 인기순으로 추린 것** 이다. 그냥
            // "장소 10" 이라고만 두면 전국에 10곳뿐인 것으로 읽힌다.
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { each in
                    Text(tabLabel(each)).tag(each)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 14)
            .padding(.bottom, 8)

            if tab == .place {
                // 새 검색이 깔리면 칩 줄도 처음 자리로 — 옆으로 밀어 둔 채 「전체」 로 돌아가면 켜진 칩이 안 보인다.
                ChipRow(selected: $chip).id(data.listSerial)
                partialNotice
            }

            ScrollView {
                LazyVStack(spacing: 0) {
                    if tab == .work {
                        workRows
                    } else {
                        placeRows
                    }
                }
                // **탭이 바뀌면 스택을 새로 만든다.** 작품 id 와 장소 id 는 같은 정수
                // 공간(둘 다 1 부터)이라, 위 두 ForEach 가 같은 id 의 행을 낸다. LazyVStack
                // 은 id 로 행을 재사용하므로 탭을 오가면 작품 목록에 장소 행(번호 핀)이
                // 남아 섞였다(iOS 26 시뮬레이터 실측, 2026-09-01 — iOS 18 에서는 드러나지
                // 않았다). 탭 전환 때 스크롤이 맨 위로 가는 것은 바라던 동작이다.
                .id(tab)
            }
            // **새 검색이 깔리면 스크롤 뷰째 새로 만든다** — 앞 검색에서 내려가 있던 자리가 남지 않게.
            // 안쪽 스택만 새로 만들면 내용은 바뀌어도 스크롤 위치는 그대로였다(실측).
            .id(data.listSerial)
        }
        // 분류 칩을 켜면 목록 끝이 보이든 말든 끝까지 받는다(아래 `fillForChip`).
        .task(id: chipFillKey) { await fillForChip() }
    }

    // MARK: 분류 칩 — 끝까지 받기

    /// 칩이 켜졌을 때 이어 받는 쪽 수의 상한(한 쪽 200곳 — 2,000곳). 넘으면 멈추고 「12+」 로 남는다.
    static let chipFillPages = 10

    /// **칩이 켜지면 끝까지 받는다** (MZ2AZ-372).
    ///
    /// 칩은 받아 둔 것 안에서 거른다(서버에 유형으로 거르는 인자가 없다 — 계획 §6). 목록 끝이 보일 때만
    /// 이어 받으면, 걸러진 줄이 화면을 넘는 칩(음식점·카페 34줄)은 사용자가 끝까지 내리기 전에는 나머지를
    /// 받지 않아 **목록과 핀이 조용히 일부만** 보인다. 그래서 칩이 켜져 있는 동안은 끝줄과 무관하게 받는다.
    ///
    /// 칩을 끄거나 바꾸거나 화면을 떠나면 이 일이 취소돼 멈춘다. 못 받으면 멈추고 목록 끝에 「다시 시도」 가
    /// 남는다 — 그것을 누르면 여기부터 다시 돈다(`chipFillRetry`).
    private func fillForChip() async {
        guard chip != CategoryChip.all else { return }
        var pages = 0
        while wantsMorePlaces, pages < Self.chipFillPages, !Task.isCancelled {
            guard await data.loadNextPlaces() else { return }
            pages += 1
        }
    }

    /// 칩·검색이 바뀌거나 새 결과가 깔리거나 「다시 시도」 를 누르면 달라진다.
    private var chipFillKey: String {
        "\(chip)-\(data.listSerial)-\(data.phase == .loaded)-\(chipFillRetry)"
    }

    /// 칩을 켠 채 받는 중인가 — 탭의 수와 안내 문구가 이것으로 말을 맞춘다.
    private var chipIsFilling: Bool {
        chip != CategoryChip.all && !isInitial && data.placePaging.hasMore
    }

    // MARK: 작품

    @ViewBuilder private var workRows: some View {
        ForEach(data.contents, id: \.id) { content in
            Button { open(content) } label: {
                WorkRow(
                    content: content,
                    onLike: { likes.toggle(content.id) },
                    liked: likes.contains(content.id)
                )
            }
            .buttonStyle(.plain)
            Divider().padding(.leading, 14)
        }
        if data.moreContentsFailed {
            ListMoreRetry { data.loadMoreContents() }
        } else if data.contentPaging.hasMore {
            // 이 줄이 화면에 들어오면 다음 쪽을 받는다. 한 쪽이 붙으면 `offset` 이 바뀌어 다시 돈다.
            ListMoreSpinner()
                .task(id: moreKey(data.contentPaging)) { data.loadMoreContents() }
        } else if data.contents.isEmpty, data.phase == .loaded {
            ListEmptyNote(title: tr("검색된 작품이 없어요"), detail: "")
        }
    }

    // MARK: 장소

    @ViewBuilder private var placeRows: some View {
        let rows = visiblePlaces
        // 번호는 지도 핀과 같은 배열의 같은 순서다 — "3번 행 = 3번 핀".
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, place in
            Button {
                selectedPlace = place
                focusToken += 1
            } label: {
                PlaceRow(
                    place: place,
                    number: index + 1,
                    onAdd: { save(place) },
                    saved: cart.contains(place.id)
                )
            }
            .buttonStyle(.plain)
            Divider().padding(.leading, 14)
        }
        if data.morePlacesFailed, wantsMorePlaces {
            // 칩이 켜져 있으면 한 쪽이 아니라 끝까지 다시 받는다.
            ListMoreRetry {
                if chip == CategoryChip.all {
                    data.loadMorePlaces()
                } else {
                    chipFillRetry += 1
                }
            }
        } else if wantsMorePlaces {
            ListMoreSpinner()
                .task(id: moreKey(data.placePaging)) { data.loadMorePlaces() }
        } else if rows.isEmpty, data.phase == .loaded {
            emptyPlaces
        }
    }

    /// 장소 목록이 다음 쪽을 받아야 하는가.
    ///
    /// 분류 칩은 **받아 둔 것 안에서** 거른다(서버에 유형으로 거르는 인자가 없다 — 계획 §6). 그래서 칩을 켠
    /// 채로는 걸러진 줄이 적어 목록 끝이 곧 보이고, 그때마다 다음 쪽을 받아 전부 받을 때까지 이어진다.
    /// 첫 화면은 인기 10곳만 보이므로 그 10곳이 찼으면 더 받지 않는다.
    var wantsMorePlaces: Bool {
        guard data.placePaging.hasMore else { return false }
        return !isInitial || visiblePlaces.count < Self.initialPlaceCount
    }

    /// 빈 묶음의 안내. 전에는 「음식점·카페」 를 눌러 0건이면 **빈 화면뿐**이라 고장으로 보였다.
    @ViewBuilder private var emptyPlaces: some View {
        if chip != CategoryChip.all {
            ListEmptyNote(
                title: tr("이 분류의 촬영지가 없어요"),
                detail: tr("다른 분류를 누르거나 「전체」 로 돌아가 보세요")
            )
        } else if nearby {
            ListEmptyNote(
                title: tr("이 지도 안에는 촬영지가 없어요"),
                detail: tr("지도를 옮기거나 축소한 뒤 다시 검색해 보세요")
            )
        } else {
            ListEmptyNote(title: tr("검색된 장소가 없어요"), detail: "")
        }
    }

    /// 받아 둔 것이 전부가 아닐 때 사실대로 적는 한 줄 — 「443곳 중 200곳」 (MZ2AZ-372).
    ///
    /// 지도의 핀도 받아 둔 만큼만 찍힌다(계약의 한 번 상한이 200). 「이 지도에서 200곳」 이라고만 적으면
    /// 전국에 200곳뿐인 것으로 읽힌다. 지도 범위로 찾은 것이면 **확대해서 다시 찾으면 더 보인다**는 것과
    /// 그 단추를 함께 둔다 — 같은 화면을 더 좁게 물으면 그 안의 것이 상한 안에 들어온다.
    ///
    /// 여기의 총수는 **지금 물은 조건의 총수**다 — 지도 범위로 찾았으면 그 화면 안의 수(전국 축척에서 443),
    /// 전체 촬영지 수(486)가 아니다.
    ///
    /// **분류 칩이 켜져 있으면 말이 달라진다.** 그때 탭의 수는 걸러진 줄 수(「34+」)라, 「443곳 중 200곳」 을
    /// 그대로 두면 두 숫자가 서로 다른 것을 센다. 칩이 켜진 동안은 「443곳 중 200곳에서 찾았어요 — 나머지를
    /// 확인하는 중」 이라고 적고, 다 받으면 이 줄이 사라지고 탭의 수가 정확해진다.
    @ViewBuilder var partialNotice: some View {
        let loaded = data.places.count
        let total = data.placePaging.shownTotal(loaded: loaded)
        if !isInitial, data.phase == .loaded, loaded < total {
            VStack(alignment: .leading, spacing: 3) {
                if chipIsFilling {
                    Text(String(format: tr("이 분류를 %2$d곳 중 %1$d곳에서 찾았어요"), loaded, total))
                        .font(.caption.weight(.semibold))
                    Text(data.morePlacesFailed ? tr("나머지를 확인하지 못했어요") : tr("나머지를 확인하는 중이에요"))
                        .font(.caption2).foregroundStyle(.secondary)
                } else {
                    Text(String(format: tr("%2$d곳 중 %1$d곳을 보고 있어요"), loaded, total))
                        .font(.caption.weight(.semibold))
                    HStack(spacing: 6) {
                        Text(nearby ? tr("지도를 확대하면 더 보여요") : tr("목록을 내리면 이어서 보여요"))
                            .font(.caption2).foregroundStyle(.secondary)
                        if nearby {
                            Button(tr("이 화면 다시 검색")) { searchViewport() }
                                .font(.caption2.weight(.semibold))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.bottom, 8)
        }
    }

    // MARK: 숫자

    /// 목록 탭의 글자 — 「인기 작품 128」·「장소 8」. `Tab` 의 rawValue 는 식별용이라 그대로 두고
    /// 그리는 문구만 여기서 지금 언어로 만든다(MZ2AZ-343).
    func tabLabel(_ each: Tab) -> String {
        let format = switch (each, isInitial) {
        case (.work, true): tr("인기 작품 %d")
        case (.work, false): tr("작품 %d")
        case (.place, true): tr("인기 장소 %d")
        case (.place, false): tr("장소 %d")
        }
        let shown = count(each)
        return String(format: format, shown.count) + (shown.orMore ? "+" : "")
    }

    /// 탭에 적을 수. 작품과 (칩 없는) 장소는 서버의 총수다. 분류 칩을 켠 장소는 받아 둔 것 안에서 센 수라,
    /// 아직 더 받을 것이 있으면 「12+」 로 적는다 — 「12」 라고 못 박으면 거짓이다.
    private func count(_ tab: Tab) -> (count: Int, orMore: Bool) {
        switch tab {
        case .work:
            (data.contentPaging.shownTotal(loaded: data.contents.count), false)
        case .place where isInitial:
            (visiblePlaces.count, false)
        case .place where chip == CategoryChip.all:
            (data.placePaging.shownTotal(loaded: data.places.count), false)
        case .place:
            (visiblePlaces.count, data.placePaging.hasMore)
        }
    }

    /// 이어 받기를 다시 걸 열쇠 — 한 쪽을 받을 때마다, 그리고 새 검색이 끝날 때마다 바뀐다.
    private func moreKey(_ paging: Paging) -> String {
        "\(paging.offset)-\(data.phase == .loaded)"
    }
}

/// 목록 끝의 「받는 중」.
struct ListMoreSpinner: View {
    var body: some View {
        ProgressView()
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
    }
}

/// 이어 받기가 실패했을 때. 저절로 다시 부르지 않는다 — 서버가 아픈데 목록 끝이 보이는 내내 두드리게 된다.
struct ListMoreRetry: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(tr("더 불러오지 못했어요 · 다시 시도"))
                .font(.footnote)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.accentColor)
    }
}

/// 목록이 비었을 때의 안내.
struct ListEmptyNote: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 5) {
            Text(title).font(.subheadline.weight(.medium))
            if !detail.isEmpty {
                Text(detail).font(.caption).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24).padding(.vertical, 36)
    }
}
