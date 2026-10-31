/*
 * 가이드 대화창의 **한도 안내와 남은 양**(MZ2AZ-366 C), 입력창 아래 **AI 답변 안내**(MZ2AZ-439) — iOS RouteTab/RouteGuideSheetLimit.swift.
 * 규칙은 `guideLimit.ts`, 상태는 `GuideSession.state.limit`·`quota`.
 */
import { HStack, Txt, VStack } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr } from '@/i18n'
import '../poi/icons'
import { AccentCapsule, useTick } from './guideUi'
import { GUIDE_LIMIT_TUNING, blockMessage, remainingOf, remainingText } from './guideLimit'
import type { GuideSession, GuideState } from './guideSession'

export function RetryButton({ onRetry }: { onRetry: () => void }) {
  return (
    <AccentCapsule onClick={onRetry} id="guide-retry">
      <Icon name="arrow.clockwise" size={12} weight="semibold" color="accent" />
      {tr('다시 시도')}
    </AccentCapsule>
  )
}

/**
 * 한도(429 `GUIDE_LIMIT_REACHED`)에 걸린 동안의 안내와, 풀린 뒤의 한 줄. **자동으로 다시 보내지 않는다.** 풀리는 때를 알면 그때까지 전송을 잠그고 남은 분을
 * 말한다. 풀리면 「이제 다시 물어볼 수 있어요」 와, 걸렸던 질문이 남아 있으면 「다시 시도」(같은 멱등 키).
 */
export function LimitNotice({ session, state, onRetry, onOpenPlanner }: { session: GuideSession; state: GuideState; onRetry: () => void; onOpenPlanner?: () => void }) {
  useTick() // 남은 분은 시간이 가면 준다 — 받은 순간의 숫자를 그대로 두지 않는다.
  const { limit } = state
  if (limit.kind === 'clear') return null
  if (limit.kind === 'lifted') {
    return (
      <VStack spacing={8} align="start">
        <HStack spacing={6} data-testid="guide-limit-lifted">
          <Icon name="checkmark.circle" size={13} color="secondary" />
          <Txt size={13} color="secondary">{tr('이제 다시 물어볼 수 있어요')}</Txt>
        </HStack>
        {session.canRetry && <RetryButton onRetry={onRetry} />}
      </VStack>
    )
  }
  return (
    <VStack spacing={8} align="start">
      <HStack spacing={6} align="start" data-testid="guide-limit-notice">
        <Icon name="hourglass" size={13} color="var(--system-orange)" style={{ marginTop: 1 }} />
        <Txt size={13} style={{ color: 'var(--system-orange)' }}>{blockMessage(limit.block, session.nowSeconds())}</Txt>
      </HStack>
      {/* 풀리는 때를 모르면 잠그지 않는다 — 걸렸던 질문을 바로 다시 보낼 수 있다(같은 멱등 키). */}
      {session.canRetry && <RetryButton onRetry={onRetry} />}
      {GUIDE_LIMIT_TUNING.plannerHint && <PlannerHint onOpenPlanner={onOpenPlanner} />}
    </VStack>
  )
}

/** 일정짜기 마법사 안내(티켓·계약 「요청 한도」). 무료라고 말하지 않는다 — 지금 설정에서는 마법사도 모델을 부른다(계획 §7-6). */
function PlannerHint({ onOpenPlanner }: { onOpenPlanner?: () => void }) {
  return (
    <VStack spacing={6} align="start">
      <Txt size={12} color="secondary">{tr('새 일정은 「AI 로 짜기」에서도 만들 수 있어요')}</Txt>
      {onOpenPlanner && (
        <AccentCapsule onClick={onOpenPlanner} id="guide-open-planner">
          <Icon name="sparkles" size={12} weight="semibold" color="accent" />
          {tr('AI 로 짜기 열기')}
        </AccentCapsule>
      )}
    </VStack>
  )
}

/**
 * 입력창 위 한 줄 — 「3번 더 물어볼 수 있어요 · 12분 뒤 다시 채워져요」. 서버가 마지막 답에 실어 준 값이다(`RateLimit-*`). 헤더가 없으면(옛 서버, 이번 실행에서
 * 아직 안 물음) 줄이 없고, 넉넉할 때도 없다. 한도 안내가 떠 있는 동안에는 그 안내가 같은 말을 하므로 숨긴다.
 */
export function RemainingLine({ session, state }: { session: GuideSession; state: GuideState }) {
  useTick()
  if (session.isLimited) return null
  const text = remainingText(remainingOf(state.quota, session.nowSeconds()))
  if (!text) return null
  return (
    <HStack spacing={6} data-testid="guide-remaining" style={{ padding: '8px 20px 0' }}>
      <Icon name="bubble.left.and.text.bubble.right" size={11} color="secondary" />
      <Txt size={11} color="secondary">{text}</Txt>
    </HStack>
  )
}

/** 입력창 바로 아래 작은 회색 줄 — 답은 모델이 만든 것이라 틀리거나 낡을 수 있다(MZ2AZ-439, 약관 초안 §4 「AI 가이드의 답변」). 시작 화면이든 대화 중이든 늘 보인다. */
export function AiNotice() {
  return (
    <Txt size={11} color="secondary" align="center" data-testid="guide-ai-notice" style={{ display: 'block', padding: '0 16px 8px' }}>
      {tr('AI 답변은 틀릴 수 있어요. 영업시간·요금·교통은 직접 확인해 주세요.')}
    </Txt>
  )
}
