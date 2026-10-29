/*
 * SwiftUI `ContentUnavailableView` — 가운데에 큰 기호, 굵은 제목, 작은 설명. 비어 있음·못 받음 화면이 쓴다.
 * 제목 22 bold, 설명 15, 기호 48 (iOS 스크린샷 실측: 제목·설명 글자는 웹 글꼴보다 약 4.5% 좁아 자간을 조금 줄였다).
 */
import type { ReactNode } from 'react'
import { Icon } from '@/design/Icon'
import { Txt, VStack } from '@/design/primitives'

const TITLE_KERNING = -0.75
const DESCRIPTION_KERNING = -0.45

export function ContentUnavailable({ title, symbol, description, children }: { title: string; symbol: string; description?: ReactNode; children?: ReactNode }) {
  return (
    <VStack align="center" justify="center" style={{ flex: 1, minHeight: 0, padding: '0 24px 46px' }}>
      <Icon name={symbol} size={48} weight="regular" color="secondary" style={{ marginBottom: 18 }} />
      <Txt as="div" size={22} weight="bold" align="center" kerning={TITLE_KERNING} style={{ lineHeight: '27px' }}>{title}</Txt>
      {description && (
        <Txt as="div" size={15} color="secondary" align="center" kerning={DESCRIPTION_KERNING} style={{ lineHeight: '20px', marginTop: -1 }}>{description}</Txt>
      )}
      {children}
    </VStack>
  )
}
