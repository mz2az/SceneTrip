// 웹앱 훑어보기(e2e 스모크): 핵심 화면을 헤드리스 Chrome 으로 차례로 열어 보며
//   콘솔 오류 · 잡히지 않은 예외 · 실패한 요청 · 4xx/5xx 응답 · 깨진 이미지 · (있어서는 안 되는) 쓰기·유료 호출을 센다.
// 하나라도 있으면 끝에 표로 적고 종료 코드 1 로 끝난다.
//
//   node scripts/e2e-smoke.mjs [--base http://localhost:5173] [--only 이름,이름] [--shots 폴더]
//
// 개발 서버가 떠 있어야 한다(`npm run dev`). 서버 쓰기는 `.env.local` 의 안전장치와 `?dev-account=1`·`?dev-stamps=1` 의 가짜 응답(src/devMock.ts)이
// 막아 주고, 이 스크립트도 `/v1` 로 나가는 GET 이 아닌 요청과 `/guide`·`/navigation` 호출을 **실패로 센다**(안전장치가 풀렸는지 알아채려고).
// DEV 는 IP 당 분당 약 60 회라 429 가 날 수 있다 — 429 는 실패가 아니라 경고로 센다(개발 서버가 GET 200 을 10분 보관하므로 두 번째부터는 덜 난다).
// 헤드리스 Chrome 한 개를 띄웠다 끝나면 닫는다. 사용자가 CPU 에 민감하므로 한 번에 하나만 돌린다.
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'

const argv = process.argv.slice(2)
const opt = { base: 'http://localhost:5173', only: null, shots: null }
while (argv.length) {
  const k = argv.shift().replace(/^--/, '')
  opt[k] = argv.shift()
}
if (opt.only) opt.only = new Set(String(opt.only).split(','))
if (opt.shots) mkdirSync(opt.shots, { recursive: true })

const FLAGS = 'dev-lang=ko&dev-seen=1&dev-nosplash=1'
const url = (path) => `${opt.base}${path}${path.includes('?') ? '&' : '?'}${FLAGS}`
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--hide-scrollbars', '--no-first-run', '--disable-extensions'] })

/** 글자가 같은 요소(자식이 적은 것 중 마지막)를 누른다. */
async function clickText(page, text) {
  const ok = await page.evaluate((t) => {
    const els = [...document.querySelectorAll('button, a, [role=button], [role=tab], div, span')].filter((e) => e.children.length < 4 && (e.textContent || '').trim() === t)
    const el = els[els.length - 1]
    if (!el) return false
    el.click()
    return true
  }, text)
  if (!ok) throw new Error(`「${text}」 를 못 찾았다`)
  await sleep(500)
}
async function clickSel(page, sel) {
  const ok = await page.evaluate((s) => {
    const el = document.querySelector(s)
    if (!el) return false
    el.click()
    return true
  }, sel)
  if (!ok) throw new Error(`${sel} 를 못 찾았다`)
  await sleep(500)
}
async function has(page, text) {
  return page.evaluate((t) => (document.body.innerText || '').includes(t), text)
}
async function expectText(page, text) {
  for (let i = 0; i < 20; i += 1) {
    if (await has(page, text)) return
    await sleep(250)
  }
  throw new Error(`화면에 「${text}」 가 없다`)
}
async function typeInto(page, selector, value) {
  await page.evaluate(
    (s, v) => {
      const el = document.querySelector(s)
      if (!el) throw new Error(`${s} 없음`)
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    },
    selector,
    value,
  )
  await sleep(600)
}

