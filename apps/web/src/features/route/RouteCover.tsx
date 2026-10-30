/*
 * 경로여정(코스) 덮개 — 첫 화면. iOS RouteTab/RouteTabView.swift.
 *
 * 8/11 회의에서 확정된 갈림길이 이 화면의 전부다. 코스가 하나도 없으면 「AI 로 짜기 / 직접 짜기」 두 갈래가 바로 뜨고(빈 상태),
 * 코스가 있으면 목록을 보여 준 뒤 「코스 추가」를 눌렀을 때 같은 두 갈래가 시트로 나온다.
 * 코스 목록 밀기 삭제·지우기 직전 확인·저장된 코스는 상세를 받은 뒤 편집 화면(전체 덮개)으로, 새 코스는 마법사(시트) 안에서 편집한다.
 *
 * URL: `/courses` 목록, `/courses/:id` 그 코스의 편집(새로 고침·딥링크도 열린다), `/courses/new` 코스 추가 시트.
 */
import { useEffect, useRef, useState } from 'react'
import { matchPath, useLocation, useNavigate } from 'react-router'
import { Icon } from '@/design/Icon'
import { Cover, Sheet } from '@/design/overlay'
import { HStack, Spacer, Spinner, Txt, VStack } from '@/design/primitives'
import { Pressable } from './ui/Press'
import { patterns, paths } from '@/app/routes'
import { consumePending, pendingStore } from '@/app/tabRouter'
import { tr, useLang } from '@/i18n'
import { trAt } from '@/i18n'
import { useStore } from '@/state/storage'
import { CourseList } from './CourseList'
import { ForkSheet } from './ForkSheet'
import { RouteEditor } from './editor/RouteEditor'
import './editor/tripExtensions'
import { type RouteCourse, makeCourse, makeDay } from './models'
import { routeActions, useRouteState } from './store'
import { GlassDialog } from './ui/GlassDialog'
import { LargeButton } from './ui/LargeButton'
import { PinoNudge } from './ui/PinoNudge'
import { useCourseOpening } from './useCourseOpening'
import { RouteWizard, type WizardKind } from './wizard/RouteWizard'
import './ui/icons'
import './ui/route.css'

