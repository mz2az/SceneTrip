/*
 * 여행 가이드 대화창 — iOS RouteTab/RouteGuideSheet.swift (MZ2AZ-223).
 *
 * 프로토타입 v6 의 「떠 있는 챗봇」을 옮긴 것이다. 오른쪽에서 미끄러져 나오는 **고정 크기 창**(`GuidePanel`)이라 지도가 계속 보인다 — 「이 근처 한식집」의 답이
 * 어디쯤인지는 지도가 말해 준다. 대화는 창 밖(`GuideSession`)에서 들고 이 창은 보여 주기만 한다 — 닫아도 남는다.
 *
 * ## 부른 도구를 보여 준다
 * 프로토타입이 정한 규칙이다 — 「답만 보면 그럴듯한 헛소리를 걸러 낼 수 없다.」 모델이 `poi_nearby` 를 안 부르고 답했다면 그 답은 지어낸 것이다.
 */
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr } from '@/i18n'
import '../poi/icons'
import { PlaceCard } from '../poi/PlaceCard'
import { PoiDot } from '../poi/PoiDot'
import { type RoutePlace, placeGroup, placeLabel, placeShownMeters, placeSymbol } from '../poi/poiPlace'
import { type ConsentGate, createConsentGate, requestLocationFor } from './consentPort'
import { failureMessage, isLimit } from './guideFailure'
import { type GuideState, type GuideSession, useGuideState } from './guideSession'
import type { GuideContext } from './guideRequest'
import type { CoordinateSource } from './guideTurn'
import { AiNotice, LimitNotice, RemainingLine, RetryButton } from './GuideNotices'
import { GuideStart } from './GuideStart'
import type { PlaceCardProps } from '../poi/PlaceCard'

const PIN_LIGHT = '143, 204, 247'
const SHEET_PADDING = 16

export interface GuideSheetProps {
  /** 대화는 창 밖에서 든다 — 닫아도 남아야 하기 때문이다. */
  session: GuideSession
  /** 어디를 기준으로 「주변」인가. 못 받았으면 null — 질문은 미리 적어 둘 수 있다. */
  here: { latitude: number; longitude: number } | null
  coordinateSource?: CoordinateSource
  anchorDescription?: string
  onChangeAnchor?: () => void
  anchorActionTitle?: string
  cartPlaces?: readonly string[]
  onOpenCart?: () => void
  onOpenNearby?: () => void
  /** 코스에 담긴 지점. 모델이 「2번 주변」을 알아듣는 재료다. */
  context?: GuideContext | null
  /** 장소를 코스에 담는다. 무엇으로 담을지는 띄운 쪽이 정한다(`courseEntry`, MZ2AZ-378). */
  onAdd?: (place: RoutePlace) => void
  /** 이미 코스에 있는가. 있으면 ⊕ 대신 체크가 뜬다 — 두 번 담기지 않는다. */
  isAdded?: (place: RoutePlace) => boolean
  /** 체크를 한 번 더 눌렀다 — 경로에서 뺀다. */
  onRemove?: (place: RoutePlace) => void
  /** 「여기로 길찾기」. 여행 중 화면만 준다 — 주면 카드에 버튼이 뜬다. */
  onReroute?: (place: RoutePlace) => void
  /** 한도 안내의 「AI 로 짜기 열기」 — 일정짜기 마법사로 가는 길. 띄운 쪽이 주지 않으면 글만 보인다. */
  onOpenPlanner?: () => void
  /** 창 줄이기. 오버레이 창이라 여닫는 상태는 띄운 쪽이 든다. */
  onClose?: () => void
  /** AI·위치 동의 — 마이페이지 담당의 시트와 `setConsentPresenter` 로 이어진다. 없으면 저장된 동의를 읽는 기본 문. */
  aiConsent?: ConsentGate
  locationConsent?: ConsentGate
  /** 카드의 별점 줄·사진 줄 — 리뷰 담당의 시트와 이어진다. */
  cardProps?: Pick<PlaceCardProps, 'onOpenReviews' | 'onOpenPhotos' | 'reloadToken' | 'fetchCard'>
}

