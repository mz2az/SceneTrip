import Foundation
import SceneApiClient

/// 리뷰가 붙는 대상 (MZ2AZ-363). 촬영지와 편의시설은 id 가 따로 매겨져 있어 창구도 두 벌이다
/// (`/places/{id}/reviews` · `/pois/{id}/reviews`) — 화면이 그것을 모르게 여기서 한 모양으로 감싼다.
///
/// 계약의 `ReviewTarget`(내 리뷰 목록에 실려 오는 대상)과 이름이 겹쳐 `ReviewSubject` 라 부른다.
enum ReviewSubject: Hashable, Identifiable {
    case place(Int64)
    case poi(Int64)

    var id: String {
        switch self {
        case let .place(id): "place-\(id)"
        case let .poi(id): "poi-\(id)"
        }
    }

    /// 분석 이벤트의 `target_type`.
    var kind: String {
        switch self {
        case .place: "place"
        case .poi: "poi"
        }
    }

    /// 한 번에 받는 리뷰 수.
    static let pageSize = 20

    /// 리뷰 목록 한 쪽과 요약. 비회원도 읽는다.
    func reviews(sort: ReviewSort, offset: Int) async throws -> ReviewList {
        switch self {
        case let .place(id):
            try await ReviewsAPI.listPlaceReviews(
                placeId: id, sort: sort, limit: Self.pageSize, offset: offset
            )
        case let .poi(id):
            try await ReviewsAPI.listPoiReviews(
                poiId: id, sort: sort, limit: Self.pageSize, offset: offset
            )
        }
    }
}
