/*
 * 개발 전용 — 계정·기록이 있어야 열리는 화면을 서버 쓰기 없이 읽기 전용으로 재현하는 가짜 응답 (iOS 의 확인용 뒷문과 같은 역할).
 * 개발 서버(`import.meta.env.DEV`)에서만 동작하고 제품 빌드에는 들어가지 않는다.
 *
 *   ?dev-account=1   가짜 로그인(토큰은 서버로 나가지 않는다) + GET /me · /me/reviews · /me/blocks 를 고정 응답으로
 *   ?dev-stamps=1|active   GET /courses · /courses/9001 을 방문 기록(visitedAt)이 찍힌 코스 하나로 (스탬프·편집기·여행 화면용). `active` 면 여행 중(status active, 1일차)인 코스
 *
 * 둘 중 하나라도 켜면 **GET 이 아닌 요청은 서버로 보내지 않고** 빈 성공(204)으로 돌려준다 — 코스 저장 등이 실수로 DEV 에 닿지 않게 한다.
 * 가짜 로그인의 `Authorization` 은 가짜 응답이 없는 요청에서 떼고 보낸다(DEV 가 401 을 돌려주면 세션이 풀리기 때문).
 */
import { authStore } from './state/auth'

const NOW = Date.now()
const iso = (daysAgo: number) => new Date(NOW - daysAgo * 86_400_000).toISOString()

const ME = {
  id: '00000000-0000-4000-8000-000000000001',
  nickname: '제주러버',
  nicknameConfirmed: true,
  displayName: 'Dev Tester',
  email: 'dev@example.com',
  identities: [{ provider: 'google', email: 'dev@example.com' }],
  registeredAt: iso(30),
}

const MY_REVIEWS = {
  total: 3,
  items: [
    { id: 1, rating: 5, body: '드라마에서 본 그 장면 그대로예요. 해 질 무렵에 가면 사진이 정말 예쁘게 나옵니다.', photos: [], author: { nickname: '제주러버' }, createdAt: iso(2), updatedAt: iso(2), isMine: true, target: { type: 'place', id: 309, name: '시크릿 블루 카페' } },
    { id: 2, rating: 4, body: '사람이 많아서 오전에 가는 걸 추천해요.', photos: [], author: { nickname: '제주러버' }, createdAt: iso(9), updatedAt: iso(9), isMine: true, target: { type: 'place', id: 219, name: '이화 벽화마을' } },
    { id: 3, rating: 3, body: null, photos: [], author: { nickname: '제주러버' }, createdAt: iso(20), updatedAt: iso(20), isMine: true, target: { type: 'poi', id: 1, name: '성수 베이커리' } },
  ],
}

const BLOCKS = { items: [{ id: 11, nickname: '여행자10198', blockedAt: iso(3) }, { id: 12, nickname: '촬영지탐험가', blockedAt: iso(12) }] }

const STOPS = [
  { id: 9101, placeId: 309, name: '시크릿 블루 카페', address: '31-6 Ayajinbuk-gil, Toseong-myeon, Goseong-gun, Gangwon-do', latitude: 38.277133, longitude: 128.551581, sourceContentTitle: '도깨비', visitedAt: iso(4) },
  { id: 9102, placeId: 219, name: '이화 벽화마을', address: '12 Naksanseonggwakseo 1-gil, Jongno-gu, Seoul', latitude: 37.578067, longitude: 127.007135, sourceContentTitle: '해일', visitedAt: iso(2) },
  { id: 9103, placeId: 150, name: '카페 만휴정', address: '70 Gungmalli-gil, Giran-myeon, Andong-si, Gyeongsangbuk-do', latitude: 36.415621, longitude: 128.917643, sourceContentTitle: null, visitedAt: null },
]
const item = (s: (typeof STOPS)[number]) => ({ source: 'place', poiId: null, customPinId: null, category: null, dwellMinutes: 60, distanceMetersFromPrevious: null, sourceContentId: null, imageUrl: null, ...s })

const course = (active: boolean) => ({
  id: 9001, title: '개발용 코스', dayCount: 1, startDate: null, status: active ? 'active' : 'upcoming', currentDayNo: active ? 1 : null, origin: 'self', pace: 'loose', sourceMarketCourseId: null,
  placeCount: STOPS.length, thumbnailUrl: null, createdAt: iso(5), updatedAt: iso(1),
})
const courseDetail = (active: boolean) => ({
  ...course(active),
  days: [{ dayNumber: 1, items: STOPS.map(item), dwellMinutes: 180, travelMinutes: 90, totalMinutes: 270, travelBasis: 'straight-line' }],
})

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

export function installDevMock(q: URLSearchParams): void {
  if (!import.meta.env.DEV) return
  const account = q.get('dev-account') === '1'
  const stamps = q.get('dev-stamps') === '1' || q.get('dev-stamps') === 'active'
  const active = q.get('dev-stamps') === 'active'
  if (!account && !stamps) return
  // 저장소에는 쓰지 않는다 — 이 브라우저에 남아 있는 진짜 DEV 로그인을 덮어쓰지 않게.
  if (account) authStore.set({ session: { accessToken: 'dev-mock', refreshToken: 'dev-mock', expiresAt: Date.now() + 86_400_000 }, generation: 1 })

  const real = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const req = new Request(input, init)
    const url = new URL(req.url, window.location.origin)
    const path = url.pathname.replace(/^.*\/v1/, '')
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response(null, { status: 204 })
    if (account) {
      if (path === '/me') return json(ME)
      if (path === '/me/reviews') return json(MY_REVIEWS)
      if (path === '/me/blocks') return json(BLOCKS)
      if (path === '/me/posts') return json({ items: [], total: 0 })
    }
    if (stamps) {
      if (path === '/courses') return json({ items: [course(active)], totalCount: 1 })
      if (path === '/courses/9001') return json(courseDetail(active))
    }
    if (req.headers.get('Authorization') === 'Bearer dev-mock') {
      const headers = new Headers(req.headers)
      headers.delete('Authorization')
      return real(new Request(req, { headers }), init?.signal ? { signal: init.signal } : undefined)
    }
    return real(req, init?.signal ? { signal: init.signal } : undefined)
  }
}
