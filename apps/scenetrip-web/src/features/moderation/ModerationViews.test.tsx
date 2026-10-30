// 신고·차단 화면 흐름 — 가짜 창구로 「⋯」 → 신고 → 이유 고르기 → 보내기 → 접수 알림 → 시트 닫힘
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import type { ModerationClient } from './client'
import { createModerationController } from './controller'
import { createModerationEvents } from './events'
import { ModerationMenu, ModerationSheets } from './ModerationViews'
import { type ModerationTarget, type ReportReason, postTarget } from './rules'

class Fake implements ModerationClient {
  reports: Array<{ target: ModerationTarget; reason: ReportReason; detail: string | null }> = []
  blocked: ModerationTarget[] = []
  async report(target: ModerationTarget, reason: ReportReason, detail: string | null) {
    this.reports.push({ target, reason, detail })
  }
  async block(target: ModerationTarget) {
    this.blocked.push(target)
    return { id: 1, nickname: 'x', blockedAt: '2026-10-01T00:00:00Z' }
  }
  async blocks() {
    return []
  }
  async unblock() {}
}

function Harness({ client, canBlock, onBlocked, signedIn }: { client: Fake; canBlock: boolean; onBlocked: (t: ModerationTarget) => void; signedIn: boolean }) {
  const [root, setRoot] = useState<HTMLElement | null>(null)
  const [controller] = useState(() => createModerationController({ client, signedIn: () => signedIn, promptSignIn: () => {}, events: createModerationEvents() }))
  return (
    <div ref={setRoot} style={{ position: 'relative' }}>
      <OverlayRootContext.Provider value={root}>
        {root && (
          <>
            <ModerationMenu target={postTarget(7)} canBlock={canBlock} controller={controller} />
            <ModerationSheets controller={controller} onBlocked={onBlocked} />
          </>
        )}
      </OverlayRootContext.Provider>
    </div>
  )
}

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('신고·차단 화면', () => {
  it('「⋯」 → 신고 → 이유 → 보내기 → 접수 알림을 확인하면 시트가 닫힌다', async () => {
    const client = new Fake()
    render(<Harness client={client} canBlock onBlocked={() => {}} signedIn={false} />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    fireEvent.click(await screen.findByRole('menuitem', { name: '신고' }))
    const send = await screen.findByText('보내기')
    // 이유를 고르기 전에는 보낼 수 없다
    fireEvent.click(send)
    expect(client.reports).toHaveLength(0)
    fireEvent.click(screen.getByText('스팸·광고'))
    fireEvent.change(screen.getByPlaceholderText('자세한 내용 (선택)'), { target: { value: '  광고예요  ' } })
    await act(async () => {
      fireEvent.click(screen.getByText('보내기'))
    })
    expect(client.reports).toEqual([{ target: postTarget(7), reason: 'spam', detail: '광고예요' }])
    expect(await screen.findByText('신고가 접수됐어요. 24시간 안에 확인할게요.')).toBeTruthy()
    fireEvent.click(screen.getByText('확인'))
    await waitFor(() => expect(screen.queryByText('신고 이유')).toBeNull(), { timeout: 1500 })
  })

  it('작성자가 있는 글은 「이 사용자 차단」 이 있고 확인하면 차단하고 글을 걷어 낸다', async () => {
    const client = new Fake()
    const gone: ModerationTarget[] = []
    render(<Harness client={client} canBlock onBlocked={(t) => gone.push(t)} signedIn />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    fireEvent.click(await screen.findByRole('menuitem', { name: '이 사용자 차단' }))
    expect(await screen.findByText('이 사용자를 차단할까요?')).toBeTruthy()
    await act(async () => {
      fireEvent.click(screen.getByText('차단'))
    })
    await waitFor(() => expect(gone).toEqual([postTarget(7)]))
    expect(client.blocked).toEqual([postTarget(7)])
  })

  it('작성자가 없는 글(탈퇴한 사용자)에는 차단 항목이 없다', async () => {
    render(<Harness client={new Fake()} canBlock={false} onBlocked={() => {}} signedIn />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    await screen.findByRole('menuitem', { name: '신고' })
    expect(screen.queryByRole('menuitem', { name: '이 사용자 차단' })).toBeNull()
  })
})
