// 신고·차단 화면 흐름 — 가짜 창구로 「⋯」 → 신고 → 이유 고르기 → 보내기 → 접수 알림 → 시트 닫힘
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import type { ModerationClient } from './client'
import { createModerationController } from './controller'
import { createModerationEvents } from './events'
import { ModerationMenu, ModerationSheets } from './ModerationViews'
import { type ModerationTarget, type PostMenuAction, type ReportReason, postTarget } from './rules'

class Fake implements ModerationClient {
  reports: Array<{ target: ModerationTarget; reason: ReportReason; detail: string | null }> = []
  blocked: ModerationTarget[] = []
  removed: ModerationTarget[] = []
  async report(target: ModerationTarget, reason: ReportReason, detail: string | null) {
    this.reports.push({ target, reason, detail })
  }
  async block(target: ModerationTarget) {
    this.blocked.push(target)
    return { id: 1, nickname: 'x', blockedAt: '2026-10-01T00:00:00Z' }
  }
  async remove(target: ModerationTarget) {
    this.removed.push(target)
  }
  async blocks() {
    return []
  }
  async unblock() {}
}

function Harness({ client, canBlock, onBlocked, signedIn, actions }: { client: Fake; canBlock: boolean; onBlocked: (t: ModerationTarget) => void; signedIn: boolean; actions?: readonly PostMenuAction[] }) {
  const [root, setRoot] = useState<HTMLElement | null>(null)
  const [controller] = useState(() => createModerationController({ client, signedIn: () => signedIn, promptSignIn: () => {}, events: createModerationEvents() }))
  return (
    <div ref={setRoot} style={{ position: 'relative' }}>
      <OverlayRootContext.Provider value={root}>
        {root && (
          <>
            <ModerationMenu target={postTarget(7)} canBlock={canBlock} controller={controller} actions={actions} />
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

  // MZ2AZ-569 — 운영자의 「내리기」(docs/project/plans/moderation-operator.md §4)
  it('운영자 메뉴에는 「내리기(운영자)」 가 있고 누르면 확인 창이 뜬다. 확인하면 내리고 「내렸어요」 를 알린다', async () => {
    const client = new Fake()
    render(<Harness client={client} canBlock onBlocked={() => {}} signedIn actions={['report', 'block', 'remove']} />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    fireEvent.click(await screen.findByRole('menuitem', { name: '내리기(운영자)' }))
    expect(await screen.findByText('이 글을 내릴까요? 다른 사람에게 보이지 않게 돼요.')).toBeTruthy()
    expect(client.removed).toEqual([])
    // 확인 단추의 글은 계획에 없다 — 경고창 안의 「취소」 가 아닌 단추를 누른다.
    const dialog = screen.getAllByRole('alertdialog').find((d) => d.textContent?.includes('이 글을 내릴까요?'))!
    const confirm = within(dialog).getAllByRole('button').find((b) => b.textContent !== '취소')!
    await act(async () => {
      fireEvent.click(confirm)
    })
    await waitFor(() => expect(client.removed).toEqual([postTarget(7)]))
    expect(await screen.findByText('내렸어요')).toBeTruthy()
  })

  it('내리기 확인 창에서 취소하면 내리지 않는다', async () => {
    const client = new Fake()
    render(<Harness client={client} canBlock onBlocked={() => {}} signedIn actions={['report', 'block', 'remove']} />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    fireEvent.click(await screen.findByRole('menuitem', { name: '내리기(운영자)' }))
    await screen.findByText('이 글을 내릴까요? 다른 사람에게 보이지 않게 돼요.')
    await act(async () => {
      fireEvent.click(screen.getByText('취소'))
    })
    await waitFor(() => expect(screen.queryByText('이 글을 내릴까요? 다른 사람에게 보이지 않게 돼요.')).toBeNull(), { timeout: 1500 })
    expect(client.removed).toEqual([])
  })

  it('운영자가 아닌 메뉴(remove 없음)에는 「내리기(운영자)」 가 없다', async () => {
    render(<Harness client={new Fake()} canBlock onBlocked={() => {}} signedIn />)
    fireEvent.click(await screen.findByLabelText('신고·차단 메뉴'))
    await screen.findByRole('menuitem', { name: '신고' })
    expect(screen.queryByRole('menuitem', { name: '내리기(운영자)' })).toBeNull()
  })
})
