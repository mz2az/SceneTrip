@testable import SceneTrip
import XCTest

/// 커뮤니티 글의 글쓴이 이름 (MZ2AZ-351). 내 글은 지금 계정의 닉네임, 없으면 「나」. 남의 글은 저장된 이름.
final class CommunityAuthorTests: XCTestCase {
    private func post(author: String? = nil) -> CommunityPost {
        CommunityPost(
            id: UUID(), board: .review, title: "제주 이틀", body: "", createdAt: Date(), author: author
        )
    }

    func testMyPostShowsNickname() {
        XCTAssertEqual(post().authorName(myNickname: "gil"), "gil")
    }

    /// 로그인하지 않았거나 `/me` 를 아직 못 읽었으면 닉네임이 없다.
    func testMyPostWithoutNicknameFallsBack() {
        XCTAssertEqual(post().authorName(myNickname: nil), tr("나"))
    }

    /// 빈 이름으로 그리면 줄이 비고 동그라미도 빈다.
    func testBlankNicknameFallsBack() {
        XCTAssertEqual(post().authorName(myNickname: ""), tr("나"))
        XCTAssertEqual(post().authorName(myNickname: "  \n"), tr("나"))
    }

    /// 남의 글에 내 닉네임이 붙으면 안 된다.
    func testOthersPostKeepsItsAuthor() {
        XCTAssertEqual(post(author: "제주러버").authorName(myNickname: "gil"), "제주러버")
        XCTAssertEqual(post(author: "제주러버").authorName(myNickname: nil), "제주러버")
    }

    func testInitialIsFirstCharacterOfShownName() {
        XCTAssertEqual(post().authorInitial(myNickname: "gil"), "g")
        XCTAssertEqual(post().authorInitial(myNickname: "여행자12345"), "여")
        XCTAssertEqual(post().authorInitial(myNickname: nil), String(tr("나").prefix(1)))
        XCTAssertEqual(post(author: "제주러버").authorInitial(myNickname: "gil"), "제")
    }

    /// 닉네임은 글에 저장하지 않는다 — 옛 글(글쓴이 칸이 없다)이 그대로 내 글로 읽힌다.
    func testSavedPostWithoutAuthorIsStillMine() throws {
        let json = #"{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","board":"장소 후기","title":"t","body":"b","createdAt":0}"#
        let saved = try JSONDecoder().decode(CommunityPost.self, from: Data(json.utf8))
        XCTAssertTrue(saved.isMine)
        XCTAssertEqual(saved.authorName(myNickname: "gil"), "gil")
    }
}
