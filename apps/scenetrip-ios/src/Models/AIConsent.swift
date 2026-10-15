import Combine
import Foundation

/// 동의 저장소를 주입해 기기 설정·네트워크 없이 시험한다(MZ2AZ-468).
protocol AIConsentStorage: AnyObject {
    func integer(forKey key: String) -> Int
    func set(_ value: Int, forKey key: String)
}

extension UserDefaults: AIConsentStorage {}

/// 두 AI 기능이 공유하는 동의. 전송 스레드에서도 읽으므로 저장소 접근을 잠근다.
final class AIConsentStore: @unchecked Sendable {
    static let shared = AIConsentStore(storage: UserDefaults.standard)
    static let key = "scenetrip.aiConsentVersion"
    /// 안내 내용이 바뀌면 올린다. 이전 버전의 허용은 새 안내의 동의가 아니다.
    static let currentVersion = 1
    private let storage: AIConsentStorage
    private let version: Int
    // 시험 세션은 resume 안에서 완료·재시도를 동기 호출할 수 있으므로 재진입을 허용한다.
    private let lock = NSRecursiveLock()

    init(storage: AIConsentStorage, version: Int = currentVersion) {
        precondition(version > 0)
        self.storage = storage
        self.version = version
    }

    var isAllowed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return storage.integer(forKey: Self.key) == version
    }

    func allow() {
        save(version)
    }

    func revoke() {
        save(0)
    }

    /// 철회와 전송 시작을 같은 잠금으로 묶는다. start는 await 없이 요청만 시작한다.
    func startIfAllowed(_ start: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard storage.integer(forKey: Self.key) == version else { return false }
        start()
        return true
    }

    private func save(_ value: Int) {
        lock.lock()
        defer { lock.unlock() }
        storage.set(value, forKey: Self.key)
    }

    static func requiresConsent(method: String, path: String) -> Bool {
        method.uppercased() == "POST"
            && ["/guide/chat", "/guide/plan"].contains { path.hasSuffix($0) }
    }
}

/// HTTP 실패가 아닌 로컬 차단이다. 네트워크 전송을 시작하기 전에 반환한다.
struct AIConsentRequired: Error, Equatable {}

/// 화면마다 대기 중 동작만 들고, 동의 자체는 공통 저장소에서 읽는다.
@MainActor
final class AIConsentGate: ObservableObject {
    @Published var isPresented = false
    private let store: AIConsentStore
    private var pending: (() -> Void)?
    private var approved = false

    init(store: AIConsentStore = .shared) {
        self.store = store
    }

    func request(_ action: @escaping () -> Void) {
        guard pending == nil else { return }
        guard store.isAllowed else {
            pending = action
            approved = false
            isPresented = true
            return
        }
        action()
    }

    func allow() {
        guard isPresented, pending != nil else { return }
        store.allow()
        approved = true
        isPresented = false
    }

    func cancel() {
        pending = nil
        approved = false
        isPresented = false
    }

    /// 끌어 내려 닫기도 취소다. 명시적으로 허용한 경우에만 닫힌 뒤 한 번 진행한다.
    func didDismiss() {
        let action = approved && store.isAllowed ? pending : nil
        cancel()
        action?()
    }

    /// 설정에서 켤 때도 안내를 읽고 허용해야 한다.
    func setEnabled(_ enabled: Bool) {
        if enabled {
            request {}
        } else {
            cancel()
            store.revoke()
        }
    }
}
