/*
 * 첫 실행에 한 번 보여 주는 사용법 넉 장. (iOS: Onboarding/OnboardingView.swift)
 * 고른 언어로 한 벌만 나온다. 원문은 한국어이고 그릴 때 tr() 로 번역한다.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { PinoMascot, type PinoPose } from '@/design/PinoMascot'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { readStorage, writeStorage } from '@/state/storage'
import { tr, useLang } from '@/i18n'
import { Backdrop } from './backdrops'
import { finish, isLast, next, previous, select, startProgress, type Progress } from './progress'

/** 사용법을 본 적이 있는지 — 판(version)으로 둔다. 장이 늘거나 기능이 바뀔 때만 올려 이미 쓰던 사람에게 한 번 더 보인다. */
const ONBOARDING_KEY = 'scenetrip.onboarding.seenVersion'
const ONBOARDING_VERSION = 1

export const hasSeenOnboarding = (): boolean => Number(readStorage(ONBOARDING_KEY) ?? 0) >= ONBOARDING_VERSION
export const markOnboardingSeen = (): void => writeStorage(ONBOARDING_KEY, String(ONBOARDING_VERSION))

interface Lesson {
  pose: PinoPose
  title: string
  body: string
}

// 한국어 원문 — 그릴 때 tr() 로 번역한다.
const LESSONS: Lesson[] = [
  { pose: 'magnifier', title: '그 장면,\n어디서 찍었을까', body: '드라마·영화 제목으로도, 장면 설명으로도 찾아요.\n실제 촬영지가 바로 지도에 뜹니다.' },
  { pose: 'sparkle', title: '속도만 정하세요.\n일정은 해태가 짭니다.', body: '원하는 속도에 맞춰 가까운 촬영지로 일정을 짭니다.\n완성된 초안은 원하는 대로 바꿀 수 있어요.' },
  { pose: 'paw', title: '가고 싶어지면\n길찾기를 누르세요', body: '코스를 따라 다음 장소로 가는 길을 확인해요.\n필요하면 지도 앱으로 길찾기를 이어갈 수 있어요.' },
  { pose: 'speech', title: '가는 길에 먹고,\n막히면 물어보세요', body: '주변의 음식점·명소·교통·숙소를 보여 줘요.\n촬영지와 여행 계획을 해태에게 물어보세요.' },
]

/** 곁들인 그림이 한쪽에 몰린 장에서는 해태를 반대쪽으로 비켜 세운다. */
function mascotFor(index: number): { width: number; dx: number; dy: number } {
  const width = index === 2 ? 128 : 186
  if (index === 1) return { width, dx: 62, dy: 0 }
  if (index === 2) return { width, dx: -100, dy: -74 }
  return { width, dx: 0, dy: 0 }
}

function LessonPage({ lesson, index }: { lesson: Lesson; index: number }) {
  const m = mascotFor(index)
  return (
    <div className="scroll-y" style={{ flex: '0 0 100%', scrollSnapAlign: 'start', height: '100%', display: 'flex' }}>
      <VStack spacing={20} align="center" justify="center" pad="12px 16px" style={{ width: '100%', minHeight: '100%' }}>
        {(() => {
          const size = 300
          return (
            <div style={{ position: 'relative', width: 'calc(300px * var(--lesson-scale, 1))', height: 'calc(300px * var(--lesson-scale, 1))', flex: 'none' }} aria-hidden>
              <div style={{ position: 'absolute', left: 0, top: 0, width: size, height: size, transformOrigin: '0 0', scale: 'var(--lesson-scale, 1)' }}>
                <Backdrop index={index} />
                <div style={{ position: 'absolute', left: 0, top: 0, width: size, height: size, display: 'grid', placeItems: 'center' }}>
                  <div style={{ transform: `translate(${m.dx}px, ${m.dy}px)` }}>
                    <PinoMascot pose={lesson.pose} width={m.width} />
                  </div>
                </div>
              </div>
            </div>
          )
        })()}
        <Txt as="h1" size={28} weight="bold" align="center">{tr(lesson.title)}</Txt>
        <Txt size={17} color="secondary" align="center" leading={1.3}>{tr(lesson.body)}</Txt>
      </VStack>
    </div>
  )
}

