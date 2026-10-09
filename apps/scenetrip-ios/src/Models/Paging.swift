import Foundation
import SceneApiClient

/// 목록을 **이어 받는** 규칙 (MZ2AZ-372).
///
/// 작품 11편·촬영지 93곳일 때는 「앞쪽 N건이 전부」 였다. 128편·486곳이 되면서 그 가정이 깨졌다 — 작품
/// 목록이 100편에서 끊기고, 찜한 작품이 그 100편 밖이면 목록에서 사라졌다. 이제 한 쪽씩 받아 뒤에 붙이고,
/// **몇 건인지는 서버가 준 `total` 로** 말한다.
///
/// 순수 값이다 — 네트워크가 없다. 무엇을 받았는지 넣으면 「더 있나」「다음은 어디부터」 가 나온다.
struct Paging: Equatable {
    /// 서버가 지금까지 **돌려준** 줄 수. 다음 요청의 `offset` 이다.
    ///
    /// 화면에 쌓인 줄 수와 다를 수 있다 — 받는 사이 순위가 밀려 같은 것이 두 번 오면 한 번만 쌓는다
    /// (`merged`). 그 줄을 빼고 세면 다음 쪽이 한 칸 앞에서 시작해 같은 것을 또 받는다.
    private(set) var offset = 0
    /// 조건에 맞는 전체 수. 첫 쪽을 받기 전에는 모른다.
    private(set) var total: Int?
    /// 빈 쪽을 받았다 — `total` 이 뭐라 하든 더 묻지 않는다(서버 총수와 실제가 어긋나도 돌지 않게).
    private(set) var exhausted = false

    /// 더 받을 것이 있는가. 첫 쪽 전에는 있다고 본다.
    var hasMore: Bool {
        guard !exhausted else { return false }
        guard let total else { return true }
        return offset < total
    }

    /// 한 쪽을 받았다.
    func advanced(received: Int, total: Int) -> Paging {
        var next = self
        next.offset += received
        next.total = total
        next.exhausted = received == 0
        return next
    }

    /// 화면에 적을 총수 — 서버 값. 쌓인 것이 그보다 많으면(받는 사이 늘었다) 쌓인 수.
    func shownTotal(loaded: Int) -> Int {
        max(total ?? 0, loaded)
    }

    /// 새 쪽을 뒤에 붙인다. **이미 있는 것은 다시 넣지 않는다** — 같은 id 가 두 줄이면 목록이 깨진다
    /// (SwiftUI 의 `ForEach` 가 id 로 줄을 가른다).
    static func merged<Item>(
        _ existing: [Item], with page: [Item], by key: (Item) -> some Hashable
    ) -> [Item] {
        var seen = Set(existing.map(key))
        return existing + page.filter { seen.insert(key($0)).inserted }
    }
}

/// 이어 받기 한 번의 끝 — **취소는 실패가 아니다** (MZ2AZ-372).
///
/// 분류 칩을 받는 도중에 끄면 그 일이 취소되고, 취소는 떠 있던 요청까지 끊는다. 그것을 `try?` 로 받으면
/// 「못 받았다」 와 구별이 안 돼 목록 끝에 「더 불러오지 못했어요 · 다시 시도」 가 남았다 — 사용자가 그만두게 한
/// 것을 고장으로 적은 것이다. 취소는 아무 표시 없이 끝낸다.
enum PageFetch<Page> {
    case page(Page)
    case cancelled
    case failed

    static func attempt(_ work: () async throws -> Page) async -> PageFetch<Page> {
        do {
            let page = try await work()
            return Task.isCancelled ? .cancelled : .page(page)
        } catch {
            return Task.isCancelled || PageFetchRules.isCancellation(error) ? .cancelled : .failed
        }
    }
}

enum PageFetchRules {
    /// 이 오류가 「그만두라고 해서 그만둔 것」 인가. 생성 클라이언트는 `URLError` 를 제 오류로 한 겹 싼다.
    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError {
            return true
        }
        if let urlError = error as? URLError {
            return urlError.code == .cancelled
        }
        if case let ErrorResponse.error(_, _, _, underlying) = error {
            return isCancellation(underlying)
        }
        return false
    }
}
