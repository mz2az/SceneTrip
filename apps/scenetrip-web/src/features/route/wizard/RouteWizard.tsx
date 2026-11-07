/*
 * 코스를 만들기 전에 묻는 질문 흐름 — iOS RouteTab/RouteWizardView.swift (8/11 회의 3-3 · 3-4 확정).
 *
 * 「직접 짜기」는 기간·떠나는 날까지만 묻는다(직접 짜는 사람에게 작품과 스타일을 묻는 것은 쓸모가 없다 — 어차피 자기가 하나씩 담는다).
 * 「AI 로 짜기」는 다섯 단계 — 기간 · 떠나는 날 · 작품 · 속도 · 확인. 답을 다 받으면 바로 저장하지 않고 편집 화면으로 넘긴다
 * (회의 확정: 「AI로 초안을 만들고 바로 저장시키는 게 아니고, 거기서 수정할 수 있게 해 줘야」). 초안이 만들어지면 이 시트가 편집 화면으로 바뀐다 —
 * 시트를 새로 띄우면 닫히고 열리는 사이에 화면이 한 번 비어 사용자가 흐름을 잃는다.
 *
 * 일정 생성(`POST /guide/plan`)은 외부 AI 로 나가므로 첫 사용 전에 AI 동의를 받고, 현재 위치를 아는 경우에는 위치기반서비스 동의도 받는다(MZ2AZ-468·472).
 * 위치 동의를 취소해도 코스는 만들어진다(좌표 없이).
 */
import { useEffect, useRef, useState } from 'react'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { AIConsentSheet, LocationConsentSheet, useAIConsent, useLocationConsent } from '@/features/profile/consent'
import { tr, useLang } from '@/i18n'
import { likeActions, useLikes } from '@/state/likes'
import { RouteEditor } from '../editor/RouteEditor'
import { type RouteCourse, PACE_TITLE, type RoutePace, formatDay, spanLabel, toIso } from '../models'
import { type PlanFailure, planFailureMessage } from '../planFailure'
import { guideDraft, routeActions, routeStore, useSortedWorks } from '../store'
import { AIBanner } from '../ui/AIBanner'
import { GlassDialog } from '../ui/GlassDialog'
import { LargeButton } from '../ui/LargeButton'
import { DateStep, PaceStep, ReviewStep, SpanStep, WorkStep } from './WizardSteps'
import { Icon } from '@/design/Icon'
import { protoAvailable, protoStore } from '@/features/proto/protoState'
import { ProtoSpanStep, ProtoWorkStep } from '@/features/proto/ProtoWizardSteps'
import { useStore } from '@/state/storage'

export type WizardKind = 'aiPlan' | 'manual'
type Step = 'span' | 'dates' | 'works' | 'pace' | 'review'

export function stepsOf(kind: WizardKind): Step[] {
  return kind === 'aiPlan' ? ['span', 'dates', 'works', 'pace', 'review'] : ['span', 'dates']
}

/** 질문 제목 — 회의에서 문구까지 정했다(1부 54:01). */
export function titleOf(step: Step): string {
  switch (step) {
    case 'span': return tr('얼마나 다녀오나요?')
    case 'dates': return tr('언제 떠나나요?')
    case 'works': return tr('어떤 작품을 좋아하나요?')
    case 'pace': return tr('어떻게 다닐까요?')
    case 'review': return tr('이렇게 짜 드립니다')
  }
}

const todayIso = (): string => toIso(new Date())

/** 지금 어디에 있나 — 질문을 시작할 때 미리 물어 둔다(마지막 화면에서 물으면 위치가 오기를 기다리느라 코스 만들기가 그만큼 늦어진다). 못 받아도 코스는 만들어진다. */
function useLocatorOnce(enabled: boolean) {
  const [here, setHere] = useState<{ lat: number; lng: number } | null>(null)
  useEffect(() => {
    if (!enabled || !('geolocation' in navigator)) return
    let live = true
    navigator.geolocation.getCurrentPosition(
      (p) => live && setHere({ lat: p.coords.latitude, lng: p.coords.longitude }),
      () => undefined,
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60_000 },
    )
    return () => {
      live = false
    }
  }, [enabled])
  return here
}

