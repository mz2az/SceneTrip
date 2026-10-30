/*
 * 네이버 지도 웹 SDK 스크립트를 **한 번만** 동적으로 주입한다. npm 패키지가 아니라 이 주소의 스크립트다.
 *
 *   https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=<클라이언트 ID>
 *
 * 인증 파라미터 이름은 `ncpKeyId` — 옛 `ncpClientId`·`govClientId`·`finClientId` 는 일반 콘솔로 통합되며 `ncpKeyId` 가 되었고 ID 도 다시 발급해야 한다.
 * 근거: https://navermaps.github.io/maps.js.ncp/docs/tutorial-2-Getting-Started.html
 * 서비스 URL(localhost·배포 도메인)이 콘솔에 등록돼 있지 않으면 스크립트는 로드돼도 인증이 실패한다 — `navermap_authFailure` 로 받아 실패로 친다.
 */
import type { NaverMaps } from './naver'

export const NAVER_SCRIPT_BASE = 'https://oapi.map.naver.com/openapi/v3/maps.js'
const LOAD_TIMEOUT_MS = 10_000

let pending: { clientId: string; promise: Promise<NaverMaps> } | null = null

export function naverScriptUrl(clientId: string): string {
  return `${NAVER_SCRIPT_BASE}?ncpKeyId=${encodeURIComponent(clientId)}`
}

/** 스크립트를 넣고 `window.naver.maps` 를 돌려준다. 같은 ID 로는 몇 번을 불러도 `<script>` 는 한 번만 들어간다. 실패하면 거절. */
export function loadNaverMaps(clientId: string): Promise<NaverMaps> {
  if (pending && pending.clientId === clientId) return pending.promise
  const promise = new Promise<NaverMaps>((resolve, reject) => {
    if (typeof document === 'undefined') {
      reject(new Error('no document'))
      return
    }
    if (window.naver?.maps) {
      resolve(window.naver.maps)
      return
    }
    const script = document.createElement('script')
    let settled = false
    const done = (fn: () => void) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      fn()
    }
    const fail = (why: string) =>
      done(() => {
        script.remove()
        reject(new Error(why))
      })
    const timer = window.setTimeout(() => fail('timeout'), LOAD_TIMEOUT_MS)
    window.navermap_authFailure = () => fail('auth')
    script.src = naverScriptUrl(clientId)
    script.async = true
    script.onload = () => {
      const maps = window.naver?.maps
      if (maps) done(() => resolve(maps))
      else fail('no-maps')
    }
    script.onerror = () => fail('load')
    document.head.appendChild(script)
  })
  // 실패하면 다음 호출이 다시 시도할 수 있게 비워 둔다(그래도 ID 가 같은 성공은 `window.naver.maps` 로 바로 풀린다).
  promise.catch(() => {
    if (pending?.promise === promise) pending = null
  })
  pending = { clientId, promise }
  return promise
}

/** 시험용 — 모듈 상태를 되돌린다. */
export function resetNaverScriptForTests(): void {
  pending = null
}
