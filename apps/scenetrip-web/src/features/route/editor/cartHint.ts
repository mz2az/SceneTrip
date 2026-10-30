/*
 * 직접 짜는 첫 코스에서 「장바구니」 단추를 짚어 주는 한 번의 안내 — iOS RouteTab/RouteCartHint.swift (MZ2AZ-444).
 * 표시할 때 기록한다. 닫기·시간 만료·화면 재진입도 같은 한 번이다. 강조는 즉시 시작하되 문구·seen·노출 시간은 장바구니 조회가 끝난 뒤 함께 시작한다.
 */
import { useEffect, useRef, useState } from 'react'
import { tr } from '@/i18n'
import { useCart } from '@/state/cart'
import { readStorage, writeStorage } from '@/state/storage'
import { type RouteCourse, courseStops } from '../models'

export const CART_HINT_KEY = 'scenetrip.manualCourseCartHintSeen'
/** 강조 테두리가 한 번 커지는 시간(RouteCartHintRing.duration). */
export const CART_HINT_MS = 2500

/** 새 초안 여부는 화면의 표시 방식(isNew)이 아니라 실제 저장 여부로 판단한다 — 직접 짜는 빈 코스에서 한 번만. */
export function canPresentCartHint(course: RouteCourse, seen: boolean): boolean {
  return course.serverId === null && !course.madeByAI && courseStops(course).length === 0 && !seen
}

/** 실패 시 빈 배열은 비어 있다는 증거가 아니므로 일반 안내를 쓴다. */
export function cartHintMessageKey(cartIsEmpty: boolean): string {
  return cartIsEmpty ? '장소를 담으면 여기서 코스에 넣을 수 있어요' : '담아둔 장소로 코스를 시작해 보세요'
}

const seenBefore = (): boolean => (Number(readStorage(CART_HINT_KEY) ?? 0) || 0) !== 0

export function useCartHint(course: RouteCourse) {
  const cart = useCart()
  const [highlighted, setHighlighted] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const started = useRef(false)
  const courseRef = useRef(course)
  courseRef.current = course

  // 편집기 진입 시 즉시 강조하고, 문구는 장바구니 조회 완료 후 표시한다.
  useEffect(() => {
    if (started.current || !canPresentCartHint(courseRef.current, seenBefore())) return
    started.current = true
    setHighlighted(true)
  }, [])
  useEffect(() => {
    if (!highlighted || message !== null) return
    if (cart.loadState === 'notLoaded' || cart.loadState === 'loading') return
    if (!canPresentCartHint(courseRef.current, seenBefore())) {
      setHighlighted(false)
      return
    }
    writeStorage(CART_HINT_KEY, '1')
    setMessage(tr(cartHintMessageKey(cart.loadState === 'loaded' && cart.items.length === 0)))
  }, [highlighted, message, cart.loadState, cart.items.length])
  // 문구가 뜬 뒤 노출 시간이 지나면 거둔다.
  useEffect(() => {
    if (message === null) return
    const t = window.setTimeout(() => {
      setHighlighted(false)
      setMessage(null)
    }, CART_HINT_MS)
    return () => window.clearTimeout(t)
  }, [message])

  return { highlighted, message, dismiss: () => { setHighlighted(false); setMessage(null) } }
}
