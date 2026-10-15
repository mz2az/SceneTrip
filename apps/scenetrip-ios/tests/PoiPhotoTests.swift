import SceneApiClient
@testable import SceneTrip
import XCTest

final class PoiPhotoTests: XCTestCase {
    func testGeneratedPhotoFieldsReachTheGallery() throws {
        let data = Data("""
        {"url":"https://example.com/full.jpg","source":"official","thumbnailUrl":"https://example.com/small.jpg",
         "license":"kogl_type3","credit":"한국관광공사"}
        """.utf8)
        let photo = try PhotoGalleryRules.photo(JSONDecoder().decode(Photo.self, from: data))
        XCTAssertEqual(photo.thumbnailUrl, "https://example.com/small.jpg")
        XCTAssertEqual(photo.license, .koglType3)
        XCTAssertEqual(photo.credit, "한국관광공사")
        XCTAssertTrue(PhotoGalleryRules.preservesFrame(photo), "호스트나 credit 문자열에 기대지 않는다")
    }

    func testBothLicensesShowLocalizedSourceEvenWithoutCredit() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        for license in PhotoLicense.allCases {
            for credit in [nil, "한국관광공사"] as [String?] {
                let photo = PhotoGalleryRules.photo(Photo(url: "https://example.com/a.jpg", source: .official,
                                                          credit: credit, license: license))
                AppLanguage.current = .ko
                XCTAssertEqual(PhotoGalleryRules.credits([photo]), ["출처: 한국관광공사"])
                AppLanguage.current = .en
                XCTAssertEqual(PhotoGalleryRules.credits([photo]), ["Source: Korea Tourism Organization"])
            }
        }
    }

    func testLicensedPhotoKeepsAdditionalCreditAndDeduplicatesCaptions() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .ko
        let photo = PhotoGalleryRules.photo(Photo(url: "https://example.com/a.jpg", source: .official,
                                                  credit: "촬영: 홍길동 · 공공누리 제3유형", license: .koglType3))
        XCTAssertEqual(PhotoGalleryRules.credits([photo, photo]),
                       ["출처: 한국관광공사\n촬영: 홍길동 · 공공누리 제3유형"])
    }

    func testSmallPhotosUseThumbnailAndDetailKeepsOriginalAndIdentity() {
        let photo = PhotoGalleryRules.photo(Photo(url: "https://example.com/full.jpg", source: .official,
                                                  thumbnailUrl: "https://example.com/small.jpg", license: .koglType3))
        XCTAssertEqual(PhotoGalleryRules.imageUrl(photo, thumbnail: true), photo.thumbnailUrl)
        XCTAssertEqual(PhotoGalleryRules.imageUrl(photo, thumbnail: false), photo.url)
        XCTAssertEqual(photo.key, "o:https://example.com/full.jpg")
        XCTAssertFalse(PhotoGalleryRules.signed(photo))
    }

    func testAbsentNullEmptyAndUnsafeThumbnailsFallBackToOriginal() throws {
        let extras = ["", ",\"thumbnailUrl\":null", ",\"thumbnailUrl\":\"\"",
                      ",\"thumbnailUrl\":\"javascript:alert(1)\""]
        for extra in extras {
            let data = Data("{\"url\":\"https://example.com/full.jpg\",\"source\":\"official\"\(extra)}".utf8)
            let photo = try PhotoGalleryRules.photo(JSONDecoder().decode(Photo.self, from: data))
            XCTAssertEqual(PhotoGalleryRules.imageUrl(photo, thumbnail: true), photo.url)
            XCTAssertEqual(PhotoGalleryRules.imageUrl(photo, thumbnail: false), photo.url)
        }
    }

    func testTypeThreePreservesTheFrameWithoutHostOrCreditHints() {
        for credit in [nil, "", "한국관광공사"] as [String?] {
            let photo = PhotoGalleryRules.photo(Photo(url: "https://example.com/full.jpg", source: .official,
                                                      credit: credit, license: .koglType3))
            XCTAssertTrue(PhotoGalleryRules.preservesFrame(photo))
        }
    }
}
