import SceneApiClient

/// 지도에 꽂을 핀 한 개(MZ2AZ-519). `number` 는 목록의 행 번호(없으면 nil), `selected` 는 지금 열어 둔 장소다.
struct PinMark {
    let place: PlaceSummary
    let number: Int?
    let selected: Bool
}

/// 검색 탭 지도의 핀 목록 규칙 — 지도 SDK 없이 시험할 수 있게 `NaverMapView` 에서 떼어 냈다.
///
/// 기준은 웹 `mapRules.ts` 의 `pinMarks`·`pinsKey`(af6eee6b). 홈 「오늘의 성지」 처럼 검색 결과에 없는 장소를
/// 열면 지도에 그 핀이 없어 어디를 연 것인지 알 수 없었다.
enum PinMarks {
    /// 열어 둔 장소(`selected`)를 핀 목록에 맞춘다. 이미 핀이면 그 핀을 강조만 하고(중복 없이), 아니면 번호 없는
    /// 강조 핀을 **맨 뒤에** 하나 더 둔다 — 번호는 목록의 행 번호라 덧붙인 핀이 기존 번호를 밀면 안 된다.
    /// 다른 장소를 열거나 닫으면(`selected` 가 바뀌거나 nil) 덧붙인 핀과 강조는 그대로 사라진다.
    static func marks(_ pins: [PlaceSummary], selected: PlaceSummary?, numbered: Bool) -> [PinMark] {
        var marks = pins.enumerated().map { index, place in
            PinMark(place: place, number: numbered ? index + 1 : nil, selected: place.id == selected?.id)
        }
        if let selected, !marks.contains(where: \.selected) {
            marks.append(PinMark(place: selected, number: nil, selected: true))
        }
        return marks
    }

    /// 핀을 다시 그릴지 보는 열쇠 — 번호 표시 여부와 열어 둔 장소가 바뀌면 아이콘이 달라지므로 함께 넣는다.
    static func key(_ pins: [PlaceSummary], numbered: Bool, selected: PlaceSummary?) -> String {
        pins.map { String($0.id) }.joined(separator: ",")
            + (numbered ? "#n" : "#p")
            + (selected.map { "@\($0.id)" } ?? "")
    }
}

/// 장소를 여는 길. 어느 길이든 그 장소로 **확대**한다.
enum PlaceOpening { case homeNote, pin, listRow, suggestion }

/// 검색 탭 지도 카메라가 할 일 — `SearchTabView` 가 맞는 토큰을 올린다.
enum SearchCameraMove: Equatable {
    case none
    /// 지금 핀 전체 범위로 맞춘다(`fitToken`).
    case fitPins
    /// 열어 둔 장소로 확대한다(`focusToken`, 줌 16).
    case focusPlace
    /// 남한 전체로 돌아간다(`koreaToken`).
    case korea
}

enum SearchCameraRules {
    /// 장소를 열면 그 장소로 확대한다. 홈 쪽지(「오늘의 성지」)만 핀 전체 맞춤(`fitToken`)을 올리고 있어서
    /// 한반도 전체에 인기 핀만 보였다(MZ2AZ-519).
    static func opening(_: PlaceOpening) -> SearchCameraMove {
        .focusPlace
    }

    /// 첫 화면에서 탭을 바꿨을 때. 장소 탭이면 인기 핀이 한 화면에 들어오게 맞추되, **장소를 열어 둔 채**
    /// 넘어온 것이면(작품 탭 첫 화면에서 핀을 누른 경우) 그 장소로의 확대를 덮지 않는다(웹 af6eee6b 와 같다).
    static func afterTabChange(
        to tab: SearchTabView.Tab, isInitial: Bool, hasPins: Bool, placeOpen: Bool
    ) -> SearchCameraMove {
        guard isInitial else { return .none }
        switch tab {
        case .place: return hasPins && !placeOpen ? .fitPins : .none
        case .work: return .korea
        }
    }
}
