/*
 * 커뮤니티 글의 화면 모델 — iOS `CommunityPost` · `PostCourse` (CommunityTab/CommunityStore.swift, CommunityBridge.swift).
 *
 * 서버 목록(`TripPostSummary`)·상세(`TripPostDetail`)와 기기에 남은 옛 글을 같은 모양으로 읽는다.
 * 서버 사진 주소는 메모리에서만 쓴다(한 시간 뒤 만료 — MZ2AZ-396). 닉네임은 글에 저장하지 않고 그릴 때마다 받는다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang, tr } from '@/i18n'

export type PostSummary = components['schemas']['TripPostSummary']
export type PostDetail = components['schemas']['TripPostDetail']
export type PostBlock = components['schemas']['TripPostBlock']
type ServerCourse = components['schemas']['TripPostCourse']
type CourseDetail = components['schemas']['CourseDetail']

// ---------------------------------------------------------------- 글에 붙인 코스

/**
 * 글에 붙인 코스 — **붙인 순간의 사본**이다 (MZ2AZ-351).
 * 코스 id 만 들고 있으면 글쓴이가 코스를 고치거나 지웠을 때 글이 달라지거나 빈다. 후기는 「내가 다녀온 그 코스」를 말하는 글이므로
 * 일차·장소까지 그대로 박아 둔다. 읽는 사람은 이것을 보고, 「내 코스로 담기」로 자기 코스를 하나 만든다.
 */
export interface PostCourseStop {
  /** 촬영지 id. 편의시설·지도에 직접 찍은 핀이면 없다. */
  placeId?: number | null
  name: string
  address?: string | null
  type?: string | null
  latitude: number
  longitude: number
  imageUrl?: string | null
  /** 편의시설 id(MZ2AZ-380). 이 칸이 생기기 전에 쓴 글에는 없고, 그 글의 편의시설은 개인 핀이다. */
  poiId?: number | null
  displayName?: string | null
  nameRoman?: string | null
  displayAddress?: string | null
  categoryLabel?: string | null
}

export interface PostCourse {
  title: string
  days: PostCourseStop[][]
  serverId?: number
  excludedPins?: number
}

/** 영어에서는 편의시설의 영문 이름·주소를 쓴다(`PostCourse.Stop.shownName`). */
export const shownName = (stop: PostCourseStop): string => (currentLang() === 'ko' ? stop.name : (stop.displayName ?? stop.name))
export const shownAddress = (stop: PostCourseStop): string | null | undefined =>
  currentLang() === 'ko' ? stop.address : (stop.displayAddress ?? stop.address)

export const placeCount = (course: PostCourse): number => course.days.reduce((n, d) => n + d.length, 0)

/** 서버 상세의 코스 → 화면 사본. 일차 번호순으로 놓는다 (`PostCourse.init(_: TripPostCourse)`). */
export function courseFromServer(course: ServerCourse): PostCourse {
  return {
    title: course.title,
    days: [...course.days]
      .sort((a, b) => a.dayNumber - b.dayNumber)
      .map((day) =>
        day.stops.map((s) => ({
          placeId: s.placeId ?? null,
          name: s.name,
          address: s.address ?? null,
          type: s.category ?? null,
          latitude: s.latitude,
          longitude: s.longitude,
          imageUrl: s.imageUrl ?? null,
          poiId: s.poiId ?? null,
          displayName: s.displayName ?? null,
          nameRoman: s.nameRoman ?? null,
          displayAddress: s.displayAddress ?? null,
          categoryLabel: s.categoryLabel ?? null,
        })),
      ),
  }
}

/**
 * 내 코스(`GET /courses/{id}`) → 글에 붙일 사본 (`PostCourse.init(from: RouteCourse)`).
 * 직접 찍은 핀(`custompin`, 또는 `poiId` 가 없는 편의시설)은 공개하지 않으므로 몇 곳인지만 센다.
 */
export function courseFromMine(detail: CourseDetail): PostCourse {
  let pins = 0
  const days = detail.days.map((day) =>
    day.items.map((item): PostCourseStop => {
      const place = item.source === 'place'
      const poi = item.source === 'poi' && item.poiId != null
      if (!place && !poi) pins += 1
      return {
        placeId: place ? (item.placeId ?? null) : null,
        name: item.name,
        address: item.address ?? null,
        type: item.category ?? null,
        latitude: item.latitude,
        longitude: item.longitude,
        imageUrl: item.imageUrl ?? null,
        poiId: poi ? (item.poiId ?? null) : null,
      }
    }),
  )
  return { title: detail.title, days, serverId: detail.id, excludedPins: pins }
}

// ---------------------------------------------------------------- 글

/** 옛 말머리. **지금은 여행후기 하나다**(2026-10-03 팀 회의) — 화면에서 고르지 않는다. 옛 글을 읽기 위해 갈래는 남겨 둔다. */
export type Board = '코스 추천' | '장소 후기' | '인증샷' | '자유'

export interface CommunityPost {
  /** 목록 key — 서버 글은 `s<id>`, 기기 옛 글은 저장된 UUID. */
  key: string
  board: Board
  title: string
  body: string
  createdAt: Date
  /** 옛 글의 첨부 — 코스 이름만 있었다. 새 글은 `course` 를 쓴다. */
  courseTitle?: string | null
  /** 옛 글의 사진 파일 이름(기기 파일) — 웹에는 그 파일이 없어 그리지 못한다. */
  photos?: string[] | null
  course?: PostCourse | null
  /** 서버의 글쓴이 이름. 탈퇴하면 없으며 소유 여부는 `serverIsMine` 으로 판정한다. */
  author?: string | null
  serverId?: number
  serverIsMine?: boolean
  remotePhotos?: string[] | null
  detailLoaded?: boolean
  bodyBlocks?: PostBlock[] | null
}

