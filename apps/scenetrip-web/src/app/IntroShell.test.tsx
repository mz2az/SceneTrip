/*
 * 앱의 맨 바깥 — 첫 화면 덮개와 그 밑의 앱. 정본: docs/project/plans/web-ad-landing.md §10-3-2 · §11.
 *
 *   IntroShell({ initialIntro }): 덮개가 떠 있는 동안 밑의 앱(AppRoot)은 붙이지 않는다. ③(onRest) 뒤 붙여 둔다.
 *   덮개가 걷히면(onClose) 그 자리의 앱 — 주소 그대로. kept → /add, myMap → /my-map, notNew + /go → 작품 상세.
 *   failed 밖의 걷힘·③ 은 scenetrip.intro.done 을 적는다. 덮개가 뜨면 screen_view(intro).
 *   앱이 떠 있는 동안의 /go/{id} → 작품 상세.
 *
 * 덮개(LandingCover)와 앱(AppRoot)은 가짜로 바꾼다 — 덮개 안은 LandingCover.test.tsx 가 본다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IntroClose } from '@/features/landing/introClose'

vi.mock('./AppRoot', () => ({ AppRoot: () => <div data-testid="app" /> }))
vi.mock('@/features/landing/LandingCover', () => ({
  LandingCover: ({ contentId, onRest, onClose }: { contentId: number; onRest?: () => void; onClose: (how: IntroClose) => void }) => (
    <div data-testid="cover">
      <span>{`작품 ${contentId}`}</span>
      <button type="button" onClick={() => onRest?.()}>rest</button>
      {(['skip', 'browse', 'kept', 'myMap', 'notNew', 'failed'] as const).map((how) => (
        <button key={how} type="button" onClick={() => onClose(how)}>{how}</button>
      ))}
    </div>
  ),
}))
vi.mock('@/analytics', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/analytics')>()), track: vi.fn() }))

import { track } from '@/analytics'
import { deferredHeld, holdDeferred, resetDeferredForTest } from './deferred'
import { introDone, introOpenStore } from './intro'
import { IntroShell } from './IntroShell'

let path = ''
let go: ReturnType<typeof useNavigate> | null = null
function Probe() {
  const loc = useLocation()
  path = loc.pathname + loc.search
  go = useNavigate()
  return null
}

function renderShell(entry: string, intro: { contentId: number; fromGo: boolean } | null) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <IntroShell initialIntro={intro} />
      <Probe />
    </MemoryRouter>,
  )
}

const flush = () => act(async () => {
  await vi.dynamicImportSettled()
  await new Promise((r) => setTimeout(r, 0))
})
const click = async (name: string) => {
  fireEvent.click(screen.getByRole('button', { name }))
  await flush()
}

beforeEach(() => {
  localStorage.clear()
  resetDeferredForTest()
  introOpenStore.set(false)
  vi.mocked(track).mockClear()
})
afterEach(() => {
  cleanup()
  resetDeferredForTest()
})

describe('처음 온 사람', () => {
  it('`/` 로 들어오면 덮개, 밑의 앱은 붙이지 않는다, screen_view(intro)', async () => {
    holdDeferred()
    renderShell('/?utm_source=meta', { contentId: 32, fromGo: false })
    await flush()
    expect(screen.getByTestId('cover')).toBeTruthy()
    expect(screen.getByText('작품 32')).toBeTruthy()
    expect(screen.queryByTestId('app')).toBeNull()
    expect(introOpenStore.get()).toBe(true)
    expect(vi.mocked(track).mock.calls.map(([e]) => e)).toContainEqual({ name: 'screen_view', params: { screen_name: 'intro' } })
  })

  it('`?show=` 의 작품으로 덮개를 띄운다', async () => {
    renderShell('/?show=7', { contentId: 7, fromGo: false })
    await flush()
    expect(screen.getByText('작품 7')).toBeTruthy()
  })

  it('③ 뒤에 밑의 앱을 붙여 둔다(덮개는 그대로), 본 기록을 적고 미뤄 둔 일을 푼다', async () => {
    vi.useFakeTimers()
    try {
      holdDeferred()
      renderShell('/', { contentId: 32, fromGo: false })
      await act(async () => {
        await vi.dynamicImportSettled()
      })
      fireEvent.click(screen.getByRole('button', { name: 'rest' }))
      expect(deferredHeld()).toBe(false)
      expect(introDone()).toBe(true)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(700)
        await vi.dynamicImportSettled()
      })
      expect(screen.getByTestId('cover')).toBeTruthy()
      expect(screen.getByTestId('app')).toBeTruthy()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('덮개가 걷히면 그 자리의 앱', () => {
  it.each<[IntroClose, string]>([
    ['skip', '/search/place/5'],
    ['browse', '/search/place/5'],
    ['notNew', '/search/place/5'],
  ])('%s → 주소 그대로(%s), 앱이 뜬다, 본 기록', async (how, want) => {
    renderShell('/search/place/5', { contentId: 32, fromGo: false })
    await flush()
    await click(how)
    expect(screen.queryByTestId('cover')).toBeNull()
    expect(screen.getByTestId('app')).toBeTruthy()
    expect(path).toBe(want)
    expect(introDone()).toBe(true)
    expect(introOpenStore.get()).toBe(false)
  })

  it('kept → 「더 담기」 /add', async () => {
    renderShell('/', { contentId: 32, fromGo: false })
    await flush()
    await click('kept')
    expect(path).toBe('/add')
  })

  it('myMap → /my-map', async () => {
    renderShell('/', { contentId: 32, fromGo: false })
    await flush()
    await click('myMap')
    expect(path).toBe('/my-map')
  })

  it('failed → 주소 그대로 앱, 본 기록은 남기지 않는다(다음에 다시)', async () => {
    renderShell('/', { contentId: 32, fromGo: false })
    await flush()
    await click('failed')
    expect(screen.getByTestId('app')).toBeTruthy()
    expect(introDone()).toBe(false)
  })

  it('/go/{id} 로 와서 처음 온 사람이 아니면(notNew) 그 작품 상세로', async () => {
    renderShell('/?show=7', { contentId: 7, fromGo: true })
    await flush()
    await click('notNew')
    expect(path).toBe('/search/content/7')
  })
})

describe('덮개 없이 뜬 앱', () => {
  it('앱을 곧바로 붙인다', async () => {
    renderShell('/', null)
    await flush()
    expect(screen.getByTestId('app')).toBeTruthy()
    expect(screen.queryByTestId('cover')).toBeNull()
  })

  it('앱 안에서 /go/{id} 로 오면 덮개 없이 작품 상세로', async () => {
    renderShell('/', null)
    await flush()
    act(() => go!('/go/12'))
    await flush()
    expect(path).toBe('/search/content/12')
    expect(screen.queryByTestId('cover')).toBeNull()
  })
})
