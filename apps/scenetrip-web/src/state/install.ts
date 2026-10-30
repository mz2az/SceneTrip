/*
 * 이 설치본을 가리키는 값 — iOS `InstallIdentity` (Models/InstallIdentity.swift).
 * 서버가 「누구의 장바구니·코스인가」를 이 값으로 가른다. 계약의 `X-Install-Id` 헤더로 나간다.
 *
 * 기기가 아니라 설치본이다: 브라우저 데이터를 지우면 새 값이 되고, 같은 사람의 다른 브라우저는 다른 사람으로 보인다.
 * (로그인하면 서버가 계정으로 합친다.) 최초 실행에 만들어 localStorage 에 보관하고 이후로는 같은 값을 돌려준다.
 */
import { readStorage, writeStorage } from './storage'

const KEY = 'scenetrip.installId'

function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  // randomUUID 가 없는 오래된 환경 — RFC 4122 v4
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

let cached: string | null = null

export function installId(): string {
  if (cached) return cached
  const saved = readStorage(KEY)
  if (saved && /^[0-9a-f-]{36}$/i.test(saved)) {
    cached = saved
    return saved
  }
  cached = uuid()
  writeStorage(KEY, cached)
  return cached
}

/** `params.header` 에 그대로 펼쳐 쓴다 — 계약이 요구하는 창구마다 X-Install-Id 를 필수 헤더로 선언한다. */
export function installHeader(): { 'X-Install-Id': string } {
  return { 'X-Install-Id': installId() }
}
