import { describe, expect, it } from 'vitest'
import { createConsentGate, createConsentStore, memoryConsentStorage } from './consent'

const KEY = 'k'

// iOS tests/AIConsentTests.swift · LocationConsentTests.swift (게이트·저장소 부분)
describe('AIConsentGate', () => {
  it('처음 쓸 때 시트를 올리고 동작은 아직 돌지 않는다', () => {
    const gate = createConsentGate(createConsentStore(memoryConsentStorage(), KEY, 1))
    let proceeded = false
    expect(gate.state().isPresented).toBe(false) // 앱 시작에는 묻지 않는다
    gate.request(() => (proceeded = true))
    expect(gate.state().isPresented).toBe(true)
    expect(proceeded).toBe(false)
  })

  it('허용하면 시트가 닫힌 뒤 한 번만 진행하고, 다른 문도 이미 허용된 것으로 본다', () => {
    const storage = memoryConsentStorage()
    const chat = createConsentGate(createConsentStore(storage, KEY, 1))
    let proceeds = 0
    chat.request(() => (proceeds += 1))
    chat.allow()
    expect(proceeds).toBe(0) // 시트가 닫힌 뒤 진행한다
    chat.didDismiss()
    chat.didDismiss()
    expect(proceeds).toBe(1)
    const planner = createConsentGate(createConsentStore(storage, KEY, 1))
    planner.request(() => (proceeds += 1))
    expect(planner.state().isPresented).toBe(false)
    expect(proceeds).toBe(2)
  })

  it('취소와 바깥 눌러 닫기는 대기 동작을 버리고 동의하지 않는다', () => {
    const store = createConsentStore(memoryConsentStorage(), KEY, 1)
    const gate = createConsentGate(store)
    let ran = 0
    for (const explicit of [true, false]) {
      gate.request(() => (ran += 1))
      if (explicit) gate.cancel()
      else gate.swipeDismiss()
      gate.didDismiss()
      expect(ran).toBe(0)
      expect(store.isAllowed()).toBe(false)
    }
    gate.request(() => (ran += 1))
    gate.allow()
    gate.didDismiss()
    expect(ran).toBe(1)
  })

  it('설정에서 켜기만으로는 동의가 아니다 — 취소하면 그대로 꺼져 있고, 끄면 철회하고 다시 묻는다', () => {
    const store = createConsentStore(memoryConsentStorage(), KEY, 1)
    const gate = createConsentGate(store)
    gate.setEnabled(true)
    expect(store.isAllowed()).toBe(false)
    gate.cancel()
    gate.didDismiss()
    expect(store.isAllowed()).toBe(false)
    gate.setEnabled(true)
    gate.allow()
    gate.didDismiss()
    expect(store.isAllowed()).toBe(true)
    gate.setEnabled(false)
    expect(store.isAllowed()).toBe(false)
    gate.request(() => {
      throw new Error('철회 뒤 동의 없이 진행')
    })
    expect(gate.state().isPresented).toBe(true)
  })

  it('안내 버전이 바뀌면 새 동의가 필요하다', () => {
    const storage = memoryConsentStorage()
    const original = createConsentStore(storage, KEY, 1)
    original.allow()
    expect(createConsentStore(storage, KEY, 1).isAllowed()).toBe(true)
    const updated = createConsentStore(storage, KEY, 2)
    const gate = createConsentGate(updated)
    gate.request(() => {})
    expect(gate.state().isPresented).toBe(true)
    gate.allow()
    gate.didDismiss()
    expect(updated.isAllowed()).toBe(true)
    expect(original.isAllowed()).toBe(false) // 다른 버전을 묵시적으로 허용하지 않는다
  })

  it('허용한 뒤 시트가 닫히기 전에 철회하면 대기 동작은 돌지 않는다', () => {
    const store = createConsentStore(memoryConsentStorage(), KEY, 1)
    const gate = createConsentGate(store)
    gate.request(() => {
      throw new Error('허용 후 철회되면 진행하지 않는다')
    })
    gate.allow()
    store.revoke()
    gate.didDismiss()
    gate.request(() => {})
    expect(gate.state().isPresented).toBe(true)
  })

  it('위치 동의는 AI 동의와 따로 저장된다', () => {
    const storage = memoryConsentStorage()
    const ai = createConsentStore(storage, 'ai', 1)
    const location = createConsentStore(storage, 'loc', 1)
    ai.allow()
    expect(location.isAllowed()).toBe(false)
    location.allow()
    location.revoke()
    expect(ai.isAllowed()).toBe(true)
  })

  it('저장소가 바뀌면 구독자에게 알린다', () => {
    const store = createConsentStore(memoryConsentStorage(), KEY, 1)
    let calls = 0
    store.subscribe(() => (calls += 1))
    store.allow()
    store.revoke()
    expect(calls).toBe(2)
  })
})
