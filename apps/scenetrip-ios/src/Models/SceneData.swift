import Foundation
import SceneApiClient

/// 화면이 쓰는 데이터 저장소. **서버가 정본이다.**
///
/// 검색은 서버가 한다. `GET /v1/contents` 와 `GET /v1/places` 에 **같은 `q` 를 넣으면**
/// 두 탭이 같이 채워진다 (계획서 §3-2 · §4). 배우 이름으로도 장소가 걸리도록 서버가
/// 이미 넓혀 뒀으므로(MZ2AZ-167) 프론트가 우회할 것이 없다 — 그래서 이 타입에는
/// 검색 로직이 없다.
@MainActor
final class SceneData: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(ApiFailure)
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var contents: [ContentSummary] = []
    @Published private(set) var places: [PlaceSummary] = []

    private var inFlight: Task<Void, Never>?
    private var lastQuery = ""

    /// 검색어 하나로 두 탭을 채운다. 빈 문자열이면 전체를 받는다.
    func search(_ query: String) {
        lastQuery = query
        inFlight?.cancel()
        phase = .loading
        inFlight = Task { [weak self] in
            let term = query.trimmingCharacters(in: .whitespaces)
            let keyword: String? = term.isEmpty ? nil : term
            do {
                // 두 탭은 서로를 기다릴 이유가 없다.
                async let works = ContentsAPI.listContents(q: keyword, limit: 100)
                async let spots = PlacesAPI.listPlaces(q: keyword, limit: 200)
                let (contentList, placeList) = try await (works, spots)
                guard !Task.isCancelled else { return }
                self?.contents = contentList.items
                self?.places = placeList.items
                self?.phase = .loaded
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.phase = .failed(ApiFailure(error))
            }
        }
    }

    /// **지금 화면에 보이는 지도 범위** 안의 촬영지만 (목업 "현 지도 내 성지 검색").
    ///
    /// 반경이 아니라 뷰포트다. 사용자가 얼마나 확대했는지·어디를 보고 있는지는
    /// 그때그때 다르고, 이 기능은 **보이는 그대로** 를 묻는 것이다. 그래서 고정
    /// 반경(`radiusMeters`)이 아니라 `bbox` 를 보낸다.
    ///
    /// **`q` 를 함께 보내지 않는다** — "이 화면 안" 과 "이 단어" 는 서로 다른
    /// 질문이라 섞으면 결과를 설명할 수 없다.
    ///
    /// 작품 탭은 건드리지 않는다. 뷰포트는 장소의 성질이지 작품의 성질이 아니다.
    func searchInViewport(bbox: String) {
        lastQuery = ""
        inFlight?.cancel()
        phase = .loading
        inFlight = Task { [weak self] in
            do {
                let spots = try await PlacesAPI.listPlaces(
                    bbox: bbox,
                    limit: 200
                )
                guard !Task.isCancelled else { return }
                self?.places = spots.items
                self?.phase = .loaded
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.phase = .failed(ApiFailure(error))
            }
        }
    }

    /// §3-6 의 재시도. 마지막 검색어를 그대로 다시 보낸다.
    func retry() {
        search(lastQuery)
    }

    /// 작품 하나를 골랐을 때 그 작품의 촬영지 **전부**.
    ///
    /// 이 엔드포인트의 `limit` 상한은 100 이다 — 목록 API 의 200 을 그대로 넣으면
    /// `400 INVALID_PARAMETER` 가 온다(실측). 상한을 넘는 작품은 `offset` 으로
    /// 이어 받는다. 핀 번호가 곧 행 번호라 일부만 그리면 번호가 어긋난다.
    func places(ofContent id: Int64) async throws -> [PlaceSummary] {
        var all: [PlaceSummary] = []
        while true {
            let page = try await PlacesAPI.listContentPlaces(
                contentId: id, limit: 100, offset: all.count
            )
            all.append(contentsOf: page.items)
            if page.items.isEmpty || all.count >= page.total {
                return all
            }
        }
    }

    /// 촬영지 상세. 목록용 `PlaceSummary` 에는 없는 것들이 여기 있다 — 장소 사진 여러
    /// 장, 네이버 링크, 그리고 **작품별 장면**(`scenes`). 한 장소에서 여러 작품을
    /// 찍었으면 그 수만큼 온다.
    func detail(ofPlace id: Int64) async throws -> PlaceDetail {
        try await PlacesAPI.getPlace(placeId: id)
    }
}

