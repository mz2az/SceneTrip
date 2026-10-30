/*
 * 코스를 담기 전에도 사용할 수 있는 가이드 입구 — iOS RouteTab/RouteGuideStart.swift. 열기만 해서는 모델을 호출하지 않는다.
 */
import { Divider, Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { BorderedButton, AccentCapsule } from './guideUi'

/** 한국어 원문이 번역 표의 열쇠다 — 누르면 번역한 문장이 질문으로 나간다. */
export const START_PROMPTS = ['주변 촬영지 알려줘', '주변 음식점 알려줘', '주변 카페 알려줘', '주변 명소 알려줘'] as const

export interface GuideStartProps {
  hasLocation: boolean
  anchorActionTitle: string
  onChangeAnchor?: () => void
  cartPlaces: readonly string[]
  onOpenCart?: () => void
  onOpenNearby?: () => void
  onOpenPlanner?: () => void
  showQuestions?: boolean
  canAsk: boolean
  onAsk: (question: string) => void
}

export function GuideStart({ hasLocation, anchorActionTitle, onChangeAnchor, cartPlaces, onOpenCart, onOpenNearby, onOpenPlanner, showQuestions = true, canAsk, onAsk }: GuideStartProps) {
  return (
    <VStack spacing={10} align="start">
      <Txt size={15} weight="semibold">{tr('무엇을 도와드릴까요?')}</Txt>
      <Txt size={12} color="secondary">{tr('장소를 코스에 담기 전에도 주변을 찾아볼 수 있어요.')}</Txt>
      {onOpenNearby && (
        <BorderedButton size={15} weight={600} onClick={onOpenNearby} id="guide-nearby-places">{tr('지도 주변 촬영지 보기')}</BorderedButton>
      )}
      {hasLocation && onChangeAnchor && (
        <BorderedButton size={12} onClick={onChangeAnchor} id="guide-change-anchor">{anchorActionTitle}</BorderedButton>
      )}
      <Divider style={{ alignSelf: 'stretch' }} />
      {onOpenPlanner && (
        <>
          <BorderedButton size={15} weight={600} onClick={onOpenPlanner} id="guide-ai-planner">{tr('코스 추천받기 (AI)')}</BorderedButton>
          <Txt size={11} color="secondary">{tr('AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요.')}</Txt>
        </>
      )}
      {showQuestions && (
        <>
          <Txt size={12} weight="semibold">{tr('AI에게 주변 장소 물어보기')}</Txt>
          {START_PROMPTS.map((prompt) => (
            <Pressable key={prompt} plain disabled={!canAsk} onClick={() => onAsk(tr(prompt))} style={{ padding: '8px 12px', borderRadius: 999, background: 'var(--system-gray6)', opacity: canAsk ? 1 : 0.35, cursor: canAsk ? 'pointer' : 'default', alignSelf: 'flex-start' }}>
              <Txt size={12}>{tr(prompt)}</Txt>
            </Pressable>
          ))}
          <Txt size={11} color="secondary">{tr('추천 장소는 골라 담으세요. 일정 변경 요청은 초안에 반영돼요.')}</Txt>
        </>
      )}
      {onOpenCart && (
        <>
          <Divider style={{ alignSelf: 'stretch' }} />
          <Txt size={12} color="secondary" lines={3}>{cartPlaces.length === 0 ? tr('장바구니에 담긴 장소가 없어요.') : cartPlaces.slice(0, 3).join(' · ')}</Txt>
          <BorderedButton size={12} onClick={onOpenCart}>{tr('장바구니 장소 보기')}</BorderedButton>
        </>
      )}
    </VStack>
  )
}

export { AccentCapsule }
