import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { chooseLanguage, format, hasEnglish, tr } from './index'

describe('tr', () => {
  it('코드에 %d 로 적어도 iOS 열쇠(%lld)의 영어를 찾는다', () => {
    chooseLanguage('en')
    expect(tr('추가 %d', 3)).toBe('Add 3')
    chooseLanguage('ko')
    expect(tr('추가 %d', 3)).toBe('추가 3')
  })
  it('자리표시자를 채운다', () => {
    expect(format('%1$@ / %2$@', ['a', 'b'])).toBe('a / b')
    expect(format('%.1f 점', [3.25])).toBe('3.3 점')
  })
})

describe('영어 표', () => {
  it('코드의 tr("한국어") 열쇠가 영어 표에 다 있다 (웹에만 있는 문구는 예외 목록에)', () => {
    const exempt = new Set(['가', '지도', '뒤로', '일차 빼기', '일차 더하기', 'Google 로그인이 아직 준비되지 않았어요'])
    const files: string[] = []
    const walk = (dir: string) => {
      for (const f of readdirSync(dir)) {
        const p = join(dir, f)
        if (statSync(p).isDirectory()) walk(p)
        else if (/\.tsx?$/.test(f) && !/\.test\./.test(f)) files.push(p)
      }
    }
    walk(join(process.cwd(), 'src'))
    const missing: string[] = []
    for (const f of files) {
      const text = readFileSync(f, 'utf8')
      for (const m of text.matchAll(/\btr\(\s*(['"`])((?:\\.|(?!\1)[^\\])*)\1/g)) {
        const key = m[2].replace(/\\n/g, '\n').replace(/\\'/g, "'")
        if (!/[가-힣]/.test(key) || exempt.has(key)) continue
        if (!hasEnglish(key) && !hasEnglish(key.replace(/%d/g, '%lld'))) missing.push(key)
      }
    }
    expect(missing).toEqual([])
  })
})
