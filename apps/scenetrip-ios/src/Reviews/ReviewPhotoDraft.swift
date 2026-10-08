import PhotosUI
import SceneApiClient
import SwiftUI

/// 쓰는 중인 리뷰의 사진 줄 (MZ2AZ-363) — 붙어 있던 사진과 새로 고른 사진을 화면 순서대로 쥔다.
///
/// **고르는 즉시 올린다**(티켓 「고를 때마다 `POST /uploads` → PUT → `key` 보관」). 저장을 눌렀을 때
/// 열 장을 기다리지 않게 하고, 서명된 주소가 10분짜리라 받아 둔 채 묵히지 않는다.
/// **한 번에 한 장씩** 올린다 — 열 장을 한꺼번에 줄이면 메모리가 튀고, 분당 요청 한도에도 닿는다.
///
/// **한 장이 한도·네트워크처럼 다음 사진도 겪을 이유로 실패하면 줄을 멈춘다**
/// (`PhotoUploadRules.stopsQueue`) — 남은 사진은 기다리는 채로 두고, 실패한 칸을 「다시 시도」 하거나 빼면 잇는다.
///
/// 올렸지만 리뷰에 붙이지 않은 사진(빼거나, 저장하지 않고 닫음)은 서버가 하루 뒤 지운다 — 앱이 따로 지우지 않는다.
@MainActor
final class ReviewPhotoDraft: ObservableObject {
    struct Slot: Identifiable {
        let id = UUID()
        var state: ReviewPhotoState
        /// 붙어 있던 사진의 주소. 한 시간 뒤 만료된다 — 이 화면이 받은 것을 이 화면에서만 쓴다.
        var remoteUrl: String?
        /// 새로 고른 사진의 작은 미리보기.
        var preview: UIImage?
        /// 아직 읽지 않은 고른 것.
        fileprivate var item: PhotosPickerItem?
        /// 줄여 둔 JPEG — 다시 시도할 때 또 읽고 줄이지 않는다.
        fileprivate var data: Data?
        /// 몇 번째 고르기에 든 사진인가 — 진행 수(「2/3」)를 그 묶음으로만 센다.
        fileprivate var batch = 0
    }

    @Published private(set) var slots: [Slot] = []
    /// 올리는 줄이 돌고 있는가. 기다리는 사진이 있는데 돌지 않으면 멈춰 선 것이다.
    @Published private(set) var pumping = false
    /// 열 때 리뷰에 붙어 있던 키 — 바뀌었는지 볼 기준.
    private var existing: [String] = []
    /// 올리는 줄. 그만두게 한 직후에 다시 시켜도 멈춰 서지 않는다(`RestartableLoop`).
    private let loop = RestartableLoop()
    private var batch = 0

    init() {
        loop.onRunning = { [weak self] running in self?.pumping = running }
    }

    var states: [ReviewPhotoState] {
        slots.map(\.state)
    }

    /// 서버에 보낼 `photoKeys`.
    var keys: [String] {
        ReviewPhotoRules.keys(states)
    }

    var changed: Bool {
        ReviewPhotoRules.changed(existing: existing, states: states)
    }

    var canSave: Bool {
        ReviewPhotoRules.canSave(states)
    }

    var remaining: Int {
        ReviewPhotoRules.remaining(count: slots.count)
    }

    /// 줄이 멈춰 기다리기만 하는 사진의 수 — 앞 사진이 실패해 멈췄다. 돌고 있으면 0.
    var stalled: Int {
        pumping ? 0 : states.filter { $0 == .waiting }.count
    }

    /// 이번에 고른 묶음의 진행.
    var progress: (current: Int, total: Int) {
        ReviewPhotoRules.progress(slots.filter { $0.batch == batch }.map(\.state))
    }

    /// 고치는 화면을 열었다 — 붙어 있던 사진으로 채운다.
    func start(with photos: [ReviewPhoto]) {
        existing = photos.map(\.key)
        slots = photos.map { Slot(state: .attached(key: $0.key), remoteUrl: $0.url) }
    }

    /// 보관함에서 골랐다. 남은 자리만큼만 받는다.
    func add(_ items: [PhotosPickerItem]) {
        let accepted = ReviewPhotoRules.accepted(picked: items.count, count: slots.count)
        guard accepted > 0 else { return }
        let batch = joinBatch()
        slots += items.prefix(accepted).map { Slot(state: .waiting, item: $0, batch: batch) }
        pump()
    }

