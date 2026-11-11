/*
 * 새로 배포된 웹이 있는지 — 「최신 버전으로 업데이트해 주세요」 창(2026-11-09 사용자 요청).
 *
 * 판 번호를 따로 만들지 않는다. 번들 이름(`/assets/index-<내용 해시>.js`)은 내용이 바뀔 때만 바뀌므로, 지금 페이지가 실은 번들과
 * 서버의 `index.html` 이 가리키는 번들이 다르면 새 판이다. 빌드에 시각·커밋 같은 값을 굽지 않아도 된다(Bazel 빌드는 결정적이어야 한다).
 */

const ENTRY = /\/assets\/index-[\w-]+\.js/

/** HTML 에서 진입 번들 경로. 없으면 null(개발 서버는 번들이 없다). */
export function entryOf(html: string): string | null {
  return ENTRY.exec(html)?.[0] ?? null
}

/** 지금 페이지의 진입 번들 — 문서의 script 태그에서. */
export function currentEntry(doc: Document = document): string | null {
  for (const s of Array.from(doc.querySelectorAll('script[src]'))) {
    const found = entryOf(s.getAttribute('src') ?? '')
    if (found) return found
  }
  return null
}

/** 둘 다 알 때만 새 판이라고 말한다 — 하나라도 모르면(오프라인·개발 서버) 조용히 넘어간다. */
export function isNewer(current: string | null, latest: string | null): boolean {
  return current !== null && latest !== null && current !== latest
}

/** 서버의 첫 화면(`/`, index.html)을 캐시 없이 받아 진입 번들을 읽는다. 실패하면 null. */
export async function latestEntry(fetcher: typeof fetch = fetch): Promise<string | null> {
  try {
    const res = await fetcher(`/?v=${Date.now()}`, { cache: 'no-store' })
    if (!res.ok) return null
    return entryOf(await res.text())
  } catch {
    return null
  }
}
