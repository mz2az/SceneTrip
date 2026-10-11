/// 사용법의 페이지 범위와 한 번만 완료하는 규칙(MZ2AZ-399).
struct OnboardingProgress {
    let count: Int
    private(set) var page = 0
    private var finished = false

    init(count: Int) {
        precondition(count > 0)
        self.count = count
    }

    var isLast: Bool {
        page == count - 1
    }

    mutating func select(_ value: Int) {
        guard !finished else { return }
        page = min(count - 1, max(0, value))
    }

    mutating func next() {
        select(page + 1)
    }

    mutating func previous() {
        select(page - 1)
    }

    /// 건너뛰기도 완료도 한 번만 기록한다.
    mutating func finish() -> Bool {
        guard !finished else { return false }
        finished = true
        return true
    }
}