export const isMine = (post: CommunityPost): boolean => post.serverIsMine ?? post.author == null

export const postKey = (serverId: number): string => `s${serverId}`

export function postFromSummary(s: PostSummary): CommunityPost {
  return {
    key: postKey(s.id),
    board: '장소 후기',
    title: s.title,
    body: s.excerpt,
    createdAt: new Date(s.createdAt),
    courseTitle: s.course?.title ?? null,
    author: s.author?.nickname ?? null,
    serverId: s.id,
    serverIsMine: s.isMine,
    remotePhotos: s.coverPhotoUrl ? [s.coverPhotoUrl] : null,
    detailLoaded: false,
  }
}

export function postFromDetail(d: PostDetail): CommunityPost {
  return {
    key: postKey(d.id),
    board: '장소 후기',
    title: d.title,
    body: d.body,
    createdAt: new Date(d.createdAt),
    course: d.course ? courseFromServer(d.course) : null,
    author: d.author?.nickname ?? null,
    serverId: d.id,
    serverIsMine: d.isMine,
    remotePhotos: d.photoUrls,
    detailLoaded: true,
    bodyBlocks: d.bodyBlocks ?? null,
  }
}

/** 방금 쓴 글(상세)을 목록 줄(요약)로 — 목록 맨 앞에 바로 넣는다. 본문 앞 120 자, 줄바꿈은 공백 (계약 `TripPostSummary.excerpt`). */
export function summaryFromDetail(d: PostDetail): PostSummary {
  return {
    id: d.id,
    title: d.title,
    excerpt: [...d.body.replace(/\s*\n\s*/g, ' ')].slice(0, 120).join(''),
    author: d.author,
    createdAt: d.createdAt,
    coverPhotoUrl: d.photoUrls[0] ?? null,
    photoCount: d.photoUrls.length,
    course: d.course
      ? { title: d.course.title, dayCount: d.course.days.length, placeCount: d.course.days.reduce((n, day) => n + day.stops.length, 0) }
      : undefined,
    isMine: d.isMine,
  }
}

/**
 * 화면에 보일 글쓴이 이름 — **이름이 나오는 자리는 모두 이것을 쓴다.**
 *
 * 남의 글은 저장된 이름 그대로다. 내 글은 **지금 로그인한 계정의 닉네임**이고, 로그인하지 않았거나 닉네임을 아직 못 읽었으면 「나」다.
 * 닉네임을 글에 저장하지 않고 그릴 때마다 받는 이유: 기기의 글은 계정이 아니라 **기기에 묶여** 있다(`isMine`). 저장해 두면
 * 닉네임을 바꿔도 옛 이름이 남고, 로그아웃한 뒤에도 그 계정 이름이 붙어 있게 된다.
 */
export function authorName(post: CommunityPost, myNickname: string | null | undefined): string {
  if (post.author != null) return post.author
  if (post.serverId !== undefined) return tr('탈퇴한 사용자')
  const nickname = (myNickname ?? '').trim()
  return nickname === '' ? tr('나') : nickname
}

/** 글쓴이 동그라미에 넣는 한 글자 — 보이는 이름의 첫 글자다. */
export function authorInitial(post: CommunityPost, myNickname: string | null | undefined): string {
  return [...authorName(post, myNickname)][0] ?? ''
}

// ---------------------------------------------------------------- 기기에 남은 옛 글

export const LEGACY_POSTS_KEY = 'scenetrip.communityPosts'
/** Swift `Date` 의 기준 시각(2001-01-01)과 Unix 시각의 차이(초). */
const SWIFT_REFERENCE_EPOCH = 978307200

function legacyDate(raw: unknown): Date {
  if (typeof raw === 'number') return new Date((raw + SWIFT_REFERENCE_EPOCH) * 1000)
  if (typeof raw === 'string') {
    const d = new Date(raw)
    if (!Number.isNaN(d.getTime())) return d
  }
  return new Date(0)
}

/**
 * iOS 가 `UserDefaults` 에 JSON 으로 남기던 옛 글(`scenetrip.communityPosts`)을 읽는다. 웹은 쓰지 않으므로 보통 비어 있다.
 * 모양이 다른 줄은 걸러 낸다 — 깨진 한 줄이 목록 전체를 막지 않게.
 */
export function parseLegacyPosts(raw: string | null): CommunityPost[] {
  if (!raw) return []
  let list: unknown
  try {
    list = JSON.parse(raw)
  } catch {
    return []
  }
  if (!Array.isArray(list)) return []
  const out: CommunityPost[] = []
  for (const row of list as Array<Record<string, unknown>>) {
    if (!row || typeof row.id !== 'string' || typeof row.title !== 'string' || typeof row.body !== 'string') continue
    const course = row.course as { title?: unknown; days?: unknown } | undefined
    out.push({
      key: row.id,
      board: (row.board as Board) ?? '자유',
      title: row.title,
      body: row.body,
      createdAt: legacyDate(row.createdAt),
      courseTitle: typeof row.courseTitle === 'string' ? row.courseTitle : null,
      photos: Array.isArray(row.photos) ? (row.photos as string[]) : null,
      course:
        course && typeof course.title === 'string' && Array.isArray(course.days)
          ? { title: course.title, days: course.days as PostCourseStop[][] }
          : null,
      author: typeof row.author === 'string' ? row.author : null,
      serverId: typeof row.serverId === 'number' ? row.serverId : undefined,
      serverIsMine: typeof row.serverIsMine === 'boolean' ? row.serverIsMine : undefined,
    })
  }
  return out
}