export function GuideSheet({
  session, here, coordinateSource = 'device', anchorDescription = tr('지도에 표시된 내 위치 주변을 찾아 드립니다.'), onChangeAnchor, anchorActionTitle = '',
  cartPlaces = [], onOpenCart, onOpenNearby, context = null, onAdd, isAdded = () => false, onRemove, onReroute, onOpenPlanner, onClose, aiConsent, locationConsent, cardProps,
}: GuideSheetProps) {
  const state = useGuideState(session)
  const [draft, setDraft] = useState('')
  const panelId = useId()
  const [ai] = useState(() => aiConsent ?? createConsentGate('ai'))
  const [loc] = useState(() => locationConsent ?? createConsentGate('location'))
  const scroller = useRef<HTMLDivElement>(null)

  // 창이 떠 있는 동안만 다시 보내기·되묻기를 기다린다 — 닫거나 화면을 떠나면 끊는다(MZ2AZ-366). 편집 화면과 길찾기 화면이 같은 창을 쓰므로 창 자신이 알린다.
  useEffect(() => {
    session.setAttended(true, panelId)
    return () => session.setAttended(false, panelId)
  }, [session, panelId])
  useEffect(
    () => () => {
      ai.cancel()
      loc.cancel()
    },
    [ai, loc],
  )

  const canSend = here !== null && draft.trim() !== '' && !session.asking && !session.isLimited
  const send = useCallback(() => {
    const text = draft
    // 잠긴 동안 자판의 「완료」 로 들어오면 친 글을 지우지 않는다.
    if (!here || !canSend) return
    ai.request(() =>
      requestLocationFor(loc, coordinateSource, () => {
        setDraft('')
        void session.ask(text, here, context, coordinateSource)
      }),
    )
  }, [ai, loc, canSend, context, coordinateSource, draft, here, session])

  const retry = useCallback(() => {
    ai.request(() => requestLocationFor(loc, session.state.pending?.source ?? 'device', () => void session.retry()))
  }, [ai, loc, session])

  // ---- 스크롤: 새 줄·실패·한도 안내는 맨 아래로, 고른 줄은 그 자리로(맨 아래로 보내면 목록 끝까지 지나쳐 버린다 — 2026-08-27 사용자 지적).
  const toBottom = () => {
    const el = scroller.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }
  // iOS 는 처음 그릴 때는 맨 위에서 시작한다 — 시작 화면 머리말이 보여야 한다. 첫 실행은 건너뛴다.
  const firstScroll = useRef(true)
  useEffect(() => {
    if (firstScroll.current) {
      firstScroll.current = false
      return
    }
    toBottom()
  }, [state.turns.length, state.failure, state.limit])
  useLayoutEffect(() => {
    // 창을 접었다 다시 열면 스크롤이 맨 위에서 시작한다 — 한도에 걸려 있으면 안내가 있는 맨 아래로 내린다.
    if (state.limit.kind !== 'clear' && scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const pickedId = state.picked?.id
  useEffect(() => {
    if (!pickedId) return
    const row = scroller.current?.querySelector<HTMLElement>(`[data-place="${CSS.escape(pickedId)}"]`)
    if (row && scroller.current) scroller.current.scrollTo({ top: row.offsetTop - scroller.current.offsetTop, behavior: 'smooth' })
  }, [pickedId])

  const showStarters = session.isEmpty || onOpenCart !== undefined || onOpenNearby !== undefined
  const lastAssistant = [...state.turns].reverse().find((t) => t.role === 'assistant')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Header onClose={onClose} />
      <div ref={scroller} className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={12} align="start" pad={SHEET_PADDING}>
          {showStarters && (
            <GuideStart
              hasLocation={here !== null}
              anchorActionTitle={anchorActionTitle}
              onChangeAnchor={onChangeAnchor}
              cartPlaces={cartPlaces}
              onOpenCart={onOpenCart}
              onOpenNearby={onOpenNearby}
              onOpenPlanner={onOpenPlanner}
              showQuestions={session.isEmpty}
              canAsk={here !== null && !session.isLimited && !session.asking}
              onAsk={(q) => {
                if (!here) return
                ai.request(() => requestLocationFor(loc, coordinateSource, () => void session.ask(q, here, context, coordinateSource)))
              }}
            />
          )}
          {state.turns.map((turn) => (
            <Bubble key={turn.id} role={turn.role} text={turn.text}>
              {/* 마지막 답에만 붙인다 — 무엇을 근거로 말했는지. */}
              {turn.role === 'assistant' && turn.id === lastAssistant?.id && (
                <Evidence session={session} state={state} onAdd={onAdd} isAdded={isAdded} onRemove={onRemove} onReroute={onReroute} cardProps={cardProps} />
              )}
            </Bubble>
          ))}
          {session.asking && <Thinking state={state} />}
          {state.failure && !isLimit(state.failure) && <Failed state={state} session={session} onRetry={retry} />}
          {/* 한도 안내는 실패 줄과 따로 둔다 — 대화를 지우거나 코스를 바꿔도 잠겨 있는 이유가 보여야 한다. */}
          <LimitNotice session={session} state={state} onRetry={retry} onOpenPlanner={onOpenPlanner} />
        </VStack>
      </div>
      <Composer here={here} anchorDescription={anchorDescription} draft={draft} setDraft={setDraft} canSend={canSend} send={send} session={session} state={state} />
    </div>
  )
}

/** 손수 그린 머리줄 — 아이콘은 X 가 아니라 창 줄이기(안쪽으로 모이는 화살)다. 대화가 사라지는 게 아니라 오른쪽 동그라미로 접히는 것이라서다(2026-08-28 사용자 요청). */
function Header({ onClose }: { onClose?: () => void }) {
  return (
    <div style={{ position: 'relative', padding: '14px 12px 6px', display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 52 }}>
      <Txt size={17} weight="semibold">{tr('여행 가이드')}</Txt>
      <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ position: 'absolute', right: 12, top: 14, width: 32, height: 32, display: 'grid', placeItems: 'center', cursor: 'pointer' }}>
        <Icon name="arrow.down.right.and.arrow.up.left" size={13} weight="semibold" color="secondary" />
      </Pressable>
    </div>
  )
}

