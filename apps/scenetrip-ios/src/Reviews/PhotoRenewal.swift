import Foundation

/// MZ2AZ-363·428: 한 화면의 주소 갱신을 공유하고 자동 갱신을 30초에 한 번으로 제한한다.
@MainActor
final class PhotoRenewal {
    private let now: () -> Date
    private var last: Date?
    private var running: Task<Bool, Never>?
    private var generation = 0

    init(now: @escaping () -> Date = Date.init) {
        self.now = now
    }

    func run(forced: Bool, _ work: @escaping @MainActor () async throws -> Void) async -> Bool {
        if let running {
            return await running.value
        }
        guard PhotoGalleryRules.shouldRenew(forced: forced, last: last, now: now()) else { return false }
        last = now()
        let asked = generation
        let task = Task {
            do { try await work(); return !Task.isCancelled }
            catch { return false }
        }
        running = task
        let success = await task.value
        guard asked == generation else { return false }
        running = nil
        return success
    }

    func cancel() {
        generation += 1
        running?.cancel()
        running = nil
        last = nil
    }
}

enum PhotoPages {
    struct Page<Row> {
        let items: [Row]
        let total: Int
    }

    /// 리뷰·내 리뷰에서 현재 읽은 범위의 새 주소를 받는다.
    static func refetch<Row>(wanted: Int, id: (Row) -> Int64,
                             fetch: (Int) async throws -> Page<Row>) async throws -> Page<Row>
    {
        var rows: [Row] = []
        var seen: Set<Int64> = []
        var offset = 0
        var total = 0
        repeat {
            try Task.checkCancellation()
            let page = try await fetch(offset)
            total = page.total
            for row in page.items where seen.insert(id(row)).inserted {
                rows.append(row)
            }
            offset += page.items.count
            if page.items.isEmpty || offset >= total {
                break
            }
        } while offset < max(wanted, ReviewSubject.pageSize)
        return Page(items: rows, total: total)
    }
}