function PageDots({ count, current }: { count: number; current: number }) {
  return (
    <HStack spacing={6} justify="center" role="img" aria-label={tr('사용법 %d / %d', current + 1, count)}>
      {Array.from({ length: count }, (_, i) => (
        <span
          key={i}
          style={{
            height: 7, width: i === current ? 20 : 7, borderRadius: 4, transition: 'width 0.22s ease-in-out',
            background: i === current ? 'var(--accent)' : 'var(--system-gray4)',
          }}
        />
      ))}
    </HStack>
  )
}

export function OnboardingView({ onDone }: { onDone: () => void }) {
  useLang()
  const [progress, setProgress] = useState<Progress>(() => startProgress(LESSONS.length))
  const scroller = useRef<HTMLDivElement>(null)
  const last = isLast(progress)

  const go = useCallback((page: number) => {
    const el = scroller.current
    if (el) el.scrollTo({ left: page * el.clientWidth, behavior: 'smooth' })
  }, [])

  // 손으로 넘기면 스크롤 위치에서 쪽을 읽는다.
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const onScroll = () => {
      const page = Math.round(el.scrollLeft / el.clientWidth)
      setProgress((p) => (p.page === page ? p : select(p, page)))
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [])

  // 삽화는 300 고정 크기로 그린 뒤 폭에 맞춰 줄인다 (min(300, 폭-32)).
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const apply = () => el.style.setProperty('--lesson-scale', String(Math.min(300, Math.max(1, el.clientWidth - 32)) / 300))
    apply()
    const ro = new ResizeObserver(apply)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const complete = () => {
    const { progress: p, fired } = finish(progress)
    setProgress(p)
    if (!fired) return
    markOnboardingSeen()
    onDone()
  }

  return (
    <VStack style={{ position: 'absolute', inset: 0, zIndex: 100, background: 'var(--system-background)' }}>
      {/* 마지막 장에는 두지 않는다 — 거기 버튼이 이미 「시작하기」다. */}
      <HStack justify="end" pad="0 16px" style={{ minHeight: 44, paddingTop: 'var(--safe-top)' }}>
        <Pressable plain onClick={complete} disabled={last} style={{ opacity: last ? 0 : 1 }}>
          <Txt size={17} color="secondary">{tr('건너뛰기')}</Txt>
        </Pressable>
      </HStack>

      <div ref={scroller} className="scroll-x" style={{ flex: 1, minHeight: 0, display: 'flex', scrollSnapType: 'x mandatory' }}>
        {LESSONS.map((lesson, i) => (
          <LessonPage key={i} lesson={lesson} index={i} />
        ))}
      </div>

      <div style={{ paddingTop: 8, paddingBottom: 12 }}>
        <PageDots count={progress.count} current={progress.page} />
      </div>

      <HStack spacing={12} pad="12px 16px" style={{ background: 'var(--system-background)', paddingBottom: 'calc(12px + var(--safe-bottom))' }}>
        <Pressable
          plain
          disabled={progress.page === 0}
          onClick={() => {
            setProgress(previous(progress))
            go(progress.page - 1)
          }}
          style={{ minHeight: 44, padding: '0 4px' }}
        >
          <Txt size={17} weight="semibold" color={progress.page === 0 ? 'tertiary' : 'accent'}>{tr('이전')}</Txt>
        </Pressable>
        <Pressable
          plain
          onClick={() => {
            if (last) return complete()
            setProgress(next(progress))
            go(progress.page + 1)
          }}
          style={{ flex: 1, minHeight: 52, borderRadius: 12, background: 'var(--accent)', display: 'grid', placeItems: 'center', padding: '4px 0' }}
        >
          <Txt size={17} weight="semibold" color="white" align="center">{last ? tr('시작하기') : tr('다음')}</Txt>
        </Pressable>
      </HStack>
    </VStack>
  )
}
