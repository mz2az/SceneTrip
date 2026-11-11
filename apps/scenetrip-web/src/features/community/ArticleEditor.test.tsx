import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { ArticleEditor } from './ArticleEditor'
import { createPhotoDraft } from './photoDraft'

/*
 * MZ2AZ-551 — 글쓰기의 본문 칸이 보이지 않았다(테두리·바탕이 없는 글상자라 어디를 눌러 써야 할지 몰랐다).
 * 본문은 「본문」 이라는 이름이 붙은 칸 묶음으로 드러나고, 그 안에 글상자가 있다. 모양(CSS)이 아니라 구조로 확인한다.
 */
beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

function renderEditor() {
  return render(
    <ArticleEditor
      items={[{ kind: 'text', id: 'text-a', text: '' }]}
      onItems={() => {}}
      meta={{}}
      onMeta={() => {}}
      draft={createPhotoDraft()}
      slots={[]}
      locked={false}
    />,
  )
}

describe('ArticleEditor — 본문 칸', () => {
  it('「본문」 이라는 이름의 칸 묶음이 있고 그 안에 글상자가 있다', () => {
    renderEditor()
    const field = screen.getByRole('group', { name: '본문' })
    expect(within(field).getAllByRole('textbox').length).toBeGreaterThan(0)
  })

  it('첫 글상자는 안내 문구를 이름으로 가진다(화면 읽기 도구가 무엇을 쓰는 칸인지 읽는다)', () => {
    renderEditor()
    const field = screen.getByRole('group', { name: '본문' })
    const box = within(field).getAllByRole('textbox')[0]!
    expect(box.getAttribute('aria-label') ?? '').not.toBe('')
  })
})
