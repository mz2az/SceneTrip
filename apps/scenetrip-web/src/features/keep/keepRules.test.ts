/*
 * 담기 중심 흐름 — 순수 규칙. 정본: docs/project/plans/web-keep-flow.md §2.
 *   §2-1 isInAppBrowser · keepEnv(위에서부터 먼저 맞는 것, 8단계)
 *   §2-2 parseKeepParam · keepUrl · handoffHref
 *   §2-3 moreWorks · mapGroups · mapBounds
 */
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import type { CartItem } from '@/state/cart'
import { handoffHref, isInAppBrowser, keepEnv, keepUrl, mapBounds, mapGroups, moreWorks, parseKeepParam, type KeepEnv } from './keepRules'

type ContentSummary = components['schemas']['ContentSummary']

// ── 실제 UA
const FB_ANDROID =
  'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.0.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/586.0.0.52.53;IABMV/1;]'
const IG_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.0 (iPhone15,2; iOS 18_1; en_US)'
const CHROME_ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'
const CHROME_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1'
const DESKTOP_CHROME = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
// 그 밖 표식 — 페이스북 아이폰(FBAN/FBIOS), 라인, 카카오톡, 안드로이드 웹뷰(; wv)
const FB_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/486.0.0.55.70;FBBV/665000000;FBDV/iPhone15,2;FBMD/iPhone;FBSN/iOS;FBSV/18.1;FBSS/3;FBCR/;FBID/phone;FBLC/en_US;FBOP/5]'
const LINE_ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 Line/14.19.1'
const KAKAO_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 10.8.5'
const ANDROID_WEBVIEW =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36'
const FIREFOX_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/132.0 Mobile/15E148 Safari/605.1.15'
const EDGE_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 EdgiOS/130.2849.80 Mobile/15E148 Safari/605.1.15'
const SAFARI_IPAD =
  'Mozilla/5.0 (iPad; CPU OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'
const FIREFOX_ANDROID = 'Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0'
const FB_DESKTOP = `${DESKTOP_CHROME} [FBAN/EMA;FBAV/1.0]`

const env = (userAgent: string, standalone = false, canPromptInstall = false): KeepEnv => keepEnv({ userAgent, standalone, canPromptInstall })

describe('isInAppBrowser — 앱 안 브라우저 표식', () => {
  it.each([
    ['페이스북 안드로이드(FB_IAB·FBAV)', FB_ANDROID],
    ['인스타 아이폰(Instagram)', IG_IPHONE],
    ['페이스북 아이폰(FBAN·FBIOS)', FB_IPHONE],
    ['라인(Line/)', LINE_ANDROID],
    ['카카오톡(KAKAOTALK)', KAKAO_IPHONE],
    ['안드로이드 웹뷰(; wv))', ANDROID_WEBVIEW],
  ])('%s 는 앱 안 브라우저다', (_, ua) => {
    expect(isInAppBrowser(ua)).toBe(true)
  })

  it.each([
    ['크롬 안드로이드', CHROME_ANDROID],
    ['사파리 아이폰', SAFARI_IPHONE],
    ['크롬 아이폰(CriOS)', CHROME_IPHONE],
    ['데스크톱 크롬', DESKTOP_CHROME],
    ['파이어폭스 안드로이드', FIREFOX_ANDROID],
  ])('%s 는 앱 안 브라우저가 아니다', (_, ua) => {
    expect(isInAppBrowser(ua)).toBe(false)
  })

  it('표식 하나만 있어도 참이다 — FBAV 만, FBAN 만, FBIOS 만, FB_IAB 만', () => {
    for (const mark of ['FBAV/1', 'FBAN/x', 'FBIOS', 'FB_IAB/x']) expect(isInAppBrowser(`${SAFARI_IPHONE} [${mark}]`), mark).toBe(true)
  })

  it('대소문자를 가린다 — instagram·kakaotalk·line/ 소문자는 표식이 아니다', () => {
    expect(isInAppBrowser(`${SAFARI_IPHONE} instagram 350.0`)).toBe(false)
    expect(isInAppBrowser(`${SAFARI_IPHONE} kakaotalk 10.8`)).toBe(false)
    expect(isInAppBrowser(`${CHROME_ANDROID} line/14.19`)).toBe(false)
  })

  it('빈 UA 는 거짓', () => {
    expect(isInAppBrowser('')).toBe(false)
  })
})

