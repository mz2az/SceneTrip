/*
 * 가이드 요청의 재료 — iOS RouteGuide.Context / RouteGuide.request (RouteGuide.swift).
 *
 * 화면 상태(`Context`)가 곧 모델의 눈이다 — 「1번 주변 맛집」·「다 돌았어?」·「2일차에서 빼 줘」를 알아듣게 한다. 좌표는 서버가 번호를
 * 좌표로 바꾸는 데 쓰고 모델에게는 번호와 이름만 간다. 질문마다 다시 만든다 — 계약 `GuideContext`.
 */
import type { components } from '@/api/generated/schema'
import type { GuideChatRequest } from './guideTurn'

export type GuideContext = components['schemas']['GuideContext']
export type GuideStop = components['schemas']['GuideStop']
export type GuidePlan = components['schemas']['GuidePlan']
export type GuideTrip = components['schemas']['GuideTrip']

/** 대화의 한 말. */
export interface GuideTurnMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
}

/** 계약 상한 40 — 넘치면 오래된 것부터 잊는다. 마지막이 이번 질문이라 뒤를 남긴다. */
export const HISTORY_LIMIT = 40

/**
 * 이번 턴의 요청을 **한 번** 만든다. 다시 보낼 때(자동·「다시 시도」)는 이것을 새로 만들지 않는다 — 서버는 같은 멱등 키에 본문 전체가
 * 같은지 보는데(지문), 본문에 위치와 화면 상태가 들어 있어 다시 읽으면 달라진다. 달라지면 `422 IDEMPOTENCY_KEY_REUSED` 다(MZ2AZ-366).
 */
export function buildRequest(p: { history: readonly GuideTurnMessage[]; here: { latitude: number; longitude: number }; sessionId: string; context?: GuideContext | null }): GuideChatRequest {
  return {
    sessionId: p.sessionId,
    latitude: p.here.latitude,
    longitude: p.here.longitude,
    messages: p.history.slice(-HISTORY_LIMIT).map((t) => ({ role: t.role, content: t.text })),
    ...(p.context ? { context: p.context } : {}),
  }
}

/** 코스에 담긴 지점 한 곳 — 지도의 번호 핀과 같은 번호여야 한다. */
export interface ContextSpot {
  number: number
  name: string
  kind: string | null
  latitude: number
  longitude: number
  visited?: boolean
}

export const toGuideStop = (s: ContextSpot): GuideStop => ({
  number: s.number, name: s.name, category: s.kind, latitude: s.latitude, longitude: s.longitude, visited: s.visited ?? false,
})

export interface ContextTrip {
  phase: 'plan' | 'guiding' | 'arrived'
  course: string
  day: number
  days: number
  targetNumber: number | null
  /** 내 자리에서 가는 곳까지 직선 m. 도로 거리가 아니고 모델에게도 그렇게 말한다. */
  targetMeters: number | null
  walkedKilometers: number
}

export function buildContext(p: { stops: readonly ContextSpot[]; picked?: ContextSpot | null; trip?: ContextTrip | null; plan?: GuidePlan | null }): GuideContext {
  return {
    stops: p.stops.map(toGuideStop),
    ...(p.picked ? { picked: toGuideStop(p.picked) } : {}),
    ...(p.trip ? { trip: p.trip } : {}),
    ...(p.plan ? { plan: p.plan } : {}),
  }
}
