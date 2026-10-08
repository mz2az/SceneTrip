@testable import SceneTrip
import XCTest

/// 올리는 줄을 돌리는 것 (MZ2AZ-363) — 그만두게 한 직후에 다시 시켜도 멈춰 서지 않는다.
@MainActor
final class RestartableLoopTests: XCTestCase {
    /// 돌던 일이 끝날 때를 시험이 정한다.
    private final class Gate {
        private var waiting: CheckedContinuation<Void, Never>?
        private(set) var entered = 0

        func wait() async {
            entered += 1
            await withCheckedContinuation { waiting = $0 }
        }

        func open() {
            waiting?.resume()
            waiting = nil
        }
    }

    private func settle() async {
        for _ in 0 ..< 20 {
            await Task.yield()
        }
    }

    /// 검증 소견의 경합: `cancel()` 직후 `start()` — 앞의 것이 「돌고 있다」 며 돌려보내면 아무도 돌지 않는다.
    func testStartRightAfterCancelRunsOnceTheOldOneEnds() async {
        let loop = RestartableLoop()
        let gate = Gate()
        var runs: [String] = []

        loop.start {
            await gate.wait()
            runs.append("first")
        }
        await settle()
        XCTAssertTrue(loop.running)

        loop.cancel()
        // 그만두라고 했지만 하던 요청이 끝나지 않아 아직 돌고 있다.
        XCTAssertTrue(loop.running)
        loop.start { runs.append("second") }
        XCTAssertEqual(runs, [], "앞의 것이 끝나기 전에 겹쳐 돌지 않는다")

        gate.open()
        await settle()
        XCTAssertEqual(runs, ["first", "second"])
        XCTAssertFalse(loop.running)
    }

    /// 그만두는 중이 아니면 — 돌고 있는 줄이 새 일을 스스로 찾으므로 또 돌리지 않는다.
    func testStartWhileRunningDoesNotStack() async {
        let loop = RestartableLoop()
        let gate = Gate()
        var runs = 0

        loop.start {
            await gate.wait()
            runs += 1
        }
        await settle()
        loop.start { runs += 10 }
        gate.open()
        await settle()
        XCTAssertEqual(runs, 1)
        XCTAssertFalse(loop.running)
    }

    /// 다시 시킨 뒤에 또 그만두게 하면 다시 돌지 않는다(화면이 사라졌다).
    func testCancelAfterAQueuedRestartDropsIt() async {
        let loop = RestartableLoop()
        let gate = Gate()
        var runs = 0

        loop.start { await gate.wait() }
        await settle()
        loop.cancel()
        loop.start { runs += 1 }
        loop.cancel()
        gate.open()
        await settle()
        XCTAssertEqual(runs, 0)
        XCTAssertFalse(loop.running)
    }

    /// 끝난 뒤에는 다시 돌릴 수 있고, 돌고 서는 것이 `onRunning` 으로 알려진다.
    func testRunningIsReported() async {
        let loop = RestartableLoop()
        var seen: [Bool] = []
        loop.onRunning = { seen.append($0) }

        loop.start {}
        await settle()
        loop.start {}
        await settle()
        XCTAssertEqual(seen, [true, false, true, false])
    }
}
