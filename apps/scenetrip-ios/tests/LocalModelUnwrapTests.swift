@testable import SceneTrip
import XCTest

/// gpt-oss 의 채널 표식을 **답만 남기고** 푸는가 (2026-09-05).
///
/// mlx_lm 0.31 서버는 `<|channel|>analysis…<|channel|>final<|message|>답` 을 content 에
/// 그대로 넣는다. 안 풀면 코스 플래너가 「생각」을 JSON 으로 읽다 실패해 늘 규칙 경로로
/// 떨어진다 — 화면에는 티가 안 나서 검사로 고정한다.
final class LocalModelUnwrapTests: XCTestCase {
    func testFinalChannelOnly() {
        let raw = "<|channel|>analysis<|message|>Need one word.<|end|>"
            + "<|start|>assistant<|channel|>final<|message|>한강"
        XCTAssertEqual(LocalModel.unwrap(raw), "한강")
    }

    func testFinalStopsAtEndToken() {
        let raw = "<|channel|>final<|message|>[1, 2]<|return|>"
        XCTAssertEqual(LocalModel.unwrap(raw), "[1, 2]")
    }

    func testPlainContentUntouched() {
        XCTAssertEqual(LocalModel.unwrap("그냥 답"), "그냥 답")
    }

    func testThoughtWithoutAnswerIsNil() {
        XCTAssertNil(LocalModel.unwrap("<|channel|>analysis<|message|>잘린 생각"))
    }
}