// ---------------------------------------------------------------- 훑을 흐름
// 각 흐름은 새 페이지(저장소 비운 상태)에서 시작한다. `ids` 는 DEV 에서 읽은 실제 작품·촬영지·글 번호.
const flows = (ids) => [
  {
    name: '홈 → 탭 → 마이페이지 덮개',
    run: async (page) => {
      await page.goto(url('/'), { waitUntil: 'networkidle2' })
      await expectText(page, '지금 뜨는 작품')
      await clickText(page, '작품검색')
      await expectText(page, '인기 작품')
      await clickText(page, '커뮤니티')
      await expectText(page, '커뮤니티')
      await clickText(page, '홈')
      await clickSel(page, '[aria-label="마이페이지"]')
      await expectText(page, '내 여행')
      await clickSel(page, '[aria-label="닫기"]')
    },
  },
  {
    name: '검색 입력 → 자동완성 → 지우기',
    run: async (page) => {
      await page.goto(url('/search'), { waitUntil: 'networkidle2' })
      await typeInto(page, 'input[aria-label="작품·배우·장소로 검색"]', '사랑')
      await sleep(900)
      await typeInto(page, 'input[aria-label="작품·배우·장소로 검색"]', '')
    },
  },
  {
    name: '작품 상세',
    run: async (page) => {
      await page.goto(url(`/search/content/${ids.content}`), { waitUntil: 'networkidle2' })
      await expectText(page, ids.contentTitle)
    },
  },
  {
    name: '촬영지 상세 → 리뷰 시트',
    run: async (page) => {
      await page.goto(url(`/search/place/${ids.place}`), { waitUntil: 'networkidle2' })
      await expectText(page, ids.placeName)
      await clickText(page, '리뷰')
        .catch(() => {})
    },
  },
  {
    name: '코스 목록 → 마법사 → 닫기',
    run: async (page) => {
      await page.goto(url('/courses'), { waitUntil: 'networkidle2' })
      await sleep(800)
      await page.goto(url('/courses/new'), { waitUntil: 'networkidle2' })
      await sleep(800)
    },
  },
  {
    name: '코스 편집기 열기 · 목록 줄 누르기 · 닫기(가짜 코스)',
    run: async (page) => {
      await page.goto(url('/courses/9001?dev-stamps=1'), { waitUntil: 'networkidle2' })
      await expectText(page, '개발용 코스')
      await clickText(page, '이화 벽화마을')
      await sleep(1500)
      await clickText(page, '취소')
    },
  },
  {
    name: '코스 편집기 — 여행 중(가짜) · 가이드 서랍(가짜 답)',
    run: async (page) => {
      await page.goto(url('/courses/9001?dev-stamps=active&dev-account=1&dev-fake-guide=answer'), { waitUntil: 'networkidle2' })
      await expectText(page, '길찾기')
      await page.evaluate(() => {
        localStorage.setItem('scenetrip.aiConsentVersion', '1')
        localStorage.setItem('scenetrip.locationConsentVersion', '1')
      })
      await page.evaluate(() => document.querySelector('[aria-label="내가 도와줄게!"]')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })))
      await sleep(600)
      await typeInto(page, 'textarea', '주변 카페 알려줘')
      await clickSel(page, '[data-testid="guide-send"]')
      await expectText(page, '근처에서 찾아봤어요')
    },
  },
  {
    name: '커뮤니티 목록 → 글 상세 → 사진 크게 → ⋯ 메뉴 → 신고 시트',
    run: async (page) => {
      await page.goto(url('/community'), { waitUntil: 'networkidle2' })
      await sleep(800)
      await page.goto(url(`/community/post/${ids.post}?dev-account=1`), { waitUntil: 'networkidle2' })
      await sleep(1200)
      await clickSel(page, '[aria-label="사진 크게 보기"]')
      await expectText(page, '100%')
      await page.goto(url(`/community/post/${ids.post}?dev-account=1`), { waitUntil: 'networkidle2' })
      await sleep(1000)
      await clickSel(page, '[data-testid="moderation-menu"]')
      await page.evaluate(() => document.querySelectorAll('[role=menuitem]')[0]?.click())
      await expectText(page, '신고 이유')
    },
  },
  {
    name: '글쓰기(가짜 계정)',
    run: async (page) => {
      await page.goto(url('/community/new?dev-account=1'), { waitUntil: 'networkidle2' })
      await expectText(page, '여행후기 쓰기')
    },
  },
  {
    name: '마이페이지 시트들(비회원): 내 코스·찜·장바구니·스탬프·내 글·릴스',
    run: async (page) => {
      for (const section of ['courses', 'likes', 'cart', 'stamps', 'posts', 'reels']) {
        await page.goto(url(`/profile/${section}${section === 'stamps' ? '?dev-stamps=1' : ''}`), { waitUntil: 'networkidle2' })
        await sleep(700)
      }
    },
  },
  {
    name: '마이페이지(가짜 계정): 내 리뷰 · 차단한 사용자 · 닉네임',
    run: async (page) => {
      await page.goto(url('/profile?dev-account=1'), { waitUntil: 'networkidle2' })
      await expectText(page, '제주러버')
      await clickText(page, '내 리뷰')
      await expectText(page, '시크릿 블루 카페')
      await page.goto(url('/profile?dev-account=1'), { waitUntil: 'networkidle2' })
      await clickText(page, '차단한 사용자')
      await expectText(page, '차단 풀기')
      await page.goto(url('/profile?dev-account=1'), { waitUntil: 'networkidle2' })
      await clickSel(page, '[aria-label="닉네임 바꾸기"]')
      await expectText(page, '닉네임 바꾸기')
    },
  },
  {
    name: '로그인 시트 · AI 동의 시트',
    run: async (page) => {
      await page.goto(url('/profile'), { waitUntil: 'networkidle2' })
      await clickText(page, '로그인')
      await expectText(page, 'SceneTrip 로그인')
      await page.goto(url('/profile?dev-account=1'), { waitUntil: 'networkidle2' })
      await clickSel(page, '[aria-label="AI 기능 사용"]')
      await expectText(page, 'AI 기능 사용 안내')
    },
  },
  {
    name: '영어 화면: 홈 · 마이페이지',
    run: async (page) => {
      await page.goto(`${opt.base}/?dev-lang=en&dev-seen=1&dev-nosplash=1`, { waitUntil: 'networkidle2' })
      await expectText(page, 'Trending')
      await page.goto(`${opt.base}/profile?dev-lang=en&dev-seen=1&dev-nosplash=1`, { waitUntil: 'networkidle2' })
      await sleep(800)
    },
  },
]

