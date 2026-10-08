import Foundation

/// 한 번에 하나만 도는 일 — 그만두게 한 직후에 다시 시키면 **앞의 것이 끝난 뒤 이어서** 돈다.
///
/// 리뷰 사진을 올리는 줄(`ReviewPhotoDraft`)이 쓴다. 그만두라고 해도(`cancel`) 돌던 일은 바로 서지 않는다 —
/// 하던 요청이 끝나야 선다. 그 사이에 「다시 돌려라」(`start`) 가 오면, 전에는 「이미 돌고 있다」 며 돌려보냈고
/// 돌던 일은 곧 그만둬 **아무도 돌지 않는 채로 기다리는 사진만 남았다**(2026-10-08 검증 소견).
/// 여기서는 그 요청을 적어 뒀다가 앞의 것이 끝나면 다시 돌린다.
@MainActor
final class RestartableLoop {
    /// 돌고 있는가(그만두는 중인 것 포함). 바뀔 때 `onRunning` 이 불린다.
    private(set) var running = false {
        didSet {
            if running != oldValue {
                onRunning(running)
            }
        }
    }

    var onRunning: (Bool) -> Void = { _ in }
    private var task: Task<Void, Never>?
    /// 그만두는 중에 다시 시켰다 — 끝나면 이것으로 다시 돈다.
    private var pending: (@MainActor () async -> Void)?

    /// 돌린다. 이미 돌고 있으면 아무것도 하지 않는다 — 도는 일이 제 할 일을 스스로 찾는다.
    /// **그만두는 중**이면 그것이 끝난 뒤에 돈다.
    func start(_ body: @escaping @MainActor () async -> Void) {
        if running {
            if task?.isCancelled == true {
                pending = body
            }
            return
        }
        running = true
        task = Task { [weak self] in
            await body()
            self?.finished()
        }
    }

    /// 그만두게 한다. 적어 둔 「다시 돌려라」 도 버린다 — 그 뒤에 온 `start` 만 산다.
    func cancel() {
        pending = nil
        task?.cancel()
    }

    private func finished() {
        running = false
        task = nil
        if let next = pending {
            pending = nil
            start(next)
        }
    }
}
