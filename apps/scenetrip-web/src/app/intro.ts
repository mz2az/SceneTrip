/*
 * 앱의 첫 화면(인트로) — 누구에게, 무슨 작품으로 띄우는가. 정본: docs/project/plans/web-ad-landing.md §11.
 *
 * 처음 온 사람(이 브라우저에서 첫 화면을 끝까지 보거나 건너뛴 적이 없고, 로그인하지 않았고, 담은 것·코스가 없다)에게
 * 어느 주소로 들어오든 앱 위 전체 화면 덮개로 뜬다. 담은 것·코스는 서버에 물어야 알므로 덮개(LandingCover)가 받아 보고
 * 하나라도 있으면 스스로 걷힌다. 여기는 앱이 뜰 때 동기로 정할 수 있는 것만 정한다(순수 함수 + 저장소 한 칸).
 *
 * `/go/{id}` 는 `/?show={id}` 와 같다. 이미 본 사람(또는 로그인한 사람)이 `/go/{id}` 로 오면 그 작품 상세로 간다.
 */
import { createStore, readStorage, writeStorage } from '@/state/storage'
import { landingIdOf, paths } from './routes'

/** 주소에 `?show=` 가 없을 때의 작품 — 케이팝 데몬 헌터스(지금 광고 작품). 바꿀 곳은 여기 한 곳. */
export const INTRO_CONTENT_ID = 32

/** 첫 화면을 끝까지 봤거나 건너뛰었다 — 브라우저 기준, 한 번. index.html 의 인라인 스크립트도 이 이름을 읽는다. */
export const INTRO_DONE_KEY = 'scenetrip.intro.done'

export function introDone(): boolean {
  return readStorage(INTRO_DONE_KEY) !== null
}

export function markIntroDone(): void {
  writeStorage(INTRO_DONE_KEY, '1')
}

/** 덮개가 떠 있는가 — 밑의 앱이 `screen_view` 를 보내지 않게 한다(덮개가 `intro` 를 보낸다). */
export const introOpenStore = createStore(false)

/**
 * 덮개가 걷힌 뒤 `fn` 을 한 번 — 떠 있지 않으면 곧바로. 덮개 밑에서 미리 받은 모듈이 서버를 부르는 일(시안의 /me/trip 맞추기)을
 * 덮개가 걷힐 때까지 미룬다(§11 — 덮개가 떠 있는 동안 밑의 앱은 API 0).
 */
export function afterIntro(fn: () => void): void {
  if (!introOpenStore.get()) {
    fn()
    return
  }
  const off = introOpenStore.subscribe(() => {
    if (introOpenStore.get()) return
    off()
    fn()
  })
}

export interface IntroStart {
  /** 덮개로 보일 작품. null 이면 덮개 없이 바로 앱. */
  intro: { contentId: number; fromGo: boolean } | null
  /** 앱이 그리기 전에 주소를 이것으로 바꾼다(history.replaceState). null 이면 그대로. */
  replaceTo: string | null
}

/** `?show=32` → 32. 없거나 양의 정수가 아니면 null. */
export function showIdOf(search: string): number | null {
  const raw = new URLSearchParams(search).get('show')
  if (raw === null || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return id > 0 && Number.isSafeInteger(id) ? id : null
}

/**
 * 앱이 뜰 때의 주소와 상태로 첫 화면을 정한다.
 *   - `/go/{id}`: 처음 온 사람이면 덮개(그 작품) + 주소는 `/?show={id}`(나머지 쿼리는 둔다), 아니면 `/search/content/{id}` 로.
 *   - 그 밖: 처음 온 사람이면 덮개(`?show=` 또는 기본 작품), 주소는 그대로. 아니면 덮개 없음.
 */
export function introStart(pathname: string, search: string, state: { done: boolean; signedIn: boolean }): IntroStart {
  const fresh = !state.done && !state.signedIn
  const goId = landingIdOf(pathname)
  if (goId !== null) {
    if (!fresh) return { intro: null, replaceTo: `${paths.searchContent(goId)}${search}` }
    const q = new URLSearchParams(search)
    q.set('show', String(goId))
    return { intro: { contentId: goId, fromGo: true }, replaceTo: `${paths.home}?${q.toString()}` }
  }
  if (!fresh) return { intro: null, replaceTo: null }
  return { intro: { contentId: showIdOf(search) ?? INTRO_CONTENT_ID, fromGo: false }, replaceTo: null }
}
