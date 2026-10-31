// 개발용 스크린샷: 설치된 Chrome 을 puppeteer-core(브라우저를 새로 받지 않는다)로 돌려 한 화면을 PNG 로 저장한다.
//
//   node scripts/shot.mjs <URL> <out.png> [옵션]
//     --w 402 --h 874 --scale 3      뷰포트(iOS 비교용 스크린샷이 1206x2622 = 402x874pt @3x 라 기본값이 그렇다)
//     --wait 1500                    로드 뒤 기다릴 ms (네트워크가 잠잠해진 다음 추가로)
//     --click "텍스트"                글자가 같은 요소를 눌러 본다 (여러 번 줄 수 있다, 눌린 뒤 300ms 쉰다)
//     --scroll "선택자:픽셀"           선택자 요소를 세로로 스크롤한다
//     --eval "js"                    페이지 안에서 실행할 코드 (여러 번 가능)
//     --until domcontentloaded       기다릴 로드 단계(기본 networkidle2) — 스플래시처럼 금방 사라지는 화면용
//     --full                          요소가 아니라 전체 페이지
//
// 사용 예:  node scripts/shot.mjs "http://localhost:5273/search?dev-lang=ko&dev-seen=1&dev-nosplash=1" /private/tmp/x.png --wait 2500
// 헤드리스 Chrome 한 개를 띄웠다 끝나면 닫는다. 사용자가 CPU 에 민감하므로 한 번에 하나씩만 돌린다.
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const args = process.argv.slice(2)
const url = args.shift()
const out = args.shift()
if (!url || !out) {
  console.error('usage: node scripts/shot.mjs <URL> <out.png> [--w 402 --h 874 --scale 3 --wait 1500 --click 텍스트 --scroll 선택자:px --eval js --full]')
  process.exit(2)
}
const opt = { w: 402, h: 874, scale: 3, wait: 1500, click: [], scroll: [], eval: [], full: false }
while (args.length) {
  const k = args.shift().replace(/^--/, '')
  if (k === 'full') opt.full = true
  else if (['click', 'scroll', 'eval'].includes(k)) opt[k].push(args.shift())
  else if (k === 'until') opt.until = args.shift()
  else opt[k] = Number(args.shift())
}

const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--hide-scrollbars', '--no-first-run', '--disable-extensions'] })
try {
  const page = await browser.newPage()
  await page.setViewport({ width: opt.w, height: opt.h, deviceScaleFactor: opt.scale, isMobile: false, hasTouch: false })
  await page.goto(url, { waitUntil: opt.until || 'networkidle2', timeout: 45000 }).catch(() => {})
  await new Promise((r) => setTimeout(r, opt.wait))
  for (const text of opt.click) {
    const ok = await page.evaluate((t) => {
      const els = [...document.querySelectorAll('button, a, [role=button], div, span')].filter((e) => e.children.length < 4 && (e.textContent || '').trim() === t)
      const el = els[els.length - 1]
      if (!el) return false
      el.click()
      return true
    }, text)
    if (!ok) console.error(`click: "${text}" 를 못 찾았다`)
    await new Promise((r) => setTimeout(r, 400))
  }
  for (const spec of opt.scroll) {
    const i = spec.lastIndexOf(':')
    await page.evaluate((sel, px) => {
      const el = document.querySelector(sel)
      if (el) el.scrollTop = px
    }, spec.slice(0, i), Number(spec.slice(i + 1)))
    await new Promise((r) => setTimeout(r, 300))
  }
  for (const code of opt.eval) {
    await page.evaluate(code)
    await new Promise((r) => setTimeout(r, 400))
  }
  mkdirSync(dirname(out), { recursive: true })
  await page.screenshot({ path: out, fullPage: opt.full })
  console.log(out)
} finally {
  await browser.close()
}
