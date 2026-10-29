/*
 * 동의 시트 공통 틀 — iOS `ConsentSheet` (Models/AIConsentSheet.swift). 첫 질문·첫 AI 코스 만들기·설정에서 같은 안내를 쓴다.
 * 제목 22 bold · 안내문 17 · 약관 링크(파랑) · 「허용」 캡슐(파랑, 높이 56) · 「취소」(작게). 안쪽 여백 24, 사이 24.
 */
import { Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { GlassSheet } from '../ui/GlassSheet'

const TITLE_KERNING = -0.75

export interface ConsentSheetProps {
  open: boolean
  title: string
  disclosure: string
  linkTitle: string
  link: string
  allowTitle: string
  identifier: string
  /** 「허용하고 계속」 */
  allow: () => void
  /** 「취소」 */
  cancel: () => void
  /** 바깥을 눌러 닫기 — 취소와 같지만 시트가 닫힌 뒤 정리한다. */
  swipeDismiss: () => void
  /** 시트가 다 닫힌 뒤. */
  onClosed: () => void
}

export function ConsentSheet(p: ConsentSheetProps) {
  return (
    <GlassSheet open={p.open} onClose={p.swipeDismiss} onClosed={p.onClosed} detent="fit" label={p.title} zIndex={70}>
      <VStack spacing={24} style={{ padding: 24, overflowY: 'auto' }}>
        <Txt as="h2" size={22} weight="bold" kerning={TITLE_KERNING} style={{ lineHeight: '27px' }}>{p.title}</Txt>
        <Txt as="p" size={17} style={{ lineHeight: '21px' }}>{p.disclosure}</Txt>
        <a href={p.link} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', fontSize: 17, lineHeight: '21px' }}>{p.linkTitle}</a>
        <VStack spacing={6}>
          <Pressable
            plain
            data-testid={`${p.identifier}-allow`}
            onClick={p.allow}
            style={{ width: '100%', minHeight: 56, borderRadius: 28, background: 'var(--accent)', textAlign: 'center', padding: '14px 16px' }}
          >
            <Txt size={17} weight="semibold" color="white" style={{ lineHeight: '28px' }}>{p.allowTitle}</Txt>
          </Pressable>
          <Pressable plain data-testid={`${p.identifier}-cancel`} onClick={p.cancel} style={{ width: '100%', minHeight: 44, textAlign: 'center' }}>
            <Txt size={15} color="secondary">{tr('취소')}</Txt>
          </Pressable>
        </VStack>
      </VStack>
    </GlassSheet>
  )
}
