import Foundation

/// MZ2AZ-363·366: 같은 계정/토큰의 갱신만 공유한다. 새 로그인은 옛 갱신을 기다리지 않는다.
actor SessionFlight<Key: Hashable & Sendable, Value: Sendable> {
    private var running: [Key: (id: UUID, task: Task<Value, Error>)] = [:]

    func run(key: Key, _ work: @escaping @Sendable () async throws -> Value) async throws -> Value {
        if let current = running[key] {
            return try await current.task.value
        }
        let id = UUID()
        let task = Task { try await work() }
        running[key] = (id, task)
        defer {
            if running[key]?.id == id {
                running[key] = nil
            }
        }
        return try await task.value
    }
}