export function RouteWizard({ kind, onClose }: { kind: WizardKind; onClose: () => void }) {
  useLang()
  const steps = stepsOf(kind)
  const [index, setIndex] = useState(0)
  const [planning, setPlanning] = useState(false)
  const [nights, setNights] = useState(1)
  const [hasDate, setHasDate] = useState(false)
  const [picked, setPicked] = useState(todayIso)
  const [workIds, setWorkIds] = useState<ReadonlySet<number>>(new Set())
  const [pace, setPace] = useState<RoutePace>('tight')
  const [draft, setDraft] = useState<RouteCourse | null>(null)
  const [failure, setFailure] = useState<PlanFailure | null>(null)
  const ai = useAIConsent()
  const location = useLocationConsent()
  const here = useLocatorOnce(kind === 'aiPlan')
  const works = useSortedWorks()
  const likes = useLikes()
  const planned = useRef(false)
  // 「어디로 가세요?」 시안이 켜져 있으면(로컬 개발 서버 전용) 전체 화면 모양 — 위 그라데이션 띠·고른 것 요약. 단계와 동작은 그대로.
  const proto = useStore(protoStore, (p) => protoAvailable && p.proto !== null)

  // 질문 재료(작품 30)는 목록과 따로 받는다 — 비어 있으면 받는다.
  useEffect(() => {
    if (kind === 'aiPlan') void routeActions.refreshMaterials()
  }, [kind])

  const step = steps[Math.min(index, steps.length - 1)]
  const last = index === steps.length - 1
  const startDate = hasDate ? picked : null

  const createAIPlan = () => {
    if (planned.current) return
    planned.current = true
    setPlanning(true)
    void guideDraft({
      nights, startDate, workIds, pace, near: here, locationAllowed: location.allowed, sortedWorks: works, places: routeStore.get().places,
    }).then((res) => {
      planned.current = false
      setPlanning(false)
      if (res.ok) setDraft(res.course)
      else setFailure(res.failure)
    })
  }

  const advance = () => {
    if (!last) return setIndex(index + 1)
    if (kind !== 'aiPlan') return setDraft(routeActions.emptyCourse(nights, startDate))
    // AI 동의 → (자리를 알면) 위치 동의 → 일정 생성.
    ai.request(() => {
      if (here !== null) location.request(createAIPlan)
      else createAIPlan()
    })
  }

  if (draft) return <RouteEditor course={draft} isNew onClose={onClose} />

  const finishLabel = kind === 'aiPlan' ? tr('AI 로 일정 짜기') : tr('코스 만들기')
  const reviewRows: Array<[string, string]> = [
    [tr('기간'), spanLabel(nights)],
    [tr('떠나는 날'), hasDate ? formatDay(picked) : tr('정하지 않음')],
    [tr('작품'), works.filter((w) => workIds.has(w.id)).map((w) => w.title).join(', ') || tr('인기 작품')],
    [tr('스타일'), tr(PACE_TITLE[pace])],
  ]

  return (
    <VStack style={{ height: '100%', background: 'var(--grouped-background)' }}>
      {proto ? (
        // 시안 머리 — 단색 보라 띠(흰 글씨 대비 6:1)에 닫기·단계 점, 그 아래 큰 질문(h1). 띠가 「AI 가 짠다」 를 말하므로 따로 띠를 두지 않는다.
        <VStack spacing={14} style={{ padding: 'calc(var(--safe-top) + 12px) 20px 20px', background: 'var(--home-purple)', flex: 'none' }}>
          <HStack justify="between">
            <Pressable plain onClick={onClose} aria-label={tr('취소')} style={{ width: 44, height: 44, borderRadius: 999, background: 'rgba(255,255,255,0.18)', display: 'grid', placeItems: 'center' }}>
              <Icon name="xmark" size={15} weight="bold" color="white" />
            </Pressable>
            <HStack spacing={5} role="progressbar" aria-valuenow={index + 1} aria-valuemax={steps.length}>
              {steps.map((st, i) => (
                <span key={st} style={{ width: i === index ? 18 : 7, height: 7, borderRadius: 999, background: i <= index ? '#fff' : 'rgba(255,255,255,0.4)', transition: 'width 0.25s' }} />
              ))}
            </HStack>
            <div style={{ width: 44 }} />
          </HStack>
          <VStack spacing={4} align="start">
            {kind === 'aiPlan' && (
              <HStack spacing={5}>
                <Icon name="sparkles" size={13} color="white" />
                <Txt size={12} weight="bold" color="white" style={{ opacity: 0.9, letterSpacing: 0.3 }}>{tr('AI 가 일정을 짜 드립니다')}</Txt>
              </HStack>
            )}
            <Txt as="h1" size={26} weight="heavy" color="white" style={{ margin: 0 }}>{titleOf(step)}</Txt>
          </VStack>
          {/* 지금까지 고른 것 — 빈 화면 대신 내 여행이 쌓여 가는 것이 보인다 */}
          <HStack spacing={6} wrap>
            {[spanLabel(nights), hasDate ? formatDay(picked) : null, workIds.size > 0 ? tr('작품 %d', workIds.size) : null, index >= steps.indexOf('pace') && steps.includes('pace') ? tr(PACE_TITLE[pace]) : null]
              .filter((t): t is string => !!t)
              .map((t) => (
                <span key={t} style={{ padding: '4px 10px', borderRadius: 999, fontSize: 12, fontWeight: 700, color: '#fff', background: 'rgba(255,255,255,0.2)', border: '1px solid rgba(255,255,255,0.35)' }}>{t}</span>
              ))}
          </HStack>
        </VStack>
      ) : (
      <VStack spacing={10} style={{ padding: '14px 20px 10px', background: 'var(--system-background)', flex: 'none' }}>
        <HStack justify="between">
          <Pressable plain onClick={onClose}><Txt size={17} color="accent">{tr('취소')}</Txt></Pressable>
          <Txt size={13} color="secondary">{`${index + 1} / ${steps.length}`}</Txt>
        </HStack>
        <div style={{ height: 3, borderRadius: 2, background: 'var(--system-gray5)', overflow: 'hidden' }} role="progressbar" aria-valuenow={index + 1} aria-valuemax={steps.length}>
          <div style={{ height: '100%', width: `${((index + 1) / steps.length) * 100}%`, background: 'var(--accent)', borderRadius: 2, transition: 'width 0.25s' }} />
        </div>
        {kind === 'aiPlan' && <AIBanner text={tr('AI 가 일정을 짜 드립니다')} padY={10} />}
      </VStack>
      )}

      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={16} style={{ padding: proto ? '18px 20px' : '16px 20px' }}>
          {!proto && <Txt size={20} weight="semibold">{titleOf(step)}</Txt>}
          {step === 'span' && (proto ? <ProtoSpanStep nights={nights} onPick={setNights} /> : <SpanStep nights={nights} onPick={setNights} />)}
          {step === 'dates' && (
            <DateStep
              picked={picked}
              hasDate={hasDate}
              nights={nights}
              today={todayIso()}
              onPick={(iso) => {
                setPicked(iso)
                setHasDate(true)
              }}
              onClear={() => setHasDate(false)}
            />
          )}
          {step === 'works' && proto && (
            <ProtoWorkStep
              works={works}
              liked={likes.contentIds}
              selected={workIds}
              onToggleSelect={(id) => setWorkIds((s) => { const n = new Set(s); if (!n.delete(id)) n.add(id); return n })}
              onToggleLike={(id) => void likeActions.toggle(id)}
            />
          )}
          {step === 'works' && !proto && (
            <WorkStep
              works={works}
              liked={likes.contentIds}
              selected={workIds}
              onToggleSelect={(id) => setWorkIds((s) => { const n = new Set(s); if (!n.delete(id)) n.add(id); return n })}
              onToggleLike={(id) => void likeActions.toggle(id)}
            />
          )}
          {step === 'pace' && <PaceStep pace={pace} onPick={setPace} />}
          {step === 'review' && <ReviewStep rows={reviewRows} />}
        </VStack>
      </div>

      {likes.failure && (
        <HStack spacing={12} align="start" style={{ margin: '0 12px 8px', padding: 12, borderRadius: 12, background: 'rgba(250,250,250,0.9)', boxShadow: '0 2px 12px rgba(0,0,0,0.12)' }}>
          <VStack spacing={4} align="start" style={{ flex: 1 }}>
            <Txt size={12} weight="semibold">{tr('찜을 바꾸지 못했습니다')}</Txt>
            <Txt size={12}>{likes.failure.userMessage}</Txt>
          </VStack>
          <Pressable plain onClick={likeActions.clearFailure} style={{ minHeight: 44 }}><Txt size={12}>{tr('닫기')}</Txt></Pressable>
        </HStack>
      )}

      <HStack spacing={10} style={{ padding: 16, background: 'var(--system-background)', flex: 'none' }}>
        {index > 0 && <LargeButton variant="bordered" size="regular" weight="regular" fontSize={15} onClick={() => setIndex(index - 1)}>{tr('이전')}</LargeButton>}
        {/* 라벨에 너비를 준다 — 바깥에만 너비를 걸면 누르는 자리만 넓어지고 파란 알약은 글자 크기 그대로 남는다(실측). */}
        <LargeButton variant="prominent" height={50.33} disabled={planning} onClick={advance} style={{ flex: 1, ...(proto ? { background: 'var(--home-purple)' } : {}) }}>
          {planning && <Spinner size={18} />}
          {planning ? tr('일정을 짜는 중입니다') : last ? finishLabel : tr('다음')}
        </LargeButton>
      </HStack>

      <GlassDialog
        open={failure !== null}
        variant="alert"
        title={tr('일정을 짜지 못했습니다')}
        message={failure ? planFailureMessage(failure) : undefined}
        buttons={[{ label: tr('확인'), role: 'cancel' }]}
        onClose={() => setFailure(null)}
      />
      <AIConsentSheet gate={ai.gate} />
      {/* 위치 동의를 취소해도 코스는 만들어진다 — 좌표 없이 */}
      <LocationConsentSheet gate={location.gate} onCancel={createAIPlan} />
    </VStack>
  )
}

