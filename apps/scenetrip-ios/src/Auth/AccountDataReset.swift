import Combine

/// 로그아웃·탈퇴·비회원 기록 삭제 때 화면 사본을 먼저 비운다. 재조회 실패도 옛 데이터를 되살리지 않는다.
@MainActor
enum AccountDataReset {
    private(set) static var generation = 0
    static let changes = PassthroughSubject<Void, Never>()

    static func clear() {
        generation += 1
        changes.send()
    }
}