/** 사용자는 하늘색, 가이드는 연보라 — AI 가이드 단추와 경로선이 쓰는 피노 보라(`PinImage.light`)의 옅은 판이다. 흰 말풍선은 시트 배경과 구별이 안 됐다(2026-08-27). */
function Bubble({ role, text, children }: { role: 'user' | 'assistant'; text: string; children?: React.ReactNode }) {
  const user = role === 'user'
  return (
    <HStack align="start" fill>
      {user && <div style={{ minWidth: 40, flex: 1 }} />}
      <VStack spacing={6} align={user ? 'end' : 'start'} style={{ minWidth: 0, maxWidth: user ? 'calc(100% - 40px)' : undefined, flex: user ? 'none' : 1 }}>
        <Txt size={15} style={{ padding: '9px 12px', borderRadius: 14, background: user ? 'color-mix(in srgb, var(--accent) 14%, transparent)' : `rgba(${PIN_LIGHT}, 0.16)` }}>{text}</Txt>
        {children}
      </VStack>
      {!user && <div style={{ minWidth: 40 }} />}
    </HStack>
  )
}

function Evidence({ session, state, onAdd, isAdded, onRemove, onReroute, cardProps }: { session: GuideSession; state: GuideState } & Pick<GuideSheetProps, 'onAdd' | 'isAdded' | 'onRemove' | 'onReroute' | 'cardProps'>) {
  if (state.tools.length === 0 && state.places.length === 0) return null
  return (
    <VStack spacing={6} align="start" fill style={{ paddingLeft: 4 }}>
      {state.tools.length > 0 && (
        // 답만 보면 그럴듯한 헛소리를 못 거른다(프로토타입 규칙).
        <HStack spacing={6} align="start">
          <Icon name="wrench.and.screwdriver" size={11} color="tertiary" style={{ marginTop: 1 }} />
          <Txt size={11} color="tertiary">{state.tools.join(' · ')}</Txt>
        </HStack>
      )}
      {state.places.map((place) => {
        const isPicked = place.id === state.picked?.id
        // 누른 줄 바로 아래에 펼쳐진다. 아코디언이다 — 한 줄을 펼치면 앞서 펼친 것은 접힌다(`picked` 가 하나뿐이라 저절로 그렇다).
        return (
          <VStack key={place.id} spacing={6} align="stretch" fill data-place={place.id}>
            <PlaceRow place={place} isPicked={isPicked} added={isAdded?.(place) ?? false} onPick={() => session.setPicked(isPicked ? null : place)} onAdd={() => onAdd?.(place)} onRemove={() => onRemove?.(place)} />
            {isPicked && (
              <PlaceCard place={place} onAdd={onAdd ? () => onAdd(place) : undefined} added={isAdded?.(place) ?? false} onRemove={onRemove ? () => onRemove(place) : undefined} onReroute={onReroute ? () => onReroute(place) : undefined} onClose={() => session.setPicked(null)} {...cardProps} />
            )}
          </VStack>
        )
      })}
    </VStack>
  )
}