// ---------------------------------------------------------------- 실행
async function firstIds() {
  const get = async (path) => {
    const r = await fetch(`${opt.base}/v1${path}`, { headers: { 'Accept-Language': 'ko' } }) // 앱 언어(ko)와 같은 이름을 받는다
    if (!r.ok) throw new Error(`${path} → ${r.status}`)
    return r.json()
  }
  const [contents, places, posts] = await Promise.all([get('/contents?limit=1'), get('/places?limit=1'), get('/posts?limit=1')])
  return { content: contents.items[0].id, contentTitle: contents.items[0].title, place: places.items[0].id, placeName: places.items[0].name, post: posts.items[0].id }
}

const results = []
let ids
try {
  ids = await firstIds()
} catch (e) {
  console.error(`개발 서버(${opt.base})나 DEV API 를 못 읽었다: ${e.message}`)
  await browser.close()
  process.exit(2)
}

for (const flow of flows(ids)) {
  if (opt.only && !opt.only.has(flow.name)) continue
  const context = await browser.createBrowserContext() // 흐름마다 저장소를 비운 새 프로필
  const page = await context.newPage()
  await page.setViewport({ width: 402, height: 874, deviceScaleFactor: 1 })
  const problems = []
  const warns = []
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    const text = m.text()
    if (text.startsWith('Failed to load resource')) return // 응답 쪽에서 센다
    problems.push(`console.error: ${text.slice(0, 200)}`)
  })
  page.on('pageerror', (e) => problems.push(`pageerror: ${String(e.message || e).slice(0, 200)}`))
  page.on('requestfailed', (r) => {
    const why = r.failure()?.errorText ?? ''
    if (why === 'net::ERR_ABORTED') return
    problems.push(`requestfailed: ${r.method()} ${r.url().slice(0, 120)} (${why})`)
  })
  page.on('request', (r) => {
    const u = new URL(r.url())
    if (!u.pathname.startsWith('/v1/')) return
    if (r.method() !== 'GET' && r.method() !== 'HEAD') problems.push(`쓰기 요청이 나갔다: ${r.method()} ${u.pathname}`)
    if (/^\/v1\/(guide|navigation)/.test(u.pathname)) problems.push(`유료 호출이 나갔다: ${r.method()} ${u.pathname}`)
  })
  page.on('response', (r) => {
    const s = r.status()
    if (s < 400) return
    const line = `${s} ${r.request().method()} ${r.url().slice(0, 120)}`
    if (s === 429) warns.push(`429(DEV 호출 한도) ${r.url().slice(0, 100)}`)
    else problems.push(`응답 ${line}`)
  })

  const started = Date.now()
  try {
    await flow.run(page)
    await sleep(500)
    const broken = await page.evaluate(() =>
      [...document.images].filter((i) => i.complete && i.naturalWidth === 0 && i.getAttribute('src') && i.offsetParent !== null && getComputedStyle(i).visibility !== 'hidden').map((i) => i.getAttribute('src').slice(0, 100)),
    )
    for (const src of broken) problems.push(`깨진 이미지: ${src}`)
  } catch (e) {
    problems.push(`흐름 실패: ${e.message}`)
  }
  if (opt.shots) await page.screenshot({ path: `${opt.shots}/${flow.name.replace(/[^\p{L}\p{N}]+/gu, '_')}.png` }).catch(() => {})
  await context.close()
  results.push({ name: flow.name, problems: [...new Set(problems)], warns: [...new Set(warns)], ms: Date.now() - started })
}
await browser.close()

let failed = 0
for (const r of results) {
  const mark = r.problems.length ? 'FAIL' : 'ok  '
  console.log(`${mark} ${r.name}  (${(r.ms / 1000).toFixed(1)}s)${r.warns.length ? `  [경고 ${r.warns.length}: ${r.warns[0]}]` : ''}`)
  for (const p of r.problems) console.log(`       - ${p}`)
  if (r.problems.length) failed += 1
}
console.log(`\n${results.length - failed}/${results.length} 흐름 통과${failed ? `, ${failed} 실패` : ''}`)
process.exit(failed ? 1 : 0)