export function RouteCover({ onClose }: { onClose: () => void }) {
  useLang()
  const state = useRouteState()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const opening = useCourseOpening()
  const [fork, setFork] = useState(false)
  const [wizard, setWizard] = useState<WizardKind | null>(null)
  /** 지우기 직전에 한 번 묻는다. 되돌릴 수 없다 — 서버에서 지우는 것이라 실행 취소가 없다. */
  const [doomed, setDoomed] = useState<RouteCourse | null>(null)
  const pendingCourseId = useStore(pendingStore, (s) => s.courseId)
  const lastEditing = useRef<RouteCourse | null>(null)
  if (opening.editing) lastEditing.current = opening.editing

  const courses = state.routeCourses
  const urlMatch = matchPath(patterns.course, pathname)
  const urlCourseId = urlMatch && urlMatch.params.courseId !== 'new' ? Number(urlMatch.params.courseId) : null

  // 탭이 뜰 때 목록과 재료를 받는다.
  useEffect(() => {
    void routeActions.refresh()
  }, [])

  // 마이페이지·홈에서 「경로여정에서 열기」로 넘어온 코스를 연다. 쪽지는 한 번 읽고 버린다 — 남겨 두면 탭에 올 때마다 또 열린다.
  useEffect(() => {
    if (pendingCourseId === null) return
    const course = courses.find((c) => c.serverId === pendingCourseId)
    if (!course) return
    consumePending('courseId')
    void opening.open(course)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingCourseId, courses])

  // 주소창으로 /courses/:id 에 곧바로 들어왔다 — 목록을 기다리지 않고 그 코스를 연다.
  const urlOpened = useRef<number | null>(null)
  useEffect(() => {
    if (urlCourseId === null) {
      urlOpened.current = null
      return
    }
    if (urlOpened.current === urlCourseId || opening.editing || opening.isLoading || pendingCourseId !== null) return
    urlOpened.current = urlCourseId
    void opening.open(makeCourse({ serverId: urlCourseId, days: [makeDay()] }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlCourseId, pendingCourseId])
  // 열린 코스를 주소에 싣는다.
  useEffect(() => {
    const id = opening.editing?.serverId
    if (id != null && urlCourseId !== id) navigate(paths.course(id), { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opening.editing])
  useEffect(() => {
    if (pathname === paths.courseNew) setFork(true)
  }, [pathname])

  const closeEditor = () => {
    opening.close()
    urlOpened.current = null
    if (urlCourseId !== null) navigate(paths.courses, { replace: true })
    // 가이드 챗봇의 한도 안내에서 「AI 로 짜기 열기」를 눌렀다 — 편집 화면이 다 닫힌 뒤에 마법사를 띄운다(닫히는 중에 띄우면 버려진다).
    if (consumePending('planner')) setTimeout(() => setWizard('aiPlan'), 320)
  }

  const closeFork = () => {
    setFork(false)
    if (pathname === paths.courseNew) navigate(paths.courses, { replace: true })
  }

  const shown = opening.editing ?? lastEditing.current
  return (
    <VStack style={{ height: '100%', background: 'var(--grouped-background)', paddingTop: 'var(--safe-top)' }}>
      <Header onClose={onClose} onAdd={() => setFork(true)} />
      {opening.failedCourse && <DetailLoadError onRetry={() => void opening.retry()} />}
      {courses.length === 0 ? (
        state.courseList === 'loading' ? (
          <VStack align="center" justify="center" style={{ flex: 1 }}><Spinner /></VStack>
        ) : (
          <EmptyState onAI={() => setWizard('aiPlan')} onManual={() => setWizard('manual')} />
        )
      ) : (
        <CourseList courses={courses} busy={opening.isLoading} onOpen={(c) => void opening.open(c)} onDelete={setDoomed} />
      )}

      <GlassDialog
        open={doomed !== null}
        variant="popover"
        title={doomed ? tr('「%@」을 지울까요?', doomed.title) : ''}
        message={tr('되돌릴 수 없습니다.')}
        buttons={[{ label: tr('삭제'), role: 'destructive', onPress: () => doomed && void routeActions.remove(doomed) }]}
        onClose={() => setDoomed(null)}
      />
      <ForkSheet open={fork} onClose={closeFork} onAI={() => setWizard('aiPlan')} onManual={() => setWizard('manual')} />
      <Sheet open={wizard !== null} onClose={() => undefined} dismissOnScrim={false} topGap={72} radius={38}>
        {wizard && <RouteWizard kind={wizard} onClose={() => setWizard(null)} />}
      </Sheet>
      <Cover open={opening.editing !== null}>
        {shown && <RouteEditor key={shown.uid} course={shown} isNew={false} onClose={closeEditor} />}
      </Cover>
    </VStack>
  )
}

/** 손수 그린 머리줄. 큰 제목 대신 「코스 추가」가 그 자리를 쓴다 — 이 탭의 첫 행동이라 피노 색으로 늘 반짝인다. */
function Header({ onClose, onAdd }: { onClose: () => void; onAdd: () => void }) {
  return (
    <div style={{ position: 'relative', padding: '10px 14px 8px' }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, bottom: 8, display: 'grid', placeItems: 'center', pointerEvents: 'none' }}>
        <Txt size={17} weight="semibold">{trAt('코스', '화면 제목')}</Txt>
      </div>
      <HStack>
        <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ padding: 8 }}>
          <Icon name="xmark" size={17} weight="semibold" />
        </Pressable>
        <Spacer />
        <PinoNudge on radius={15}>
          <Pressable plain onClick={onAdd} style={{ padding: '6px 10px', flexDirection: 'row', alignItems: 'center', gap: 5 }}>
            <Icon name="plus" size={12} weight="semibold" />
            <Txt size={12} weight="semibold">{tr('코스 추가')}</Txt>
          </Pressable>
        </PinoNudge>
      </HStack>
    </div>
  )
}

/**
 * 첫 사용자 화면. 갈림길이 아니라 빈 상태다 — 사용자에게 상태를 말하고(「아직 만든 코스가 없습니다」), 할 일을 버튼 둘로 준다.
 * AI 를 채운 버튼으로 두는 것은 회의 확정 사항이다(정승길, 8/11). 별도 띠로 광고하지 않고 설명 문장과 버튼 이름에 녹인다.
 */
function EmptyState({ onAI, onManual }: { onAI: () => void; onManual: () => void }) {
  return (
    <VStack align="center" justify="center" style={{ flex: 1, padding: '24px 0' }}>
      <VStack spacing={6} align="center" style={{ padding: '0 20px' }}>
        <div style={{ width: 56, height: 56, borderRadius: '50%', background: 'var(--pin-gradient)', display: 'grid', placeItems: 'center', marginBottom: 6 }}>
          <Icon name="sparkles" size={26} weight="medium" color="#fff" />
        </div>
        <Txt size={17} weight="semibold">{tr('아직 만든 코스가 없습니다')}</Txt>
        <Txt size={15} color="secondary" align="center">{tr('보고 싶은 작품과 기간만 고르면,\n촬영지를 이어서 일차별 일정으로 짜 드립니다')}</Txt>
      </VStack>
      <VStack spacing={10} style={{ padding: '22px 20px 0', width: '100%' }}>
        <LargeButton variant="prominent" textDy={2} onClick={onAI}>
          <Icon name="sparkles" size={17} color="#fff" />
          {tr('AI 로 여정 짜기')}
        </LargeButton>
        <LargeButton variant="bordered" textDy={1.67} onClick={onManual}>{tr('직접 짜기')}</LargeButton>
      </VStack>
    </VStack>
  )
}

function DetailLoadError({ onRetry }: { onRetry: () => void }) {
  return (
    <VStack spacing={8} align="start" style={{ padding: '10px 16px' }}>
      <HStack spacing={6}>
        <Icon name="exclamationmark.circle" size={15} />
        <Txt size={15}>{tr('코스를 불러오지 못했어요')}</Txt>
      </HStack>
      <LargeButton variant="bordered" size="regular" onClick={onRetry} weight="regular">{tr('다시 시도')}</LargeButton>
    </VStack>
  )
}
