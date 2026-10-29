import { Icon } from '@/design/Icon'
import type { ApiFailure } from '@/api/failure'
import { Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { LargeButton } from './LargeButton'

/** 오류 안내 — iOS SearchTab/Rows.swift `ErrorView`. 다시 해 볼 만한 오류에만 「다시 시도」, 추적 id 는 복사할 수 있게 작게. */
export function ErrorBlock({ failure, onRetry }: { failure: ApiFailure; onRetry: () => void }) {
  return (
    <VStack spacing={12} align="center" justify="center" style={{ flex: 1, padding: 24 }}>
      <Icon name="exclamationmark.triangle" size={34} color="secondary" />
      <Txt size={15} align="center">{failure.userMessage}</Txt>
      {failure.isRetryable && <LargeButton variant="prominent" size="regular" onClick={onRetry}>{tr('다시 시도')}</LargeButton>}
      {failure.traceId && <Txt size={11} color="tertiary" mono style={{ userSelect: 'text' }}>{failure.traceId}</Txt>}
    </VStack>
  )
}
