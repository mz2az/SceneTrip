import Foundation
import SceneApiClient

/// 경로여정 탭의 **목 데이터**.
///
/// 서버가 아직 없다. 경로 관련 계약이 하나도 없으므로(`contracts/openapi/` 참고)
/// 이 탭은 통째로 로컬 값으로 돈다 — 팀이 눈으로 보고 고치기 위한 화면이다.
///
/// **장소 이름과 좌표는 실제 촬영지의 것**이다. 지어낸 좌표를 쓰면 지도에 그려지는
/// 동선이 실제와 달라져, 동선 최적화가 제대로 도는지조차 판단할 수 없다.
/// 작품 배지·주소는 화면을 채우기 위한 값이라 정밀하지 않다.
///
/// 서버가 서면 이 파일은 통째로 사라진다. 그래서 **다른 파일이 이 안의 값을 직접
/// 참조하지 않게** 두었다 — 화면은 `RouteStore` 를 통해서만 읽는다.
enum RouteMock {
    // MARK: 작품

    /// 질문 흐름의 「어떤 작품을 좋아하나요?」에 뿌리는 작품.
    ///
    /// 배열 순서가 곧 **인기도순**이다. 찜한 작품을 앞으로 끌어올리는 일은
    /// `RouteStore.works` 가 한다 — 8/11 회의 확정: *"찜한 작품 우선적으로 보여주고
    /// 나머지는 인기도 순으로"*.
    static let works: [ContentSummary] = [
        work(101, "도깨비", .drama, "tvN", 2016, ["판타지", "멜로"], 4),
        work(103, "눈물의 여왕", .drama, "tvN", 2024, ["멜로", "코미디"], 4),
        work(102, "이태원 클라쓰", .drama, "JTBC", 2020, ["드라마"], 3),
        work(105, "사랑의 불시착", .drama, "tvN", 2019, ["멜로"], 2),
        work(104, "기생충", .movie, nil, 2019, ["스릴러"], 2),
    ]

    /// 처음부터 찜해 둔 작품.
    ///
    /// **작품 찜은 아직 어디에도 없다.** 8/11 회의에서 "작품에는 하트, 장소에는
    /// 장바구니" 가 확정됐지만 서버에도 검색 탭에도 아직 없다. 여기서는 화면을
    /// 보기 위해 두 개를 찜해 둔 값으로 고정한다 — 목업도 같은 처리를 했다.
    static let favoriteWorkIds: Set<Int64> = [101, 103]

    // MARK: 촬영지

    static let places: [PlaceSummary] = [
        place(201, "덕수궁 돌담길", "거리", "서울 중구 세종대로19길", 37.5658, 126.9751, [101]),
        place(202, "운현궁", "고궁", "서울 종로구 삼일대로 464", 37.5748, 126.9856, [101]),
        place(203, "북촌한옥마을", "한옥마을", "서울 종로구 계동길", 37.5826, 126.9830, [101, 103]),
        place(204, "경복궁", "고궁", "서울 종로구 사직로 161", 37.5796, 126.9770, [105]),
        place(205, "N서울타워", "전망대", "서울 용산구 남산공원길 105", 37.5512, 126.9882, [102, 103]),
        place(206, "녹사평역 광장", "역/교통", "서울 용산구 이태원로", 37.5347, 126.9876, [102]),
        place(207, "경리단길", "거리", "서울 용산구 회나무로", 37.5405, 126.9887, [102]),
        place(208, "세빛섬", "명소", "서울 서초구 올림픽대로 683", 37.5126, 126.9955, [103]),
        place(209, "대림창고", "카페", "서울 성동구 성수이로 78", 37.5443, 127.0557, [103]),
        place(210, "자하문터널 계단", "거리", "서울 종로구 자하문로", 37.5906, 126.9663, [104]),
        place(211, "우리슈퍼", "상점", "서울 마포구 서강로9길", 37.5556, 126.9497, [104]),
        place(212, "남산 케이블카 승강장", "명소", "서울 중구 소파로 83", 37.5560, 126.9838, [102]),
        place(213, "청계천 광통교", "다리", "서울 종로구 청계천로", 37.5687, 126.9835, [103]),
        place(214, "주문진 방사제", "해변", "강원 강릉시 주문진읍", 37.8983, 128.8317, [101]),
    ]

    /// 장바구니가 비었을 때 대신 보여 주는 값.
    ///
    /// 장바구니의 정본은 서버다(`CartStore`). 하지만 이 데모는 **서버 없이도 떠야**
    /// 팀이 화면을 볼 수 있으므로, 담긴 것이 없으면 이 네 곳을 대신 보여 준다.
    /// 시트에 "예시" 라고 적어 진짜 장바구니와 구별한다.
    static let cartFallback: [PlaceSummary] = [201, 205, 209, 213].compactMap { id in
        places.first { $0.id == id }
    }

    // MARK: 만들기

    static func day(_ ids: [Int64]) -> RouteDay {
        RouteDay(stops: ids.compactMap { id in
            places.first { $0.id == id }.map { RouteStop(place: $0) }
        })
    }

    /// 지도를 눌러 찍은 핀. **우리 데이터에 없는 곳**이라 서버 id 를 줄 수 없어
    /// 음수로 매긴다 — 진짜 촬영지 id 와 절대 겹치지 않는다.
    static func pinnedPlace(name: String, category: String, lat: Double, lng: Double) -> PlaceSummary {
        PlaceSummary(
            id: -Int64(Date().timeIntervalSince1970 * 1000) % 1_000_000_000,
            name: name,
            type: category,
            address: nil,
            latitude: lat,
            longitude: lng
        )
    }

    // 인자가 일곱이라 린트가 막는다. **여기서는 그 규칙을 끈다.**
    //
    // 이 둘은 로직이 아니라 **표를 읽어 들이는 자리**다. 위의 `places`·`works` 가
    // 한 줄에 한 곳씩 늘어선 표로 읽히는 것이 이 파일의 전부이고, 그렇게 만들려면
    // 생성자 인자를 이름 없이 늘어놓는 수밖에 없다. 규칙대로 구조체를 끼워 넣으면
    // 표 한 줄이 세 줄로 늘어나 정작 읽어야 할 값이 안 보인다.
    //
    // 검색 탭도 같은 판단을 한 번 했다 (`NaverMapView.Coordinator.render`).
    // swiftlint:disable:next function_parameter_count
    private static func place(
        _ id: Int64,
        _ name: String,
        _ type: String,
        _ address: String,
        _ lat: Double,
        _ lng: Double,
        _ workIds: [Int64]
    ) -> PlaceSummary {
        PlaceSummary(
            id: id,
            name: name,
            type: type,
            address: address,
            latitude: lat,
            longitude: lng,
            contents: workIds.compactMap { workId in
                works.first { $0.id == workId }
                    .map { ContentRef(contentId: $0.id, title: $0.title) }
            }
        )
    }

    // swiftlint:disable:next function_parameter_count
    private static func work(
        _ id: Int64,
        _ title: String,
        _ category: ContentCategory,
        _ broadcaster: String?,
        _ year: Int,
        _ genres: [String],
        _ placeCount: Int
    ) -> ContentSummary {
        ContentSummary(
            id: id,
            category: category,
            title: title,
            broadcaster: broadcaster,
            releaseYear: year,
            genres: genres,
            placeCount: placeCount
        )
    }
}