describe('keepEnv — 위에서부터 먼저 맞는 것', () => {
  it('1. standalone 이면 installed — 앱 안 브라우저 UA 여도 standalone 이 먼저', () => {
    expect(env(FB_ANDROID, true, false)).toBe('installed')
    expect(env(IG_IPHONE, true, false)).toBe('installed')
    expect(env(CHROME_ANDROID, true, true)).toBe('installed')
    expect(env(SAFARI_IPHONE, true, false)).toBe('installed')
    expect(env(DESKTOP_CHROME, true, true)).toBe('installed')
  })

  it('2. 앱 안 브라우저 + Android → in_app_android', () => {
    expect(env(FB_ANDROID)).toBe('in_app_android')
    expect(env(LINE_ANDROID)).toBe('in_app_android')
    expect(env(ANDROID_WEBVIEW)).toBe('in_app_android')
  })

  it('2. 앱 안 브라우저가 설치 창보다 먼저 — canPromptInstall 이어도 in_app_android', () => {
    expect(env(FB_ANDROID, false, true)).toBe('in_app_android')
  })

  it('3. 앱 안 브라우저 + iPhone·iPad·iPod → in_app_ios', () => {
    expect(env(IG_IPHONE)).toBe('in_app_ios')
    expect(env(FB_IPHONE)).toBe('in_app_ios')
    expect(env(KAKAO_IPHONE)).toBe('in_app_ios')
    expect(env(IG_IPHONE.replace('iPhone; CPU iPhone OS', 'iPad; CPU OS'))).toBe('in_app_ios')
    expect(env(IG_IPHONE.replace('iPhone; CPU iPhone OS', 'iPod touch; CPU iPhone OS').replace(/iPhone/g, 'iPod'))).toBe('in_app_ios')
    expect(env(IG_IPHONE, false, true)).toBe('in_app_ios')
  })

  it('4. 앱 안 브라우저(그 밖의 기기) → other — 설치 창이 있어도', () => {
    expect(env(FB_DESKTOP)).toBe('other')
    expect(env(FB_DESKTOP, false, true)).toBe('other')
  })

  it('5. 설치 창을 띄울 수 있으면 installable', () => {
    expect(env(CHROME_ANDROID, false, true)).toBe('installable')
    expect(env(DESKTOP_CHROME, false, true)).toBe('installable')
  })

  it('5. 설치 창이 사파리 판정보다 먼저 — 아이폰 사파리 UA 라도 canPromptInstall 이면 installable', () => {
    expect(env(SAFARI_IPHONE, false, true)).toBe('installable')
  })

  it('6. 설치 창이 없는 Android 브라우저 → android_menu', () => {
    expect(env(CHROME_ANDROID)).toBe('android_menu')
    expect(env(FIREFOX_ANDROID)).toBe('android_menu')
  })

  it('7. iPhone·iPad·iPod + Safari, CriOS·FxiOS·EdgiOS 없음 → ios_safari', () => {
    expect(env(SAFARI_IPHONE)).toBe('ios_safari')
    expect(env(SAFARI_IPAD)).toBe('ios_safari')
  })

  it('8. 아이폰의 크롬·파이어폭스·엣지는 ios_safari 가 아니라 other', () => {
    expect(env(CHROME_IPHONE)).toBe('other')
    expect(env(FIREFOX_IPHONE)).toBe('other')
    expect(env(EDGE_IPHONE)).toBe('other')
  })

  it('8. 설치 창 없는 데스크톱 → other', () => {
    expect(env(DESKTOP_CHROME)).toBe('other')
  })
})

