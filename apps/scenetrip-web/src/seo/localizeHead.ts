/*
 * 첫 화면(`/`) 꼬리표의 언어판 — 검색 결과·링크 미리보기에 보이는 제목·설명·대표 주소.
 * 영어가 기본이고(`index.html` 에 그대로 적혀 있다), 한국어판 주소 `/?lang=ko` 는 Pages Function 이 이 함수로 바꿔 내보낸다.
 * 자바스크립트로 바꾸지 않는 이유: 카카오톡·인스타그램 같은 링크 미리보기 봇은 스크립트를 돌리지 않는다.
 * 계획: docs/project/plans/web-seo-bilingual.md
 */
export type SeoLang = 'ko' | 'en'

export const SEO_COPY: Record<SeoLang, { title: string; description: string; url: string; locale: string }> = {
  en: {
    title: 'SceneTrip — K-Drama & Movie Filming Locations Trip Planner',
    description:
      'Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés.',
    url: 'https://app.scenetrip.io/',
    locale: 'en_US',
  },
  ko: {
    title: 'SceneTrip — K-드라마·영화 촬영지 여행 코스',
    description: '드라마·영화 속 그 장면이 찍힌 곳으로. 작품을 고르면 촬영지와 주변 맛집·카페를 엮어 한국 여행 코스를 짜 드려요.',
    url: 'https://app.scenetrip.io/?lang=ko',
    locale: 'ko_KR',
  },
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** `<tag ... key="match" ...>` 한 개의 `attr="…"` 값만 바꾼다. 태그가 없으면 그대로. */
function setAttr(html: string, tag: string, key: string, match: string, attr: string, value: string): string {
  const tagRe = new RegExp(`<${tag}\\b[^>]*\\b${key}="${match}"[^>]*>`, 'i')
  return html.replace(tagRe, (whole) => whole.replace(new RegExp(`(\\b${attr}=")[^"]*(")`, 'i'), `$1${value}$2`))
}

/** 영어 `index.html` 을 `lang` 판으로 바꾼다. 바꾸는 것은 html lang·title·description·canonical·og:url/title/description/locale 뿐이다. */
export function localizeHead(html: string, lang: SeoLang): string {
  const c = SEO_COPY[lang]
  const title = escapeHtml(c.title)
  const description = escapeHtml(c.description)
  const url = escapeHtml(c.url)
  let out = html.replace(/<html\b([^>]*?)\blang="[^"]*"/i, `<html$1lang="${lang}"`)
  out = out.replace(/(<title\b[^>]*>)[\s\S]*?(<\/title>)/i, `$1${title}$2`)
  out = setAttr(out, 'meta', 'name', 'description', 'content', description)
  out = setAttr(out, 'link', 'rel', 'canonical', 'href', url)
  out = setAttr(out, 'meta', 'property', 'og:url', 'content', url)
  out = setAttr(out, 'meta', 'property', 'og:title', 'content', title)
  out = setAttr(out, 'meta', 'property', 'og:description', 'content', description)
  out = setAttr(out, 'meta', 'property', 'og:locale', 'content', c.locale)
  return out
}
