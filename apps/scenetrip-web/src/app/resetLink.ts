/*
 * 시험용 초기화 링크 `?reset=1` — 인스타 DM 같은 앱 안 브라우저에서 시험한 뒤, 저장소를 손으로 지우기 어려울 때
 * 다시 「처음 온 사람」으로 열려고 쓴다. 정본: docs/project/web-app.md §10 의 화면 확인용 쿼리.
 *
 * - 시험용 웹(isDevSite)·로컬(localhost — 개발 서버와 `vite preview`)에서만 지운다. 운영 주소에서는 지우지 않고 주소에서 떼기만 한다.
 * - 지우는 것: localStorage·sessionStorage 의 이 앱 키 — 접두 `scenetrip.`(설치 id `scenetrip.installId`·첫 화면 기록 포함),
 *   `proto.`, PostHog 의 `ph_`(익명 id 도 새로). 쿠키는 쓰지 않으니 손대지 않는다. 서버의 비회원 데이터는 그대로 둔다 —
 *   설치 id 가 새로 생기므로 화면에서는 비어 보인다.
 * - 실제로 지우는 곳은 index.html 의 첫 인라인 스크립트다 — 첫 화면을 정하는 스크립트·앱 JS 보다 먼저 돌아야 하기 때문.
 *   주소는 `history.replaceState` 로 고치고 같은 페이지가 그대로 뜬다(다시 불러오면 이전 페이지의 앱 JS 가 지운 뒤에 다시 적을 틈이 생긴다).
 *   여기 함수들은 그 규칙의 정본이고, resetLink.test.ts 가 같은 사례표로 인라인 스크립트와 이 함수들을 함께 본다.
 */
import { isDevSite } from '@/features/proto/protoGate'

/** 지울 키의 접두. index.html 의 인라인 스크립트도 같은 목록을 쓴다. */
export const RESET_KEY_PREFIXES: readonly string[] = ['scenetrip.', 'proto.', 'ph_']

const LOCAL_HOSTS: readonly string[] = ['localhost', '127.0.0.1', '[::1]', '::1']

function asksReset(search: string): boolean {
  return new URLSearchParams(search).get('reset') === '1'
}

/** 저장소를 지울까 — 주소에 `reset=1` 이 있고, 개발 서버(`dev`)·시험용 웹·로컬 주소일 때만. */
export function shouldReset(hostname: string, search: string, dev: boolean): boolean {
  if (!asksReset(search)) return false
  const host = hostname.toLowerCase()
  return dev || isDevSite(host) || LOCAL_HOSTS.includes(host)
}

/** 주어진 키들 가운데 지울 것. */
export function keysToClear(keys: readonly string[]): string[] {
  return keys.filter((k) => RESET_KEY_PREFIXES.some((p) => k.startsWith(p)))
}

/** `reset=1` 이 있으면 그것만 뗀 쿼리(`?` 포함, 남는 게 없으면 빈 문자열), 없으면 null — 주소를 고치지 않는다. */
export function searchWithoutReset(search: string): string | null {
  if (!asksReset(search)) return null
  const q = new URLSearchParams(search)
  q.delete('reset')
  const rest = q.toString()
  return rest ? `?${rest}` : ''
}
