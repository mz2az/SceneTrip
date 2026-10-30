/*
 * 신고·차단 화면 — iOS Reviews/ModerationViews.swift.
 *   <ModerationMenu>   리뷰·여행후기의 「⋯」 — 「신고」 와 「이 사용자 차단」
 *   <ModerationSheets> 신고 시트·차단 확인 창·안내를 올릴 자리(목록 화면에 한 번)
 *   <ReportSheet>      신고 — 이유 여섯 중 하나와 설명(선택)
 */
import { MessageSquareWarning } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import './moderation.css'
import { Alert, Sheet } from '@/design/overlay'
import { Icon, registerIcon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { signInPromptStore } from '@/state/auth'
import { useStore } from '@/state/storage'
import { type ModerationController, reportSheetPresence, useModerationState } from './controller'
import { PopupMenu } from './PopupMenu'
import {
  DETAIL_LIMIT,
  type ModerationTarget,
  type ReportOutcome,
  type ReportReason,
  REPORT_REASONS,
  reasonLabel,
  reportFinishes,
  reportMessage,
} from './rules'

registerIcon('exclamationmark.bubble', ({ size, strokeWidth, color, style }) => (
  <MessageSquareWarning size={size} strokeWidth={strokeWidth} color={color} style={style} />
))

/** 마지막으로 있던 값을 기억한다 — 닫히는 애니메이션 동안 알림창 글이 바뀌지 않게. */
function useLast<T>(value: T | null): T | null {
  const ref = useRef<T | null>(value)
  if (value !== null) ref.current = value
  return ref.current
}

// ---------------------------------------------------------------- ⋯ 메뉴

const MENU_FRAME = { width: 32, height: 28 } // ModerationViews.swift:28
const MENU_HIT_INSET = 8 // `.contentShape(Rectangle().inset(by: -8))` — 그림은 작아도 누르는 자리는 넉넉히

/** 자기 글에는 그리지 않는다(`showsMenu`) — 부르는 쪽이 거른다. 누르면 화면에 단 `<ModerationSheets>` 가 시트·확인 창을 올린다. */
export function ModerationMenu({ target, canBlock, controller }: { target: ModerationTarget; canBlock: boolean; controller: ModerationController }) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const items = [
    { label: tr('신고'), icon: 'exclamationmark.bubble', onPress: () => controller.askReport(target) },
    ...(canBlock ? [{ label: tr('이 사용자 차단'), icon: 'nosign', destructive: true, onPress: () => controller.askBlock(target) }] : []),
  ]
  return (
    <>
      <Pressable
        aria-label={tr('신고·차단 메뉴')}
        aria-haspopup="menu"
        data-testid="moderation-menu"
        onClick={(e) => {
          e.stopPropagation()
          setAnchor(e.currentTarget.getBoundingClientRect())
        }}
        style={{ position: 'relative', flex: 'none', ...MENU_FRAME, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
      >
        <span style={{ position: 'absolute', inset: -MENU_HIT_INSET }} />
        {/* 메뉴 라벨은 Menu 가 강조색으로 물들인다 — 스크린샷의 「⋯」 는 옅은 파랑이다. */}
        <Icon name="ellipsis" size={16.5} weight="semibold" color="rgba(0,136,255,0.5)" />
      </Pressable>
      {anchor && <PopupMenu items={items} anchor={anchor} onClose={() => setAnchor(null)} />}
    </>
  )
}

// ---------------------------------------------------------------- 시트 자리

/**
 * 신고 시트·차단 확인 창·안내를 올릴 자리. 목록 화면에 한 번 단다.
 * `onBlocked` — 차단했다. 그 글을 지우고 목록을 다시 받는다(서버가 이후 목록에서 그 사람의 글을 뺀다).
 */
export function ModerationSheets({ controller, onBlocked }: { controller: ModerationController; onBlocked: (target: ModerationTarget) => void }) {
  const { reporting, confirmingBlock, notice } = useModerationState(controller)
  const shownReport = useLast(reporting)
  const shownBlock = useLast(confirmingBlock)
  const shownNotice = useLast(notice)
  return (
    <>
      <Sheet open={reporting !== null} onClose={controller.dismissReport} topGap={SHEET_TOP_GAP} radius={SHEET_RADIUS} grabber label={tr('신고')} background="var(--grouped-background)">
        {shownReport && <ReportSheet target={shownReport} controller={controller} />}
      </Sheet>
      <Alert
        open={confirmingBlock !== null}
        title={tr('이 사용자를 차단할까요?')}
        message={tr('이 사람의 리뷰·후기가 더 이상 보이지 않아요. 마이페이지의 「차단한 사용자」에서 풀 수 있어요.')}
        onClose={controller.dismissBlockConfirm}
        buttons={[
          { label: tr('취소'), role: 'cancel' },
          {
            label: tr('차단'),
            role: 'destructive',
            onPress: () => {
              const target = shownBlock
              if (!target) return
              void controller.block(target).then((blocked) => {
                if (blocked) onBlocked(target)
              })
            },
          },
        ]}
      />
      <Alert
        open={notice !== null}
        title={shownNotice ?? ''}
        onClose={controller.dismissNotice}
        buttons={[{ label: tr('확인'), role: 'cancel' }]}
      />
    </>
  )
}

// ---------------------------------------------------------------- 신고 시트

const FORM_MARGIN = 20 // inset grouped Form 의 좌우 여백
const FORM_RADIUS = 24
const ROW_MIN = 44
const SEPARATOR_INSET = 16 // 폼 줄 사이 구분선은 왼쪽이 들어가 있다
// 큰 시트의 위쪽 여백·모서리 — iOS 스크린샷(audit/15-community-post-ios.png)에서 잰 값
const SHEET_TOP_GAP = 62
const SHEET_RADIUS = 38

function FormSection({ header, footer, children }: { header: string; footer?: string; children: React.ReactNode }) {
  return (
    <VStack spacing={0} style={{ marginBottom: 22 }}>
      <Txt size={13} color="secondary" style={{ padding: `0 ${FORM_MARGIN + 16}px 7px` }}>{header}</Txt>
      <div style={{ margin: `0 ${FORM_MARGIN}px`, background: 'var(--secondary-grouped-background)', overflow: 'hidden', ...rr(FORM_RADIUS) }}>{children}</div>
      {footer && <Txt size={13} color="secondary" style={{ padding: `7px ${FORM_MARGIN + 16}px 0` }}>{footer}</Txt>}
    </VStack>
  )
}

/** 신고 — 이유 여섯 중 하나와 설명(선택). 보내면 「접수됐어요」 를 보이고 닫는다. */
export function ReportSheet({ target, controller }: { target: ModerationTarget; controller: ModerationController }) {
  const { sending } = useModerationState(controller)
  const signInRequested = useStore(signInPromptStore, (s) => s.requested)
  const [reason, setReason] = useState<ReportReason | null>(null)
  const [detail, setDetail] = useState('')
  const [outcome, setOutcome] = useState<ReportOutcome | null>(null)
  const shownOutcome = useLast(outcome)
  const dismiss = controller.dismissReport

  // 떠 있는 동안 밑의 화면은 로그인 화면을 올리지 않는다(ReportSheetPresence). 신고 중에 세션이 풀려 로그인을 청하면 이 시트가
  // 먼저 닫히고, 닫힌 뒤 밑의 화면이 로그인 화면을 올린다.
  useEffect(() => {
    reportSheetPresence.appeared(signInPromptStore.get().requested, dismiss)
    return () => reportSheetPresence.disappeared()
  }, [dismiss])
  useEffect(() => {
    reportSheetPresence.signInChanged(signInRequested, dismiss)
  }, [signInRequested, dismiss])

  const submit = async () => {
    if (!reason) return
    setOutcome(await controller.report(target, reason, detail))
  }

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <HStack justify="between" pad="6px 16px 0" style={{ minHeight: 50 }}>
        <Pressable onClick={dismiss} disabled={sending} style={{ minWidth: 70, textAlign: 'left' }}>
          <Txt size={17} color="accent">{tr('취소')}</Txt>
        </Pressable>
        <Txt size={17} weight="semibold">{tr('신고')}</Txt>
        <div style={{ minWidth: 70, display: 'flex', justifyContent: 'flex-end' }}>
          {sending ? (
            <Spinner />
          ) : (
            <Pressable onClick={() => void submit()} disabled={reason === null}>
              <Txt size={17} weight="semibold" color={reason === null ? 'tertiary' : 'accent'}>{tr('보내기')}</Txt>
            </Pressable>
          )}
        </div>
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, paddingTop: 16, paddingBottom: 24 }}>
        <FormSection header={tr('신고 이유')} footer={tr('운영자가 확인하고 규칙을 어긴 글은 내려요.')}>
          {REPORT_REASONS.map((item, i) => (
            <div key={item}>
              {i > 0 && <div style={{ marginLeft: SEPARATOR_INSET, borderTop: '0.33px solid var(--separator)' }} />}
              <Pressable
                plain
                role="radio"
                aria-checked={reason === item}
                onClick={() => setReason(item)}
                style={{ width: '100%', minHeight: ROW_MIN, padding: '0 16px' }}
              >
                <HStack justify="between">
                  <Txt size={17}>{reasonLabel(item)}</Txt>
                  {reason === item && <Icon name="checkmark" size={17} weight="semibold" color="accent" />}
                </HStack>
              </Pressable>
            </div>
          ))}
        </FormSection>
        <FormSection header={tr('설명')} footer={`${[...detail].length}/${DETAIL_LIMIT}`}>
          <textarea
            className="mod-field"
            value={detail}
            placeholder={tr('자세한 내용 (선택)')}
            rows={3}
            disabled={sending}
            onChange={(e) => setDetail([...e.target.value].slice(0, DETAIL_LIMIT).join(''))}
            style={{
              display: 'block', width: '100%', resize: 'none', border: 0, outline: 0, background: 'transparent',
              padding: '12px 16px', fontSize: 17, lineHeight: 1.3, minHeight: 3 * 22 + 24, maxHeight: 6 * 22 + 24,
              // 줄 수에 맞춰 늘어난다 (`.lineLimit(3 ... 6)`)
              fieldSizing: 'content',
            } as React.CSSProperties}
          />
        </FormSection>
      </div>
      <Alert
        open={outcome !== null}
        title={shownOutcome ? reportMessage(shownOutcome) : ''}
        onClose={() => setOutcome(null)}
        buttons={[
          {
            label: tr('확인'),
            role: 'cancel',
            onPress: () => {
              if (shownOutcome && reportFinishes(shownOutcome)) dismiss()
            },
          },
        ]}
      />
    </VStack>
  )
}
