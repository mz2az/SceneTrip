/*
 * 사진 주소 갱신 — iOS `PhotoRenewal` · `CommunityPhotoRefresh` · `.renewsPhotos` (MZ2AZ-363·428).
 *
 * 후기 사진 주소는 서명된 것이라 한 시간 뒤 죽는다. 사진이 안 열리면 같은 상세를 다시 받아 새 주소로 바꾼다.
 * 여러 칸이 한꺼번에 실패해도 한 번만 나가고, 저절로 하는 갱신은 30 초에 한 번으로 제한한다(사람이 누른 「다시 받기」는 언제나).
 */
import { createContext, useContext } from 'react'

export const RENEW_GAP_MS = 30_000

/** 사진을 지금 다시 받을 것인가 — iOS `PhotoGalleryRules.shouldRenew`. */
export function shouldRenew(forced: boolean, last: number | null, now: number, gap = RENEW_GAP_MS): boolean {
  if (forced || last === null) return true
  return now - last >= gap
}

export interface PhotoRenewal {
  /** 갱신을 요청한다. 돌고 있으면 그 결과를 같이 받는다. 성공하면 true. */
  run(forced: boolean, work: () => Promise<void>): Promise<boolean>
  cancel(): void
}

export function createPhotoRenewal(now: () => number = Date.now): PhotoRenewal {
  let last: number | null = null
  let running: Promise<boolean> | null = null
  let generation = 0
  return {
    async run(forced, work) {
      if (running) return running
      if (!shouldRenew(forced, last, now())) return false
      last = now()
      const asked = generation
      const task = work().then(
        () => true,
        () => false,
      )
      running = task
      const success = await task
      if (asked !== generation) return false
      running = null
      return success
    },
    cancel() {
      generation += 1
      running = null
      last = null
    },
  }
}

/** 그림 컴포넌트가 「안 열린다」 를 알리는 길. `forced` 는 사람이 누른 다시 받기. */
export const PhotoRenewContext = createContext<((forced: boolean) => void) | null>(null)
export const usePhotoRenew = () => useContext(PhotoRenewContext)

/** 서명 쿼리·조각을 뗀 주소 — 새 주소로 바뀌어도 같은 사진이면 같은 이름이다 (`PhotoGalleryRules.unsigned`). */
export function unsigned(url: string): string {
  try {
    const u = new URL(url)
    u.search = ''
    u.hash = ''
    return u.toString()
  } catch {
    return url
  }
}
