// 설치 번호 갈아 끼우기 — 가입 계정에 묶인 설치 번호를 버리고 새 번호를 쓴다(renewInstallId).
// installId() 는 모듈 변수에 값을 기억한다. 시험 순서에 기대지 않도록 늘 「지금 installId() 가 주는 값」을 기준으로 잰다.
import { describe, expect, it } from 'vitest'
import { installId, renewInstallId } from './install'

const KEY = 'scenetrip.installId'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

describe('renewInstallId', () => {
  it('지금 번호를 버리라고 하면 새 UUID 를 만들어 저장하고 그것을 돌려준다', () => {
    const before = installId()
    const after = renewInstallId(before)
    expect(after).not.toBe(before)
    expect(after).toMatch(UUID)
    expect(installId()).toBe(after)
    expect(localStorage.getItem(KEY)).toBe(after)
  })

  it('이미 다른 번호로 바뀌었으면(낡은 번호를 버리라는 요청) 그대로 두고 지금 번호를 돌려준다', () => {
    const current = installId()
    const stale = '00000000-0000-4000-8000-000000000000'
    expect(current).not.toBe(stale)
    expect(renewInstallId(stale)).toBe(current)
    expect(installId()).toBe(current)
  })

  it('같은 낡은 번호로 두 번 불러도 한 번만 바뀐다 — 동시에 401 을 받은 요청들이 같은 새 번호를 쓴다', () => {
    const before = installId()
    const first = renewInstallId(before)
    const second = renewInstallId(before)
    expect(first).not.toBe(before)
    expect(second).toBe(first)
    expect(installId()).toBe(first)
  })
})