describe('parseKeepParam — ?keep= 읽기', () => {
  it("'?keep=32,25' → [32, 25]", () => {
    expect(parseKeepParam('?keep=32,25')).toEqual([32, 25])
  })

  it('utm_* 등 다른 쿼리가 섞여 있어도 keep 만 읽는다', () => {
    expect(parseKeepParam('?utm_source=facebook&keep=32,25&utm_campaign=kpop')).toEqual([32, 25])
  })

  it('keep 이 없거나 비면 []', () => {
    expect(parseKeepParam('')).toEqual([])
    expect(parseKeepParam('?utm_source=facebook')).toEqual([])
    expect(parseKeepParam('?keep=')).toEqual([])
  })

  it('양의 정수만 남긴다 — 0·음수·소수·글자는 버린다', () => {
    expect(parseKeepParam('?keep=0,-3,abc,1.5,7')).toEqual([7])
    expect(parseKeepParam('?keep=x,y')).toEqual([])
  })

  it('같은 값은 처음 것만, 순서는 그대로', () => {
    expect(parseKeepParam('?keep=5,3,5,3,9')).toEqual([5, 3, 9])
  })

  it('최대 6개', () => {
    expect(parseKeepParam('?keep=1,2,3,4,5,6,7,8')).toEqual([1, 2, 3, 4, 5, 6])
  })
})

describe('keepUrl — 넘기는 주소', () => {
  it("('https://app.scenetrip.io', [32, 25]) → '/my-map?keep=32,25'", () => {
    expect(keepUrl('https://app.scenetrip.io', [32, 25])).toBe('https://app.scenetrip.io/my-map?keep=32,25')
  })

  it('id 가 없으면 /my-map 만', () => {
    expect(keepUrl('https://app.scenetrip.io', [])).toBe('https://app.scenetrip.io/my-map')
  })

  it('6개까지만 싣는다', () => {
    expect(keepUrl('https://app.scenetrip.io', [1, 2, 3, 4, 5, 6, 7, 8])).toBe('https://app.scenetrip.io/my-map?keep=1,2,3,4,5,6')
  })

  it('keepUrl 로 만든 주소를 parseKeepParam 이 그대로 되읽는다', () => {
    const url = new URL(keepUrl('https://app.scenetrip.io', [32, 25, 7]))
    expect(parseKeepParam(url.search)).toEqual([32, 25, 7])
  })
})

describe('handoffHref — 바깥 브라우저로 넘기는 주소', () => {
  it('in_app_android → intent:// (크롬 package 지정 없음), 명세 예시 그대로', () => {
    expect(handoffHref('in_app_android', 'https://app.scenetrip.io/my-map?keep=32')).toBe(
      'intent://app.scenetrip.io/my-map?keep=32#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fapp.scenetrip.io%2Fmy-map%3Fkeep%3D32;end',
    )
  })

  it('in_app_android — 작품이 여럿이면 fallback 은 encodeURIComponent 로 쉼표까지 감싼다', () => {
    expect(handoffHref('in_app_android', 'https://app.scenetrip.io/my-map?keep=32,25')).toBe(
      'intent://app.scenetrip.io/my-map?keep=32,25#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fapp.scenetrip.io%2Fmy-map%3Fkeep%3D32%2C25;end',
    )
  })

  it('in_app_android — package= 를 넣지 않는다', () => {
    expect(handoffHref('in_app_android', 'https://app.scenetrip.io/my-map?keep=32')).not.toContain('package=')
  })

  it('in_app_ios → x-safari- 를 앞에 붙인다', () => {
    expect(handoffHref('in_app_ios', 'https://app.scenetrip.io/my-map?keep=32')).toBe('x-safari-https://app.scenetrip.io/my-map?keep=32')
  })

  it.each(['installable', 'android_menu', 'ios_safari', 'installed', 'other'] as const)('%s → null', (e) => {
    expect(handoffHref(e, 'https://app.scenetrip.io/my-map?keep=32')).toBeNull()
  })
})

// ── §2-3
const work = (id: number, placeCount: number, delisted?: boolean): ContentSummary => ({
  id,
  category: 'drama',
  title: `작품${id}`,
  placeCount,
  ...(delisted === undefined ? {} : { delisted }),
})

