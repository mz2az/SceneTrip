// @vitest-environment node
// 지도 컨테이너 CSS 가 네이버 SDK 의 인라인 `position: relative` 에 지지 않는지 — 졌을 때는 높이가 0 이 돼 지도가 통째로 안 보였다(운영 첫 배포).
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const HOSTS: Array<[string, string]> = [
  ['.st-map', 'src/features/search/map/map.css'],
  ['.rt-map', 'src/features/route/map/routeMap.css'],
  ['.tm-map', 'src/features/route/nav/tripMap.css'],
]

describe('지도 컨테이너 CSS', () => {
  it.each(HOSTS)('%s 는 position: absolute !important 로 inset 을 지킨다', (selector, file) => {
    const css = readFileSync(file, 'utf8')
    const start = css.indexOf(`${selector} {`)
    expect(start).toBeGreaterThanOrEqual(0)
    const block = css.slice(start, css.indexOf('}', start))
    expect(block).toMatch(/position:\s*absolute\s*!important/)
    expect(block).toMatch(/inset:\s*0/)
  })
})
