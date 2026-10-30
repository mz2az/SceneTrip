/*
 * 여행 모드의 상수와 판정 — iOS RouteTab/TripMode.swift (계획 `docs/project/plans/trip-mode.md` §2).
 *
 * 「코스 시작」 뒤 앱이 흐름을 잇는다 — 다음 성지로 길찾기, 반경 안에 **머무르면** 도착, 스탬프, 그다음 성지. 지나가기만 한 것과 들른 것을 가르는 것이 머무름이다.
 */

/** 도착으로 보는 반경(m). 촬영지 좌표가 건물 한가운데가 아닐 수 있어 넉넉하다. */
export const ARRIVE_RADIUS_METERS = 100

/** 반경 안에 이만큼 머무르면 도착이다. 기본 5분(사용자 제안). 확인용 뒷문으로 줄일 수 있다(`?dev-trip-dwell=10`, 개발 서버에서만) — 5분을 기다릴 수 없어서다. */
export const DEFAULT_DWELL_SECONDS = 300

export function dwellSeconds(): number {
  if (import.meta.env.DEV && typeof window !== 'undefined') {
    const raw = Number(new URLSearchParams(window.location.search).get('dev-trip-dwell'))
    if (raw > 0) return raw
  }
  return DEFAULT_DWELL_SECONDS
}

/**
 * 머무름 판정 — 반경 안에 **연속으로** 있었던 시간을 센다. 반경 밖으로 나가면 처음부터 다시 센다. GPS 가 한 번 튀어 밖으로 나갔다 들어오면
 * 다시 세는 것이 맞다 — 그것이 「머무름」의 뜻이다. 상태는 값이라 목적지가 바뀔 때 새로 만들면 된다.
 */
export class TripArrival {
  private enteredAt: number | null = null

  constructor(private readonly dwell: () => number = dwellSeconds) {}

  /** 새 위치를 넣는다. 머무름을 채웠으면 true — 한 번 true 를 낸 뒤 어떻게 할지는 부르는 쪽이 정한다(스탬프를 찍고 다음 목적지로). `nowMs` 는 ms. */
  observe(distanceMeters: number, nowMs: number): boolean {
    if (distanceMeters > ARRIVE_RADIUS_METERS) {
      this.enteredAt = null
      return false
    }
    const since = this.enteredAt ?? nowMs
    this.enteredAt = since
    return (nowMs - since) / 1000 >= this.dwell()
  }

  /** 반경 안에 있은 시간(초). 화면이 「도착까지 N분」을 보여 주는 데 쓴다. */
  dwelt(nowMs: number): number | null {
    return this.enteredAt === null ? null : (nowMs - this.enteredAt) / 1000
  }
}
