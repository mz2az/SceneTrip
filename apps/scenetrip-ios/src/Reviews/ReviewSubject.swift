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

    /// 내가 이 대상에 쓴 리뷰. 없으면 nil(`404 REVIEW_NOT_FOUND`) — 빈 쓰기 화면을 연다.
    func myReview() async throws -> Review? {
        do {
            switch self {
            case let .place(id): return try await ReviewsAPI.getMyPlaceReview(placeId: id)
            case let .poi(id): return try await ReviewsAPI.getMyPoiReview(poiId: id)
            }
        } catch let ErrorResponse.error(status, data, _, _)
            where status == 404 && AuthRules.apiCode(from: data) == "REVIEW_NOT_FOUND"
        {
            // 404 가 전부 「리뷰 없음」 은 아니다 — 장소가 없어진 것(`PLACE_NOT_FOUND`·`POI_NOT_FOUND`)은 실패다.
            return nil
        }
    }

    /// 쓰기·고치기 — 한 대상에 한 개라, 있으면 고친다. **보낸 것이 전부다**: 남길 사진은 그 키를 그대로 보낸다.
    func save(rating: Int, body: String?, photoKeys: [String]) async throws -> Review {
        let input = ReviewInput(rating: rating, body: body, photoKeys: photoKeys)
        switch self {
        case let .place(id): return try await ReviewsAPI.putMyPlaceReview(placeId: id, reviewInput: input)
        case let .poi(id): return try await ReviewsAPI.putMyPoiReview(poiId: id, reviewInput: input)
        }
    }

    /// 지우기. 되돌릴 수 없다 — 부르는 쪽이 확인 창을 띄운 뒤에 부른다.
    func deleteMine() async throws {
        switch self {
        case let .place(id): try await ReviewsAPI.deleteMyPlaceReview(placeId: id)
        case let .poi(id): try await ReviewsAPI.deleteMyPoiReview(poiId: id)
        }
    }
}
