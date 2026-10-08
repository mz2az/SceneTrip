import Foundation

/// 리뷰 사진 한 칸의 상태 (MZ2AZ-363).
enum ReviewPhotoState: Equatable {
    /// 이미 리뷰에 붙어 있던 사진 — 남기려면 이 키를 그대로 다시 보낸다.
    case attached(key: String)
    /// 고르기만 했다. 앞 사진이 올라가기를 기다린다(한 번에 한 장씩 올린다).
    case waiting
    case uploading
    /// 저장소에 올라갔다 — 리뷰를 저장하면 이 키로 붙는다.
    case uploaded(key: String)
    case failed(PhotoUploadFailure)
}

/// 리뷰 사진의 순수 규칙 (MZ2AZ-363). 화면 없이 시험한다.
enum ReviewPhotoRules {
    /// 리뷰 한 개에 붙는 사진의 상한(티켓 「사진 최대 10장」, 계약 `ReviewInput.photoKeys.maxItems`).
    static let limit = 10

    /// 더 고를 수 있는 장수. 올리는 중이거나 실패한 칸도 자리를 차지한다.
    static func remaining(count: Int) -> Int {
        max(0, limit - count)
    }

    /// 고른 것 중 받아들이는 장수 — 남은 자리만큼만. 고르는 창이 상한을 지키지만, 창이 떠 있는 동안
    /// 자리가 줄 수 있어 한 번 더 자른다.
    static func accepted(picked: Int, count: Int) -> Int {
        min(max(0, picked), remaining(count: count))
    }

    /// 서버에 보낼 `photoKeys` — **화면의 순서 그대로**, 붙어 있던 사진과 새로 올라간 사진만.
    /// 보낸 것이 전부다: 여기 없는 사진은 리뷰에서 떨어진다.
    static func keys(_ states: [ReviewPhotoState]) -> [String] {
        let keys = states.compactMap { state -> String? in
            switch state {
            case let .attached(key), let .uploaded(key): key
            case .waiting, .uploading, .failed: nil
            }
        }
        return Array(keys.prefix(limit))
    }

    /// 올리는 중인 사진이 있는가 — 있으면 저장을 막는다(끝나기 전에 저장하면 그 사진이 빠진다).
    static func busy(_ states: [ReviewPhotoState]) -> Bool {
        states.contains { $0 == .waiting || $0 == .uploading }
    }

    /// 못 올린 사진의 수. 있으면 저장을 막는다 — 다시 시도하거나 빼야 한다. 말없이 빼고 저장하지 않는다.
    static func failedCount(_ states: [ReviewPhotoState]) -> Int {
        states.filter { state in
            if case .failed = state {
                return true
            }
            return false
        }.count
    }

    /// 사진 쪽에서 저장을 막을 이유가 없는가.
    static func canSave(_ states: [ReviewPhotoState]) -> Bool {
        !busy(states) && failedCount(states) == 0 && states.count <= limit
    }

    /// 사진을 건드렸는가 — 저장 단추와 「쓰던 리뷰를 버릴까요?」 가 본다. 새로 고른 칸은 올리는 중이든
    /// 실패했든 건드린 것이다. 붙어 있던 사진은 빼거나 순서가 달라졌을 때.
    static func changed(existing: [String], states: [ReviewPhotoState]) -> Bool {
        let untouched = states.allSatisfy { state in
            if case .attached = state {
                return true
            }
            return false
        }
        return !untouched || keys(states) != existing
    }

    /// 멈춰 선 줄을 다시 돌릴 것인가 — 기다리는 사진이 있고, 줄을 멈추게 한 실패(한도·네트워크 등)가 더는 없을 때.
    /// 그 실패 칸을 「다시 시도」 하거나 빼면 그렇게 된다. 빼기만 하고 남은 사진이 영영 기다리지 않게 한다.
    static func shouldResume(_ states: [ReviewPhotoState]) -> Bool {
        let blocked = states.contains { state in
            if case let .failed(failure) = state {
                return PhotoUploadRules.stopsQueue(failure)
            }
            return false
        }
        return states.contains(.waiting) && !blocked
    }

    /// 「사진 올리는 중 2/3」 의 두 수 — **이번에 고른 묶음** 가운데 지금 올리는 것이 몇 번째이고 모두 몇 장인지.
    /// 묶음의 상태만 받는다(앞서 끝난 고르기와 붙어 있던 사진은 부르는 쪽이 뺀다). 실패도 끝난 것으로 센다.
    static func progress(_ batch: [ReviewPhotoState]) -> (current: Int, total: Int) {
        let done = batch.filter { $0 != .waiting && $0 != .uploading }.count
        return (min(done + 1, batch.count), batch.count)
    }
}
