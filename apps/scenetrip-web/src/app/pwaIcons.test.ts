// @vitest-environment node
// 홈 화면에 추가했을 때 쓰는 아이콘 — manifest·index.html 이 가리키는 파일이 있고 크기가 맞는지 (public/icons)
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/** PNG 머리글(IHDR)에서 가로·세로를 읽는다. */
function pngSize(path: string): { w: number; h: number } {
  const b = readFileSync(path)
  expect(b.subarray(1, 4).toString('ascii')).toBe('PNG')
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }
}

describe('PWA 아이콘', () => {
  const manifest = JSON.parse(readFileSync('public/manifest.webmanifest', 'utf8')) as { icons: Array<{ src: string; sizes: string; purpose: string }> }

  it('manifest 의 아이콘은 정사각형이고 선언한 크기와 같다 (192·512)', () => {
    const sizes = manifest.icons.map((i) => i.sizes).sort()
    expect(sizes).toEqual(['192x192', '512x512'])
    for (const icon of manifest.icons) {
      const [w, h] = icon.sizes.split('x').map(Number)
      expect(pngSize(`public${icon.src}`)).toEqual({ w, h })
      expect(icon.purpose).toContain('maskable')
    }
  })

  it('index.html 의 아이콘 링크가 있는 파일을 가리킨다 (아이폰은 180)', () => {
    const html = readFileSync('index.html', 'utf8')
    expect(html).toContain('href="/icons/apple-touch-icon.png"')
    expect(pngSize('public/icons/apple-touch-icon.png')).toEqual({ w: 180, h: 180 })
    expect(html).toContain('href="/icons/icon-192.png"')
  })
})
