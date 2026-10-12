import SceneApiClient
@testable import SceneTrip
import XCTest

final class ProducerLinkTests: XCTestCase {
    func testSuccessfulDetailCanClearAnOldSummaryLink() {
        let summary = ContentSummary(id: 1, category: .drama, title: "작품", producerUrl: "https://example.com/old", placeCount: 1)
        let cleared = ContentDetail(id: 1, category: .drama, title: "작품", placeCount: 1)
        XCTAssertEqual(ProducerLink.destination(summary: summary, detail: nil)?.absoluteString, summary.producerUrl)
        XCTAssertNil(ProducerLink.destination(summary: summary, detail: cleared))
    }

    func testDetailFromAnotherWorkCannotReplaceThisWorksLink() {
        let summary = ContentSummary(id: 1, category: .drama, title: "작품", producerUrl: "https://example.com/one", placeCount: 1)
        let other = ContentDetail(id: 2, category: .drama, title: "다른 작품", producerUrl: "https://example.com/two", placeCount: 1)
        XCTAssertEqual(ProducerLink.destination(summary: summary, detail: other)?.absoluteString, summary.producerUrl)
    }

    func testOfficialHttpAndHttpsPagesKeepTheirDestination() {
        for raw in ["https://www.studiodragon.net/en/works/portfolio/the-glory/", "http://astory.co.kr/works/?id=123"] {
            XCTAssertEqual(ProducerLink.destination(raw)?.absoluteString, raw)
        }
    }

    func testMissingAndNonWebDestinationsHaveNoLink() {
        for raw in [nil, "", "   ", "/works/123", "https:///works", "file:///tmp/a.html",
                    "javascript:alert(1)", "scenetrip://works/123", "https://user:pass@example.com/a"]
        {
            XCTAssertNil(ProducerLink.destination(raw), raw ?? "nil")
        }
    }

    func testOuterWhitespaceIsTrimmedButEmbeddedWhitespaceIsRejected() {
        XCTAssertEqual(ProducerLink.destination(" https://example.com/work \n")?.absoluteString, "https://example.com/work")
        XCTAssertNil(ProducerLink.destination("https://example.com/one two"))
    }

    func testMalformedPercentEscapesAreRejected() {
        for raw in ["https://example.com/%", "https://example.com/%Z0", "https://example.com/%0Z"] {
            XCTAssertNil(ProducerLink.destination(raw))
        }
        XCTAssertEqual(ProducerLink.destination("https://example.com/a%20b")?.absoluteString, "https://example.com/a%20b")
    }
}