/// 화면이 오류를 다루는 데 필요한 만큼만 추린 것 (계획서 §3-6).
///
/// 계약이 `ApiError.message` 를 "사용자에게 그대로 보여 줄 문구가 아니다" 라고 못 박아
/// 뒀으므로 여기서 화면 문구를 만들지 않는다. 상태 코드와 `traceId` 만 들고 나간다.
struct ApiFailure: Equatable {
    let statusCode: Int?
    let traceId: String?

    /// 재시도가 의미 있는 경우. 계약이 "재시도해도 된다 — 클라이언트가 고칠 것은
    /// 없다" 고 적은 것은 `500` 뿐이다. 서버에 닿지도 못한 경우(statusCode 없음)도
    /// 같이 본다 — 그쪽은 앱이 고칠 것이 없다는 점에서 성격이 같다.
    var isRetryable: Bool {
        statusCode == nil || statusCode == 500
    }

    /// 사용자에게 보일 한 줄.
    ///
    /// **안드로이드의 `ApiFailure.message` 와 같은 문구여야 한다** — 두 앱이 같은
    /// 상황에서 다른 말을 하면 같은 앱으로 보이지 않는다. 앞서 이 문구가 검색 탭의
    /// `ErrorView` 안에만 있어서 다른 화면이 재사용할 수 없었다.
    var message: String {
        switch statusCode {
        case nil: "서버에 연결하지 못했습니다."
        case 500: "잠시 문제가 생겼습니다."
        default: "요청을 처리하지 못했습니다."
        }
    }

    init(statusCode: Int?, traceId: String?) {
        self.statusCode = statusCode
        self.traceId = traceId
    }

    init(_ error: Error) {
        guard case let ErrorResponse.error(code, data, _, _) = error else {
            self.init(statusCode: nil, traceId: nil)
            return
        }
        // **생성 클라이언트는 음수를 HTTP 코드가 아닌 신호로 쓴다.** 연결 자체가
        // 실패하면 -1, 응답이 HTTP 가 아니면 -2 다
        // (URLSessionImplementations.swift:164,169). 그것을 상태 코드로 그대로 넘기면
        // 서버가 꺼져 있을 때 "요청을 처리하지 못했습니다" 가 뜨고 재시도 버튼이
        // 사라진다(실측) — 정작 재시도가 필요한 상황인데.
        let reachedServer = code > 0
        self.init(
            statusCode: reachedServer ? code : nil,
            traceId: reachedServer ? Self.traceId(from: data) : nil
        )
    }

    /// `traceId` 는 `500` 응답에만 실린다. 다른 코드에서는 키 자체가 빠지므로
    /// 디코딩이 실패하거나 nil 이 나오는 것이 정상이다.
    private static func traceId(from data: Data?) -> String? {
        guard let data,
              let body = try? JSONDecoder().decode(ApiError.self, from: data)
        else { return nil }
        return body.traceId
    }
}

// MARK: - 카테고리 칩

/// `place.type` 을 칩 단위로 접는 임시 매핑.
///
/// **서버가 묶어 주면 걷어낸다.** 계약이 `PlaceSummary.type` 을 "수집된 장소 유형을
/// 가공 없이" 자유 문자열로 내려주고 있어(현재 37 종) 클라이언트가 임시로 든다.
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

    static func of(_ placeType: String?) -> String {
        guard let placeType else { return "건물·시설" }
        return groups.first { $0.types.contains(placeType) }?.name ?? "건물·시설"
    }
}
