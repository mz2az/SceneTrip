// iOS ReviewPhotoDraft / ReviewPhotoRules / PhotoUploadRules 의 규칙 (여행후기 8 장)
import { describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { createPhotoDraft } from './photoDraft'
import {
  type PhotoState, accepted, busy, canSave, failedCount, keys, progress, retryable, shouldResume, stopsQueue, uploadFailure,
} from './photoRules'
import type { PhotoUploader, UploadResult } from './photoUpload'
import { fittedSize } from './photoUpload'

const file = (name: string) => new File(['x'], name, { type: 'image/jpeg' })
const flush = async () => {
  for (let i = 0; i < 20; i += 1) await Promise.resolve()
}

class FakeUploader implements PhotoUploader {
  results: UploadResult[] = []
  uploads = 0
  gate: Promise<void> | null = null
  async prepare(f: Blob) {
    return { ok: true as const, blob: f }
  }
  async upload() {
    this.uploads += 1
    if (this.gate) await this.gate
    return this.results.shift() ?? { ok: true as const, key: `uploads/tmp/k${this.uploads}.jpg` }
  }
}

const draftWith = (uploader: PhotoUploader) => createPhotoDraft({ uploader, makePreview: () => undefined })

describe('사진 규칙', () => {
  const att: PhotoState = { kind: 'attached', key: 'a' }
  const up: PhotoState = { kind: 'uploaded', key: 'b' }
  const fail = (failure: Parameters<typeof stopsQueue>[0]): PhotoState => ({ kind: 'failed', failure })

  it('보낼 키는 화면 순서 그대로, 올라간 것만, 8 장까지', () => {
    expect(keys([att, { kind: 'waiting' }, up, fail('network')])).toEqual(['a', 'b'])
    expect(keys(Array.from({ length: 10 }, (_, i) => ({ kind: 'uploaded', key: `k${i}` }) as PhotoState))).toHaveLength(8)
  })

  it('올리는 중이거나 실패한 사진이 있으면 저장하지 못한다', () => {
    expect(canSave([att, up])).toBe(true)
    expect(busy([{ kind: 'uploading' }])).toBe(true)
    expect(canSave([att, { kind: 'waiting' }])).toBe(false)
    expect(failedCount([fail('tooLarge')])).toBe(1)
    expect(canSave([fail('tooLarge')])).toBe(false)
  })

  it('남은 자리만큼만 받는다', () => {
    expect(accepted(5, 6)).toBe(2)
    expect(accepted(1, 8)).toBe(0)
  })

  it('한도·네트워크 실패는 줄을 멈추고, 사진 자체의 문제는 그 칸만의 일이다', () => {
    for (const f of ['rateLimited', 'unavailable', 'signedOut', 'storage', 'network'] as const) expect(stopsQueue(f)).toBe(true)
    for (const f of ['unreadable', 'tooLarge', 'unsupportedType', 'expired'] as const) expect(stopsQueue(f)).toBe(false)
    expect(retryable('tooLarge')).toBe(false)
    expect(retryable('network')).toBe(true)
  })

  it('멈춘 줄은 멈추게 한 실패가 없어졌을 때만 잇는다', () => {
    expect(shouldResume([{ kind: 'waiting' }, fail('network')])).toBe(false)
    expect(shouldResume([{ kind: 'waiting' }, fail('tooLarge')])).toBe(true)
    expect(shouldResume([att])).toBe(false)
  })

  it('진행은 이번 묶음만, 실패도 끝난 것으로 센다', () => {
    expect(progress([up, { kind: 'uploading' }, { kind: 'waiting' }])).toEqual({ current: 2, total: 3 })
    expect(progress([up, fail('network')])).toEqual({ current: 2, total: 2 })
  })

  it('업로드 오류는 code 로 가른다', () => {
    expect(uploadFailure(new ApiFailure({ statusCode: 400, apiCode: 'UPLOAD_TOO_LARGE' }))).toBe('tooLarge')
    expect(uploadFailure(new ApiFailure({ statusCode: 503, apiCode: 'UPLOAD_UNAVAILABLE' }))).toBe('unavailable')
    expect(uploadFailure(new ApiFailure({ statusCode: 401 }))).toBe('signedOut')
    expect(uploadFailure(new ApiFailure({ statusCode: 429 }))).toBe('rateLimited')
    expect(uploadFailure(new Error('x'))).toBe('network')
  })

  it('올릴 그림은 긴 변 2048 을 넘지 않게 줄인다', () => {
    expect(fittedSize(4096, 2048)).toEqual({ width: 2048, height: 1024 })
    expect(fittedSize(800, 600)).toEqual({ width: 800, height: 600 })
  })
})

describe('PhotoDraft', () => {
  it('고르는 즉시 한 장씩 올리고 키를 모은다', async () => {
    const uploader = new FakeUploader()
    const draft = draftWith(uploader)
    draft.add([file('a'), file('b')])
    await flush()
    expect(uploader.uploads).toBe(2)
    expect(draft.keys()).toEqual(['uploads/tmp/k1.jpg', 'uploads/tmp/k2.jpg'])
    expect(draft.canSave()).toBe(true)
    expect(draft.store.get().pumping).toBe(false)
  })

  it('자리가 없으면 더 받지 않는다(8 장)', async () => {
    const draft = draftWith(new FakeUploader())
    draft.add(Array.from({ length: 10 }, (_, i) => file(`p${i}`)))
    await flush()
    expect(draft.store.get().slots).toHaveLength(8)
    expect(draft.remaining()).toBe(0)
  })

  it('한 장이 네트워크로 실패하면 줄이 멈추고, 다시 시도하면 이어서 올린다', async () => {
    const uploader = new FakeUploader()
    uploader.results = [{ ok: false, failure: 'network' }]
    const draft = draftWith(uploader)
    draft.add([file('a'), file('b')])
    await flush()
    const [first, second] = draft.store.get().slots
    expect(first!.state).toEqual({ kind: 'failed', failure: 'network' })
    expect(second!.state).toEqual({ kind: 'waiting' })
    expect(draft.stalled()).toBe(1)
    expect(draft.canSave()).toBe(false)
    draft.retry(first!.id)
    await flush()
    expect(draft.store.get().slots.map((s) => s.state.kind)).toEqual(['uploaded', 'uploaded'])
    expect(draft.canSave()).toBe(true)
  })

  it('멈추게 한 칸을 빼면 남은 사진을 잇는다', async () => {
    const uploader = new FakeUploader()
    uploader.results = [{ ok: false, failure: 'rateLimited' }]
    const draft = draftWith(uploader)
    draft.add([file('a'), file('b')])
    await flush()
    draft.remove(draft.store.get().slots[0]!.id)
    await flush()
    expect(draft.store.get().slots.map((s) => s.state.kind)).toEqual(['uploaded'])
  })

  it('사진 자체의 문제는 줄을 멈추지 않고 다시 시도할 수도 없다', async () => {
    const uploader = new FakeUploader()
    uploader.results = [{ ok: false, failure: 'tooLarge' }]
    const draft = draftWith(uploader)
    draft.add([file('a'), file('b')])
    await flush()
    const slots = draft.store.get().slots
    expect(slots[0]!.state).toEqual({ kind: 'failed', failure: 'tooLarge' })
    expect(slots[1]!.state.kind).toBe('uploaded')
    draft.retry(slots[0]!.id)
    expect(draft.store.get().slots[0]!.state.kind).toBe('failed')
  })

  it('올리는 사이 뺀 사진의 결과는 버린다', async () => {
    const uploader = new FakeUploader()
    let open: () => void = () => {}
    uploader.gate = new Promise<void>((r) => (open = r))
    const draft = draftWith(uploader)
    draft.add([file('a')])
    await flush()
    draft.remove(draft.store.get().slots[0]!.id)
    open()
    await flush()
    expect(draft.store.get().slots).toEqual([])
    expect(draft.keys()).toEqual([])
  })

  it('그만두면 올리던 칸은 기다림으로 돌아가고 resume 이 잇는다', async () => {
    const uploader = new FakeUploader()
    let open: () => void = () => {}
    uploader.gate = new Promise<void>((r) => (open = r))
    const draft = draftWith(uploader)
    draft.add([file('a')])
    await flush()
    draft.cancel()
    open()
    await flush()
    expect(draft.store.get().slots[0]!.state.kind).toBe('waiting')
    uploader.gate = null
    draft.resume()
    await flush()
    expect(draft.store.get().slots[0]!.state.kind).toBe('uploaded')
  })

  it('저장 때 키가 거절되면 올린 것들을 다시 올리게 한다', async () => {
    const draft = draftWith(new FakeUploader())
    draft.add([file('a')])
    await flush()
    draft.rejectUploaded()
    expect(draft.store.get().slots[0]!.state).toEqual({ kind: 'failed', failure: 'expired' })
    draft.retry(draft.store.get().slots[0]!.id)
    await flush()
    expect(draft.store.get().slots[0]!.state.kind).toBe('uploaded')
  })
})