describe('moreWorks — 더 담을 작품', () => {
  it('촬영지가 있고, 빠지지 않았고, 제외 목록에 없는 작품만 받은 순서 그대로', () => {
    const works = [work(1, 3), work(2, 0), work(3, 5, true), work(4, 2, false), work(5, 4), work(6, 1)]
    expect(moreWorks(works, new Set([5])).map((w) => w.id)).toEqual([1, 4, 6])
  })

  it('limit 기본값은 5', () => {
    const works = [1, 2, 3, 4, 5, 6, 7].map((id) => work(id, 1))
    expect(moreWorks(works, new Set()).map((w) => w.id)).toEqual([1, 2, 3, 4, 5])
  })

  it('limit 을 주면 앞에서 그만큼 — 거른 뒤에 센다', () => {
    const works = [work(1, 0), work(2, 1), work(3, 1), work(4, 1)]
    expect(moreWorks(works, new Set([2]), 2).map((w) => w.id)).toEqual([3, 4])
  })

  it('남는 것이 없으면 []', () => {
    expect(moreWorks([work(1, 3)], new Set([1]))).toEqual([])
    expect(moreWorks([], new Set())).toEqual([])
  })
})

let seq = 0
const item = (p: Partial<CartItem>): CartItem => ({ placeId: ++seq, name: `장소${seq}`, addedAt: '2026-11-14T00:00:00Z', ...p })

describe('mapGroups — 작품별 묶음', () => {
  it('처음 나온 순서로 묶고, 출처 없는 것은 한 묶음으로 맨 뒤', () => {
    const items = [
      item({ sourceContentId: null }),
      item({ sourceContentId: 20, sourceContentTitle: '도깨비' }),
      item({ sourceContentId: 10, sourceContentTitle: '케이팝 데몬 헌터스' }),
      item({ sourceContentId: 20, sourceContentTitle: '도깨비' }),
      item({}),
      item({ sourceContentId: 10, sourceContentTitle: '케이팝 데몬 헌터스' }),
      item({ sourceContentId: 10, sourceContentTitle: '케이팝 데몬 헌터스' }),
    ]
    expect(mapGroups(items)).toEqual([
      { contentId: 20, title: '도깨비', count: 2 },
      { contentId: 10, title: '케이팝 데몬 헌터스', count: 3 },
      { contentId: null, title: null, count: 2 },
    ])
  })

  it('title 은 그 묶음 첫 항목의 sourceContentTitle — 없으면 null', () => {
    const items = [item({ sourceContentId: 7 }), item({ sourceContentId: 7, sourceContentTitle: '나중 제목' })]
    expect(mapGroups(items)).toEqual([{ contentId: 7, title: null, count: 2 }])
  })

  it('출처 없는 것만 있으면 null 묶음 하나', () => {
    expect(mapGroups([item({}), item({ sourceContentId: null })])).toEqual([{ contentId: null, title: null, count: 2 }])
  })

  it('빈 목록이면 []', () => {
    expect(mapGroups([])).toEqual([])
  })
})

describe('mapBounds — 담은 곳을 다 보이게', () => {
  it('위도·경도가 둘 다 숫자인 항목으로 최소·최대', () => {
    const items = [
      item({ latitude: 37.5, longitude: 127.0 }),
      item({ latitude: 35.1, longitude: 129.04 }),
      item({ latitude: 33.4, longitude: 126.5 }),
    ]
    expect(mapBounds(items)).toEqual({ minLat: 33.4, minLng: 126.5, maxLat: 37.5, maxLng: 129.04 })
  })

  it('좌표가 하나라도 없는 항목은 빼고 계산한다', () => {
    const items = [
      item({ latitude: 37.5, longitude: 127.0 }),
      item({ latitude: 10 }),
      item({ longitude: 200 }),
      item({}),
      item({ latitude: 35.1, longitude: 129.04 }),
    ]
    expect(mapBounds(items)).toEqual({ minLat: 35.1, minLng: 127.0, maxLat: 37.5, maxLng: 129.04 })
  })

  it('한 곳뿐이면 최소와 최대가 같다', () => {
    expect(mapBounds([item({ latitude: 37.5, longitude: 127.0 })])).toEqual({ minLat: 37.5, minLng: 127.0, maxLat: 37.5, maxLng: 127.0 })
  })

  it('그런 항목이 없으면 null', () => {
    expect(mapBounds([item({}), item({ latitude: 37.5 })])).toBeNull()
    expect(mapBounds([])).toBeNull()
  })
})
