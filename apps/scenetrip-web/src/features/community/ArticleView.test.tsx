// 본문 블록 그리기 — 문단 사이 사진, 사진 설명·출처·AI 변환 표시 (iOS CommunityArticleView)
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { ArticlePhoto, ArticleView, type PhotoSource } from './ArticleView'
import type { PostBlock } from './model'
import { PhotoRenewContext } from './renewal'

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

const remote = (n: number): PhotoSource => ({ kind: 'remote', url: `https://s.test/p${n}.png?sig=${n}` })

describe('ArticleView', () => {
  it('문단과 사진을 블록 순서대로 그리고 사진 곁에 설명·AI 표시·출처를 둔다', () => {
    const blocks: PostBlock[] = [
      { kind: 'text', text: '첫 문단' },
      { kind: 'photo', photoIndex: 0, caption: '바다', credit: '공공누리 제1유형', aiGenerated: true },
      { kind: 'text', text: '끝 문단' },
    ]
    const { container } = render(<ArticleView blocks={blocks} photos={[remote(1)]} />)
    const text = container.textContent ?? ''
    expect(text.indexOf('첫 문단')).toBeLessThan(text.indexOf('바다'))
    expect(text.indexOf('바다')).toBeLessThan(text.indexOf('끝 문단'))
    expect(screen.getByText('AI 변환 이미지')).toBeTruthy()
    expect(screen.getByText('공공누리 제1유형')).toBeTruthy()
    expect(container.querySelectorAll('img')).toHaveLength(1)
  })

  it('가리키는 사진이 없는 블록은 그리지 않는다', () => {
    const { container } = render(<ArticleView blocks={[{ kind: 'photo', photoIndex: 3 }]} photos={[remote(1)]} />)
    expect(container.querySelectorAll('img')).toHaveLength(0)
  })
})

describe('ArticlePhoto', () => {
  it('사진이 안 열리면 한 번 새 주소를 청하고, 눌러서 다시 받기는 곧바로 청한다', () => {
    const asked: boolean[] = []
    const { container } = render(
      <PhotoRenewContext.Provider value={(forced) => asked.push(forced)}>
        <ArticlePhoto source={remote(1)} block={{ kind: 'photo', photoIndex: 0 }} />
      </PhotoRenewContext.Provider>,
    )
    fireEvent.error(container.querySelector('img')!)
    expect(asked).toEqual([false])
    expect(screen.getByText('사진을 불러오지 못했어요')).toBeTruthy()
    fireEvent.click(screen.getByText('사진 다시 받기'))
    expect(asked).toEqual([false, true])
  })

  it('못 읽은 사진은 다시 받기가 아니라 다시 고르라고 한다', () => {
    render(<ArticlePhoto source={{ kind: 'failed' }} block={{ kind: 'photo', photoIndex: 0 }} />)
    expect(screen.getByText('사진을 다시 골라 주세요')).toBeTruthy()
    expect(screen.queryByText('사진 다시 받기')).toBeNull()
  })

  it('다 읽히면 크게 보기 단추가 전체 화면 보기를 연다', () => {
    const { container } = render(<ArticlePhoto source={remote(2)} block={{ kind: 'photo', photoIndex: 0, caption: '골목' }} />)
    fireEvent.load(container.querySelector('img')!)
    expect(screen.getByLabelText('사진 크게 보기')).toBeTruthy()
  })
})
