/*
 * SwiftUI `ContentUnavailableView` — 가운데에 큰 기호, 굵은 제목, 작은 설명. 비어 있음·못 받음 화면이 쓴다.
 * 제목 22 bold, 설명 15, 기호 잉크 49(lucide 는 칸의 0.9 → 54) (iOS 스크린샷 실측: 제목·설명 글자는 웹 글꼴보다 약 4.5% 좁아 자간을 조금 줄였다).
 */
import type { ReactNode } from 'react'
import { Icon } from '@/design/Icon'
import { Txt, VStack } from '@/design/primitives'

const TITLE_KERNING = -0.75
const DESCRIPTION_KERNING = -0.45

/** `lift` — 글자를 그만큼 위로(아이콘 자리는 그대로): 찜한 작품·장바구니 시트가 iOS 보다 글자가 1~3pt 아래라 맞춘다. */
export function ContentUnavailable({ title, symbol, description, children, lift = 0 }: { title: string; symbol: string; description?: ReactNode; children?: ReactNode; lift?: number }) {
  return (
    <VStack align="center" justify="center" style={{ flex: 1, minHeight: 0, padding: `0 24px ${50 + lift}px` }}>
      <Icon name={symbol} size={54} weight="regular" color="secondary" style={{ marginBottom: 12 - lift, transform: 'translateY(-2.5px)' }} />
      <Txt as="div" size={22} weight="bold" align="center" kerning={TITLE_KERNING} style={{ lineHeight: '27px' }}>{title}</Txt>
      {description && (
        <Txt as="div" size={15} color="secondary" align="center" kerning={DESCRIPTION_KERNING} style={{ lineHeight: '17.9px', marginTop: 1 }}>{description}</Txt>
      )}
      {children}
    </VStack>
  )
}
