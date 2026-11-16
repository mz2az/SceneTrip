/*
 * 첫 화면을 HTML 에(web-ad-landing.md §10-3-1 · §11) · 비싼 효과 빼기(§10-3-10).
 *   - index.html 의 #root 안에 보라 바탕 + 해태 + SceneTrip 이 있고, 처음 온 사람일 수 있을 때만(scenetrip.intro.done·로그인 기록이 없을 때) 보인다.
 *   - 해태 그림은 10KB 이하의 WebP, 빌드가 data URI 로 끼운다. React 의 첫 화면(IntroSplash)도 같은 그림·같은 클래스.
 *   - 덮개 CSS 에 filter: blur · backdrop-filter · border-radius 전환이 없다.
 */
import { readFileSync, statSync } from 'node:fs'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { INTRO_DONE_KEY } from '@/app/intro'
import { IntroSplash } from './IntroSplash'

const html = readFileSync('index.html', 'utf8')

describe('index.html 의 첫 화면', () => {
  it('#root 안에 해태 + SceneTrip, 그림 자리는 빌드가 채운다', () => {
    const root = /<div id="root">([\s\S]*?)<\/div><\/div>/.exec(html)?.[1] ?? ''
    expect(root).toContain('class="st-splash')
    expect(root).toContain('%SCENETRIP_INTRO_HAETAE%')
    expect(root).toContain('SceneTrip')
  })

  it('인라인 스크립트가 본 기록·로그인 기록이 없을 때만 표시를 건다', () => {
    expect(html).toContain(`localStorage.getItem('${INTRO_DONE_KEY}')`)
    expect(html).toContain("localStorage.getItem('scenetrip.auth.session')")
    expect(html).toContain("classList.add('st-intro')")
    expect(html).toMatch(/\.st-splash\s*\{\s*display:\s*none;\s*\}/)
    expect(html).toMatch(/html\.st-intro \.st-splash\s*\{/)
  })

  it('해태 그림은 10KB 이하 WebP', () => {
    const file = 'src/features/landing/intro-haetae.webp'
    expect(statSync(file).size).toBeLessThanOrEqual(10 * 1024)
    expect(readFileSync(file).subarray(8, 12).toString('ascii')).toBe('WEBP')
  })

  it('React 의 첫 화면도 같은 클래스와 data URI 그림', () => {
    const { container } = render(<IntroSplash />)
    const el = container.querySelector('.st-splash.st-landing--loading')
    expect(el).not.toBeNull()
    expect(el!.querySelector('img')!.getAttribute('src')).toMatch(/^data:image\/webp;base64,/)
    expect(el!.textContent).toBe('SceneTrip')
  })
})

describe('덮개 CSS — 비싼 효과 없음', () => {
  const css = readFileSync('src/features/landing/landing.css', 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  it('filter: blur · backdrop-filter 가 없다', () => {
    expect(css).not.toMatch(/filter\s*:\s*blur/)
    expect(css).not.toMatch(/backdrop-filter/)
  })
  it('border-radius 를 전환하지 않는다', () => {
    expect(css).not.toMatch(/transition[^;]*border-radius/)
    const tsx = readFileSync('src/features/landing/LandingCover.tsx', 'utf8')
    expect(tsx).not.toMatch(/border-radius \d+ms/)
    expect(tsx).not.toMatch(/filter: ['"]?blur/)
  })
})