    /// 뺀다. 올리는 중이면 결과를 버린다(올라간 파일은 서버가 하루 뒤 지운다).
    /// 줄을 멈추게 한 칸을 뺐으면 남은 사진을 잇는다.
    func remove(_ id: UUID) {
        slots.removeAll { $0.id == id }
        if ReviewPhotoRules.shouldResume(states) {
            pump()
        }
    }

    /// 실패한 칸을 다시 올린다. 줄이 멈춰 있었으면 이 칸부터, 기다리던 사진까지 이어서 돈다.
    func retry(_ id: UUID) {
        guard let slot = slots.first(where: { $0.id == id }),
              case let .failed(failure) = slot.state, PhotoUploadRules.retryable(failure)
        else { return }
        let batch = joinBatch()
        update(id) {
            $0.state = .waiting
            $0.batch = batch
        }
        pump()
    }

    /// 리뷰 저장 때 서버가 사진 키를 거절했다(`REVIEW_PHOTO_INVALID`) — 이번에 올린 것들을 다시 올리게 한다.
    /// 붙어 있던 사진은 건드리지 않는다.
    func rejectUploaded() {
        for slot in slots {
            if case .uploaded = slot.state {
                update(slot.id) { $0.state = .failed(.expired) }
            }
        }
    }

    /// 쓰기 화면이 사라졌다(저장·버리기·닫기) — 올리던 것을 그만둔다. 이미 올라간 키는 그대로다(서버에 있다).
    func cancel() {
        loop.cancel()
    }

    /// 그만뒀던 줄을 잇는다 — 줄을 멈추게 한 실패가 없을 때만.
    func resume() {
        if ReviewPhotoRules.shouldResume(states) {
            pump()
        }
    }

    /// 새 묶음인가 — 줄이 비어 있을 때 고르면 새 묶음, 도는 중에 더 고르면 같은 묶음에 얹는다.
    private func joinBatch() -> Int {
        if !ReviewPhotoRules.busy(states) {
            batch += 1
        }
        return batch
    }

    /// 올리는 줄을 돌린다. 이미 돌고 있으면 그 줄이 새 칸까지 올린다 — 그만두는 중이었으면 끝난 뒤 다시 돈다.
    private func pump() {
        loop.start { [weak self] in await self?.drain() }
    }

    /// 기다리는 칸을 앞에서부터 하나씩 올린다.
    private func drain() async {
        while !Task.isCancelled, let id = slots.first(where: { $0.state == .waiting })?.id {
            update(id) { $0.state = .uploading }
            let result = await upload(id)
            if Task.isCancelled {
                // 그만두는 중이다 — 실패로 적지 않는다. 화면이 아직 살아 있으면 다음 고르기·다시 시도가 잇는다.
                update(id) { $0.state = .waiting }
                return
            }
            // 올리는 사이 뺐으면 칸이 없다 — 결과를 버린다(그 실패로 줄을 멈추지도 않는다).
            guard let result, slots.contains(where: { $0.id == id }) else { continue }
            switch result {
            case let .success(key):
                update(id) { $0.state = .uploaded(key: key) }
            case let .failure(failure):
                update(id) { $0.state = .failed(failure) }
                if PhotoUploadRules.stopsQueue(failure) {
                    // 다음 사진도 같은 이유로 실패한다 — 연달아 실패시키지 않고 멈춘다.
                    return
                }
            }
        }
    }

    /// 한 칸을 올린다. 그 사이 칸을 뺐거나 그만두게 됐으면 nil — 올리지 않는다.
    private func upload(_ id: UUID) async -> Result<String, PhotoUploadFailure>? {
        guard let slot = slots.first(where: { $0.id == id }) else { return nil }
        if let data = slot.data {
            return await PhotoUploader.upload(data)
        }
        guard let item = slot.item, let file = await PhotoPick.file(from: item) else {
            return .failure(.unreadable)
        }
        // 풀고 줄이는 일은 무겁다 — 화면 밖에서 한다.
        let made = await Task.detached(priority: .userInitiated) {
            (jpeg: PhotoUploader.prepare(file), preview: PhotoShrink.preview(from: file, side: PhotoPick.tile * 3))
        }.value
        // 줄이는 사이 뺐으면 올리지 않는다.
        guard !Task.isCancelled, slots.contains(where: { $0.id == id }) else { return nil }
        update(id) { $0.preview = made.preview }
        switch made.jpeg {
        case let .failure(failure):
            return .failure(failure)
        case let .success(data):
            update(id) {
                $0.data = data
                $0.item = nil
            }
            return await PhotoUploader.upload(data)
        }
    }

    private func update(_ id: UUID, _ change: (inout Slot) -> Void) {
        guard let index = slots.firstIndex(where: { $0.id == id }) else { return }
        change(&slots[index])
    }
}
