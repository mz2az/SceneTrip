import Foundation
import SceneApiClient

/// 상세 응답이 정본이며 없는 제작사 주소를 추정하지 않는다 (MZ2AZ-392).
enum ProducerLink {
    static func destination(summary: ContentSummary, detail: ContentDetail?) -> URL? {
        if let detail, detail.id == summary.id {
            return destination(detail.producerUrl)
        }
        return destination(summary.producerUrl)
    }

    static func destination(_ raw: String?) -> URL? {
        WebAddress.url(raw)
    }
}
