import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import { UpdatePrompt } from './UpdatePrompt'

const OLD = '/assets/index-OLD11111.js'
const NEW = '/assets/index-NEW22222.js'
const TITLE = '새 버전이 나왔어요'
const TEN_MINUTES = 10 * 60 * 1000

function pageWith(entry: string): string {
  return `<!doctype html><html><head><script type="module" crossorigin src="${entry}"></script></head><body><div id="root"></div></body></html>`
}

/** 서버가 돌려줄 index.html 의 번들. 시험 중간에 바꿔 「새 배포」를 흉내 낸다. */
let served = OLD
const fetchMock = vi.fn(async () => ({ ok: true, status: 200, text: async () => pageWith(served) }) as unknown as Response)

function setEntryScript(src: string | null) {
  document.querySelectorAll('script[data-test-entry]').forEach((s) => s.remove())
  if (!src) return
  const s = document.createElement('script')
  s.setAttribute('src', src)
  s.setAttribute('data-test-entry', '')
  document.head.appendChild(s)
}

/** 앱(App.tsx)처럼 경고창이 그려질 자리(OverlayRootContext)를 준 채로 띄운다. */
let overlayRoot: HTMLDivElement
function renderPrompt() {
  return render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <UpdatePrompt />
    </OverlayRootContext.Provider>,
  )
}

let visibility: DocumentVisibilityState = 'visible'

async function becomeVisible() {
  visibility = 'hidden'
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
  visibility = 'visible'
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
  // fetch 응답·text() 같은 비동기 단계를 흘려 보낸다.
  await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve() })
}

beforeEach(() => {
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  served = OLD
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => visibility === 'hidden' })
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  setEntryScript(null)
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
  // jsdom 기본값(프로토타입의 getter)으로 되돌린다.
  delete (document as unknown as Record<string, unknown>).visibilityState
  delete (document as unknown as Record<string, unknown>).hidden
})

describe('UpdatePrompt — 새 버전 알림', () => {
  it('화면이 다시 보일 때 다른 번들이 배포돼 있으면 알림과 두 단추를 띄운다', async () => {
    setEntryScript(OLD)
    renderPrompt()
    expect(screen.queryByText(TITLE)).toBeNull()

    served = NEW
    await becomeVisible()

    expect(await screen.findByText(TITLE)).toBeTruthy()
    expect(screen.getByRole('button', { name: '나중에' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeTruthy()
    expect(fetchMock).toHaveBeenCalled()
  })

  it('서버 번들이 지금과 같으면 알림을 띄우지 않는다', async () => {
    setEntryScript(OLD)
    renderPrompt()

    await becomeVisible()

    expect(fetchMock).toHaveBeenCalled()
    expect(screen.queryByText(TITLE)).toBeNull()
  })

  it('「새로고침」은 window.location.reload() 를 부른다', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    setEntryScript(OLD)
    renderPrompt()

    served = NEW
    await becomeVisible()
    fireEvent.click(await screen.findByRole('button', { name: '새로고침' }))

    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('「나중에」는 알림을 닫고, 같은 버전은 다시 권하지 않는다', async () => {
    setEntryScript(OLD)
    renderPrompt()

    served = NEW
    await becomeVisible()
    fireEvent.click(await screen.findByRole('button', { name: '나중에' }))
    // 닫히는 애니메이션(짧은 시간)이 끝나면 사라진다.
    await waitFor(() => expect(screen.queryByText(TITLE)).toBeNull())

    const callsBefore = fetchMock.mock.calls.length
    await becomeVisible()
    await new Promise((r) => setTimeout(r, 300))

    // 다시 확인은 하되(같은 NEW 를 받아도) 알림은 띄우지 않는다.
    expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore)
    expect(screen.queryByText(TITLE)).toBeNull()
  })

  it('10분마다 확인한다 — 10분이 지나야 새 버전 알림이 뜬다', async () => {
    vi.useFakeTimers()
    setEntryScript(OLD)
    renderPrompt()
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    served = NEW
    await act(async () => { await vi.advanceTimersByTimeAsync(TEN_MINUTES - 60_000) })
    expect(screen.queryByText(TITLE)).toBeNull()

    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(screen.queryByText(TITLE)).not.toBeNull()
  })

  it('문서에 번들 script 가 없으면(개발 서버) 한 번도 fetch 하지 않는다', async () => {
    vi.useFakeTimers()
    setEntryScript(null)
    renderPrompt()

    served = NEW
    await becomeVisible()
    await act(async () => { await vi.advanceTimersByTimeAsync(3 * TEN_MINUTES) })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.queryByText(TITLE)).toBeNull()
  })
})