/** 줄을 누르면 지도에서 그 곳이 빨간 해태가 된다. 담는 것과 고르는 것은 다른 일이라 자리를 나눈다 — 앞서 줄 전체가 「담기」라 어디인지 보려다 코스에 들어갔다. */
function PlaceRow({ place, isPicked, added, onPick, onAdd, onRemove }: { place: RoutePlace; isPicked: boolean; added: boolean; onPick: () => void; onAdd: () => void; onRemove: () => void }) {
  const label = placeLabel(place)
  const meters = placeShownMeters(place)
  return (
    <HStack spacing={8} style={{ padding: '2px 0' }}>
      <Pressable plain onClick={onPick} style={{ flex: 1, minWidth: 0, cursor: 'pointer' }}>
        <HStack spacing={8}>
          {/* 목록 점도 지도 점과 같은 갈래 색·같은 글리프다 — 그것이 끈이다. */}
          <PoiDot group={placeGroup(place)} symbol={placeSymbol(place)} picked={isPicked} />
          <VStack spacing={1} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt size={12} weight={isPicked ? 'bold' : 'medium'} lines={1}>{label.title}</Txt>
            {/* 영어 이름이 없는 가게 — 한글 간판 아래 읽는 법(MZ2AZ-360). */}
            {label.reading && <Txt size={11} color="secondary" lines={1}>{label.reading}</Txt>}
            <Txt size={11} color="tertiary" lines={1}>{[label.category, label.address].filter(Boolean).join(' · ')}</Txt>
          </VStack>
          {meters !== null && <Txt size={11} color="tertiary" style={{ whiteSpace: 'nowrap' }}>{`${meters} m`}</Txt>}
        </HStack>
      </Pressable>
      {/* 펼칠 수 있다는 표시. 없으면 줄이 눌리는 것을 아무도 모른다. */}
      <Icon name={isPicked ? 'chevron.up' : 'chevron.down'} size={11} color="tertiary" />
      {added ? (
        // 이미 담긴 곳 — 체크가 뜨고, 한 번 더 누르면 뺀다.
        <Pressable plain onClick={onRemove} aria-label={tr('%@ · 코스에 담김', label.title)} style={{ cursor: 'pointer' }}>
          <Icon name="checkmark.circle.fill" size={13} color="secondary" />
        </Pressable>
      ) : (
        <Pressable plain onClick={onAdd} aria-label={tr('%@ 코스에 담기', label.title)} style={{ cursor: 'pointer' }}>
          <Icon name="plus.circle" size={13} color="accent" />
        </Pressable>
      )}
    </HStack>
  )
}

