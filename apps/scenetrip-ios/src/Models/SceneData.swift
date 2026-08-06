import Foundation

/// 목데이터 저장소. 앱 번들의 scenetrip_v6.json 을 읽는다.
///
/// **임시다.** 서버가 이미 같은 것을 내려주고 있고(`GET /v1/contents`·`/v1/places`),
/// 생성 클라이언트를 붙이면(계획서 §5-5) 이 타입의 내용물만 갈린다. 화면 코드가
/// SceneRow 만 보게 짜 둔 이유다.
@MainActor
final class SceneData: ObservableObject {
    @Published private(set) var rows: [SceneRow] = []
    @Published private(set) var works: [Work] = []

    init() {
        load()
    }

    private func load() {
        guard let url = Bundle.main.url(forResource: "scenetrip_v6", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([SceneRow].self, from: data)
        else {
            assertionFailure("scenetrip_v6.json 을 읽지 못했다 — BUILD.bazel 의 resources 확인")
            return
        }
        rows = decoded

        // 제목으로 묶되 데이터에 나온 순서를 유지한다.
        var order: [String] = []
        var grouped: [String: [SceneRow]] = [:]
        for row in decoded {
            if grouped[row.title] == nil {
                order.append(row.title)
            }
            grouped[row.title, default: []].append(row)
        }
        works = order.map { Work(title: $0, rows: grouped[$0]!) }
            .sorted { $0.famousRank < $1.famousRank }
    }

    /// 같은 장소에서 찍은 다른 작품들.
    func sharingPlace(_ row: SceneRow) -> [SceneRow] {
        rows.filter { $0.placeName == row.placeName && $0.id != row.id }
    }
}

// MARK: - 카테고리 칩

/// place_type 37종을 4묶음으로 접는 임시 매핑.
///
/// **서버가 묶어 주면 걷어낸다.** 계약이 `PlaceSummary.type` 을 "수집된 장소 유형을
/// 가공 없이" 자유 문자열로 내려주고 있어(현재 37종) 클라이언트가 임시로 든다.
/// 이 표가 iOS·Android 에 두 벌로 복제되는 것이 계획서 §4 가 지적한 문제이며,
/// 권호와 상의할 항목으로 §6 남은것 #1 에 올라 있다.
enum CategoryChip {
    static let all = "전체"

    static let groups: [(name: String, types: Set<String>)] = [
        ("음식점·카페", ["음식점", "카페", "바", "편의점", "마트", "시장"]),
        ("명소·자연", [
            "명소", "자연", "공원", "해변", "항구", "전망대", "사찰", "성당", "고궁",
            "한옥", "한옥마을", "마을", "테마파크", "체험시설", "캠핑장",
        ]),
        ("거리·다리", ["거리", "다리", "역/교통", "공항"]),
        ("건물·시설", [
            "건물", "호텔", "병원", "학교", "박물관/미술관", "서점", "상점", "백화점",
            "쇼핑몰", "경기장", "스포츠시설", "예식장", "장례식장", "세트장", "관공서",
        ]),
    ]

    static var names: [String] {
        [all] + groups.map(\.name)
    }

    static func of(_ placeType: String) -> String {
        groups.first { $0.types.contains(placeType) }?.name ?? "건물·시설"
    }
}
