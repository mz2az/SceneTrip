/*
 * 「길 안내를 위해 카카오에 현재 위치를 보냈어요.」 — iOS Models/LocationConsentSheet.swift `KakaoDirectionsBanner`. 길찾기가 성공하면 4초 동안 위에 뜬다(한 사용자 요청에 한 번).
 */
import { useEffect } from 'react'
import { Txt } from '@/design/primitives'
import { tr } from '@/i18n'
import type { TripSession, TripState } from './tripSession'
import './tripUi.css'

const SHOW_MS = 4000
/** iOS `.padding(.top, 54)` — 상태바 아래. 웹에는 상태바가 없어 편집 화면 머리줄 아래로 둔다. */
const TOP = 54

export function KakaoNotice({ session, state, top = TOP }: { session: TripSession; state: TripState; top?: number }) {
  const id = state.notice
  useEffect(() => {
    if (id === null) return
    const t = setTimeout(() => session.hideNotice(id), SHOW_MS)
    return () => clearTimeout(t)
  }, [id, session])
  if (id === null) return null
  return (
    <div className="tp-toast" style={{ top }} data-testid="kakao-location-notice">
      <Txt size={15} weight="semibold" color="white" style={{ padding: '9px 14px', borderRadius: 16, background: 'var(--pin-deep)' }}>{tr('길 안내를 위해 카카오에 현재 위치를 보냈어요.')}</Txt>
    </div>
  )
}