function Thinking({ state }: { state: GuideState }) {
  const stage = state.pending?.stage.kind
  // 첫 요청이 실패해 같은 키로 다시 보내는 중 — 멈춘 것이 아니라는 것을 말한다. / 분당 한도가 풀리기를 기다린다(길면 1 분) — 연결 탓이 아니다.
  // 몇 초 걸리는지 미리 말한다. 안 그러면 멈춘 줄 안다(실측 9~57초). 서버가 「처리 중」 이라 다시 묻는 동안에도 이 말이다 — 답은 만들어지고 있다.
  const text = stage === 'reconnecting' ? tr('다시 연결하는 중이에요') : stage === 'waitingForLimit' ? tr('요청이 많아 잠시 기다리는 중이에요') : tr('찾는 중입니다… 10초쯤 걸립니다')
  return (
    <HStack spacing={8}>
      <Spinner size={16} />
      <Txt size={12} color="secondary">{text}</Txt>
    </HStack>
  )
}

/** 실패 한 줄과, 다시 해 볼 만하면 「다시 시도」. 단추는 같은 질문을 같은 멱등 키로 다시 보낸다 — 서버가 이미 답을 만들어 두었으면 그 답이 오고, 모델을 다시 부르지 않는다. */
function Failed({ state, session, onRetry }: { state: GuideState; session: GuideSession; onRetry: () => void }) {
  if (!state.failure) return null
  return (
    <VStack spacing={8} align="start">
      <Txt size={12} weight="semibold">{tr('AI 가이드 오류')}</Txt>
      <HStack spacing={6} align="start">
        <Icon name="exclamationmark.triangle" size={13} color="var(--system-orange)" style={{ marginTop: 1 }} />
        <Txt size={13} style={{ color: 'var(--system-orange)' }}>{failureMessage(state.failure)}</Txt>
      </HStack>
      {session.canRetry && <RetryButton onRetry={onRetry} />}
    </VStack>
  )
}

function Composer({ here, anchorDescription, draft, setDraft, canSend, send, session, state }: { here: GuideSheetProps['here']; anchorDescription: string; draft: string; setDraft: (v: string) => void; canSend: boolean; send: () => void; session: GuideSession; state: GuideState }) {
  const field = useRef<HTMLTextAreaElement>(null)
  // 한 줄에서 석 줄까지 늘어난다(lineLimit 1...3).
  useLayoutEffect(() => {
    const el = field.current
    if (!el) return
    el.style.height = '0px'
    el.style.height = `${Math.min(el.scrollHeight, LINE * 3)}px`
  }, [draft])
  return (
    <div style={{ background: 'rgba(255,255,255,0.7)', backdropFilter: 'blur(20px) saturate(1.5)', WebkitBackdropFilter: 'blur(20px) saturate(1.5)' }}>
      <Txt size={11} color="secondary" style={{ display: 'block', padding: '6px 16px 0' }}>{here === null ? tr('지도의 위치를 확인하고 있어요. 질문을 미리 적어 두세요.') : anchorDescription}</Txt>
      <RemainingLine session={session} state={state} />
      <HStack spacing={8} pad="10px 16px" align="end">
        <div style={{ flex: 1, minWidth: 0, borderRadius: 999, background: 'var(--system-gray6)', padding: '9px 12px' }}>
          <textarea
            ref={field}
            rows={1}
            value={draft}
            placeholder={tr('주변에 무엇을 찾으세요?')}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault()
                send()
              }
            }}
            style={{ display: 'block', width: '100%', border: 0, outline: 0, resize: 'none', background: 'transparent', fontSize: 17, lineHeight: `${LINE}px`, padding: 0, color: 'var(--label)', fontFamily: 'inherit' }}
          />
        </div>
        <Pressable plain onClick={send} disabled={!canSend} aria-label={tr('보내기')} data-testid="guide-send" style={{ width: 30, height: 30, flex: 'none', cursor: canSend ? 'pointer' : 'default', marginBottom: 0 }}>
          <Icon name="arrow.up.circle.fill" size={30} color={canSend ? 'var(--accent)' : 'rgba(60,60,67,0.24)'} />
        </Pressable>
      </HStack>
      <AiNotice />
    </div>
  )
}

/** 입력창 한 줄 높이(17 pt 본문). */
const LINE = 22
